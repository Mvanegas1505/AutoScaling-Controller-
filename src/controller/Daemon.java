package controller;

import aws.AwsGateway;
import logging.CicloLogger;
import model.Decision;
import model.Metrica;
import model.ResultadoAccion;
import model.ResultadoDecision;

import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * El proceso que corre todo el tiempo (sección 3.2.1). Cada ciclo:
 *
 *   1. OBSERVAR  -> pide a AWS las instancias registradas y las métricas (Observer)
 *   2. VALIDAR   -> descarta datos repetidos o imposibles (sección 7)
 *   3. DECIDIR   -> le pasa el estado a Logic y recibe la decisión con su razón
 *   4. ACTUAR    -> si hay que subir o bajar, se lo pide al Gateway (Actuator)
 *   5. REGISTRAR -> guarda todo el ciclo en el CSV
 *
 * Solo el Daemon modifica el cooldown y reinicia confirmaciones después de una acción:
 * Logic razona, el Daemon ejecuta los efectos.
 */
public class Daemon {

    private final AwsGateway gateway;
    private final Logic logic;
    private final State estado;
    private final CicloLogger logger;
    private final Duration intervalo;
    private final int cooldownCiclos;

    // último timestamp de CloudWatch visto por métrica, para no meter el mismo minuto dos veces
    private final Map<String, Instant> ultimoTimestamp = new HashMap<>();

    // último número de instancias conocido, para el registro cuando AWS no responde
    private int ultimasInstanciasConocidas = 0;

    private ScheduledExecutorService planificador;

    public Daemon(AwsGateway gateway, Logic logic, State estado, CicloLogger logger,
                            Duration intervalo, int cooldownCiclos) {
        this.gateway = gateway;
        this.logic = logic;
        this.estado = estado;
        this.logger = logger;
        this.intervalo = intervalo;
        this.cooldownCiclos = cooldownCiclos;
    }

    /**
     * Arranca el ciclo. Se usa "fixed delay": el siguiente ciclo empieza 'intervalo' después
     * de que TERMINA el anterior. Así, si una acción tarda (lanzar una instancia toma ~1 min),
     * los ciclos nunca se enciman.
     */
    public void iniciar() {
        planificador = Executors.newSingleThreadScheduledExecutor();
        planificador.scheduleWithFixedDelay(this::ejecutarCicloSeguro,
                0, intervalo.toSeconds(), TimeUnit.SECONDS);
        System.out.println("[Daemon] Controller iniciado. Ciclo cada " + intervalo.toSeconds()
                + " s, cooldown de " + cooldownCiclos + " ciclos. Ctrl+C para detener.");
    }

    /** Detiene el ciclo y cierra el log y los clientes de AWS. */
    public void detener() {
        System.out.println("[Daemon] Deteniendo controller...");
        if (planificador != null) {
            planificador.shutdown();
            try {
                // si justo está a mitad de un ciclo (ej. lanzando una instancia), se le deja terminar
                planificador.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.close();
        gateway.close();
        System.out.println("[Daemon] Controller detenido.");
    }

    /**
     * Si un ciclo lanza una excepción no prevista, el planificador de Java deja de ejecutar
     * los siguientes EN SILENCIO. Esta envoltura lo impide: se reporta y el controller sigue.
     */
    private void ejecutarCicloSeguro() {
        try {
            ejecutarCiclo();
        } catch (Exception e) {
            System.err.println("[Daemon] Error inesperado en el ciclo (se continúa): " + e);
            e.printStackTrace();
        }
    }

    /** Un ciclo completo. Normalmente lo llama el planificador; es público para poder probarlo. */
    public void ejecutarCiclo() {
        Instant inicioCiclo = Instant.now();

        // ---------- 1. OBSERVAR ----------
        List<String> instancias;
        List<Metrica> lecturas;
        try {
            instancias = gateway.instanciasRegistradas();
            lecturas = gateway.obtenerMetricas(instancias);
        } catch (SdkException e) {
            // Sin observación no se decide nada: se mantiene (sección 7)
            registrarSinDecision(inicioCiclo, List.of(), ultimasInstanciasConocidas,
                    "No se pudo consultar AWS: " + e.getMessage() + ". Se mantiene la capacidad.");
            estado.avanzarCiclo();
            return;
        }
        int instanciasAntes = instancias.size();
        ultimasInstanciasConocidas = instanciasAntes;

        // ---------- 2. VALIDAR ----------
        // V2: durante el cooldown las lecturas NO entran a la ventana. CloudWatch va ~3 min
        // atrasado, así que esos datos todavía son de antes de la acción (o de la transición)
        // y ensuciarían la banda y la regresión. Sí se marca su timestamp como visto.
        boolean enCooldown = estado.enCooldown();
        List<String> descartes = new ArrayList<>();
        for (Metrica m : lecturas) {
            if (!esValida(m)) {
                descartes.add(m.getNombre() + " fuera de rango (" + m.getValor() + ")");
            } else if (!esNueva(m)) {
                descartes.add(m.getNombre() + " repetida");
            } else {
                if (!enCooldown) {
                    estado.agregarLectura(m);
                }
                ultimoTimestamp.put(m.getNombre(), m.getTimestamp());
            }
        }

        if (enCooldown) {
            registrarSinDecision(inicioCiclo, lecturas, instanciasAntes,
                    "En cooldown (quedan " + estado.getCooldownRestante() + " ciclos): las métricas aún no "
                            + "reflejan la última acción. Las lecturas no entran a la ventana. Se mantiene la capacidad.");
            estado.avanzarCiclo();
            return;
        }

        // Las 3 métricas que usa la decisión deben tener un dato NUEVO en este ciclo.
        // Si no, Logic decidiría con la ventana del ciclo anterior (dato viejo): se mantiene.
        List<String> faltantes = new ArrayList<>();
        for (String necesaria : List.of(Logic.CPU, Logic.REQUESTS, Logic.HEALTHY)) {
            if (!llegoNueva(necesaria, lecturas, descartes)) {
                faltantes.add(necesaria);
            }
        }
        if (!faltantes.isEmpty()) {
            String razon = "Dato faltante, repetido o anómalo en " + String.join(", ", faltantes)
                    + (descartes.isEmpty() ? "" : " [" + String.join("; ", descartes) + "]")
                    + ". Se mantiene la capacidad (sección 7).";
            registrarSinDecision(inicioCiclo, lecturas, instanciasAntes, razon);
            estado.avanzarCiclo();
            return;
        }

        // ---------- 3. DECIDIR ----------
        ResultadoDecision decision = logic.decidir(estado, instanciasAntes);

        // ---------- 4. ACTUAR ----------
        ResultadoAccion accion = ResultadoAccion.ninguna();
        int instanciasDespues = instanciasAntes;

        if (decision.decision() == Decision.INCREASE) {
            accion = gateway.lanzarYRegistrar();
            if (accion.fueExitosa()) {
                instanciasDespues = instanciasAntes + 1;
            }
        } else if (decision.decision() == Decision.REDUCE) {
            accion = gateway.desregistrarYTerminar();
            if (accion.fueExitosa()) {
                instanciasDespues = instanciasAntes - 1;
            }
        }

        if (accion.fueExitosa()) {
            // Acción aplicada: se reinician rachas y empieza el cooldown (5.6 / 6.2).
            // Este ciclo NO descuenta cooldown, así dura 'cooldownCiclos' ciclos completos.
            estado.resetearConfirmaciones();
            estado.activarCooldown(cooldownCiclos);
            // V2: las métricas son POR INSTANCIA y cambian de nivel con la acción aunque la
            // demanda no cambie: la ventana vuelve a empezar con la nueva capacidad
            estado.limpiarVentanas();
        } else {
            // Si la acción falló, no se toca nada: la racha sigue y se reintenta el próximo ciclo (6.3)
            estado.avanzarCiclo();
        }

        ultimasInstanciasConocidas = instanciasDespues;

        // ---------- 5. REGISTRAR ----------
        logger.registrarCiclo(inicioCiclo, lecturas, instanciasAntes, decision, accion, instanciasDespues);
    }

    // ---------- auxiliares ----------

    private void registrarSinDecision(Instant inicio, List<Metrica> lecturas, int instancias, String razon) {
        ResultadoDecision r = ResultadoDecision.sinCalculo(Decision.MAINTAIN, razon,
                estado.getContadorConfirmacionIncrease(), estado.getContadorConfirmacionReduce());
        logger.registrarCiclo(inicio, lecturas, instancias, r, ResultadoAccion.ninguna(), instancias);
    }

    /** Rango físicamente posible (sección 7): lo que esté fuera se descarta como anómalo. */
    private static boolean esValida(Metrica m) {
        double v = m.getValor();
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
            return false;
        }
        if (m.getNombre().equals(Logic.CPU)) {
            return v <= 100;
        }
        if (m.getNombre().equals(Logic.HEALTHY)) {
            return v <= Logic.MAX_INSTANCIAS + 1; // +1 de margen por una instancia saliendo
        }
        return true;
    }

    /** Un dato es nuevo si su timestamp de CloudWatch es posterior al último que ya se usó. */
    private boolean esNueva(Metrica m) {
        Instant anterior = ultimoTimestamp.get(m.getNombre());
        return anterior == null || m.getTimestamp().isAfter(anterior);
    }

    private static boolean llegoNueva(String nombre, List<Metrica> lecturas, List<String> descartes) {
        boolean llego = lecturas.stream().anyMatch(m -> m.getNombre().equals(nombre));
        boolean descartada = descartes.stream().anyMatch(d -> d.startsWith(nombre + " "));
        return llego && !descartada;
    }
}
