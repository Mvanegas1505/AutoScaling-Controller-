package controller;

import model.BandaReactiva;
import model.Decision;
import model.Metrica;
import model.ParametrosControl;
import model.ResultadoDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Logic {

    // Nombres exactos de las métricas en CloudWatch (sección 4.1)
    public static final String CPU = "CPUUtilization";
    public static final String REQUESTS = "RequestCountPerTarget";
    public static final String HEALTHY = "HealthyHostCount";

    // Rango de instancias impuesto por el reto (no es configurable)
    public static final int MIN_INSTANCIAS = 1;
    public static final int MAX_INSTANCIAS = 5;

    private final ParametrosControl p;

    public Logic(ParametrosControl parametros) {
        this.p = parametros;
    }

    /**
     * Banda dinámica: promedio ± max(k·σ, margenMinimo).
     * Recibe solo lecturas ANTERIORES a la actual (sección 5.2), para que un pico
     * no se "jale a sí mismo" hacia el centro de la banda.
     */
    public BandaReactiva calcularBandaReactiva(List<Metrica> lecturasReferencia, double margenMinimo) {

        int n = lecturasReferencia.size();

        double suma = 0;
        for (Metrica m : lecturasReferencia) {
            suma += m.getValor();
        }
        double promedio = suma / n;

        double sumaDiferenciasCuadrado = 0;
        for (Metrica m : lecturasReferencia) {
            double diferencia = m.getValor() - promedio;
            sumaDiferenciasCuadrado += diferencia * diferencia;
        }
        double desviacionEstandar = Math.sqrt(sumaDiferenciasCuadrado / n);

        // piso mínimo: si el sistema estuvo muy estable (σ ≈ 0), la banda no se vuelve absurdamente angosta
        double margen = Math.max(p.k() * desviacionEstandar, margenMinimo);

        return new BandaReactiva(promedio, desviacionEstandar, margen,
                promedio - margen, promedio + margen);
    }

    /**
     * Pendiente de la regresión lineal simple. Se calcula sobre TODA la ventana,
     * incluida la lectura actual: para la tendencia, el dato más reciente es el más informativo.
     */
    public double calcularPendienteRegresion(List<Metrica> ventana) {

        int n = ventana.size();
        if (n < 2) {
            return 0; // con un solo punto no hay tendencia
        }

        double sumaX = 0, sumaY = 0, sumaXY = 0, sumaX2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = ventana.get(i).getValor();

            sumaX += x;
            sumaY += y;
            sumaXY += x * y;
            sumaX2 += x * x;
        }

        double numerador = (n * sumaXY) - (sumaX * sumaY);
        double denominador = (n * sumaX2) - (sumaX * sumaX);

        return numerador / denominador;
    }

    /** Valor esperado de la métrica dentro de horizonteMinutos si la tendencia continúa. */
    public double proyectarValor(double pendiente, double valorActual) {
        return valorActual + (pendiente * p.horizonteMinutos());
    }

    /**
     * Alerta proactiva (5.3): true solo si la métrica viene SUBIENDO y la proyección
     * supera el límite. Una tendencia a la baja nunca es breach al alza.
     */
    public boolean proyectarBreachSuperior(double pendiente, double valorActual, double limite) {
        if (pendiente <= 0) {
            return false;
        }
        return proyectarValor(pendiente, valorActual) > limite;
    }

    public ResultadoDecision decidir(State estado, int instanciasActivas) {

        List<Metrica> ventanaCpu = estado.getVentana(CPU);
        List<Metrica> ventanaRequest = estado.getVentana(REQUESTS);
        List<Metrica> ventanaHealthy = estado.getVentana(HEALTHY);

        // 1. arranque o ventana recién limpiada: sin lecturas suficientes no hay banda ni regresión
        if (ventanaCpu.size() < p.lecturasMinimas()
                || ventanaRequest.size() < p.lecturasMinimas()
                || ventanaHealthy.isEmpty()) {
            return decidirConVentanaInsuficiente(estado, instanciasActivas,
                    ventanaCpu, ventanaRequest, ventanaHealthy);
        }

        double valorActualCpu = ultima(ventanaCpu).getValor();
        double valorActualRequest = ultima(ventanaRequest).getValor();
        double healthyHostCountActual = ultima(ventanaHealthy).getValor();

        // 2. banda congelada durante una racha: si ya se llevan c lecturas seguidas confirmando
        //    un cambio, esas c lecturas NO entran a la banda de referencia. Así la banda no
        //    "absorbe" el cambio antes de que termine de confirmarse.
        int racha = Math.max(estado.getContadorConfirmacionIncrease(), estado.getContadorConfirmacionReduce());

        BandaReactiva bandaCpu = calcularBandaReactiva(referencia(ventanaCpu, racha), p.margenMinimoCpu());
        BandaReactiva bandaRequest = calcularBandaReactiva(referencia(ventanaRequest, racha), p.margenMinimoRequest());

        // 3. clasificación de cada métrica: por banda dinámica (detecta cambios)
        //    o por límites absolutos (detecta niveles malos aunque la banda ya se haya adaptado).
        //    V2: salir de la banda por arriba solo cuenta si además hay riesgo real, es decir,
        //    si la métrica pasa el nivel de riesgo (punto medio entre piso y techo). En el
        //    experimento 1, subir de 28% a 54% de CPU disparaba aumentos con un nivel seguro.
        double riesgoCpu = nivelRiesgoCpu();
        double riesgoRequest = nivelRiesgoRequest();

        boolean cpuAltaBanda = valorActualCpu > bandaCpu.limiteSuperior() && valorActualCpu > riesgoCpu;
        boolean cpuAltaAbs = valorActualCpu > p.techoCpu();
        boolean cpuBajaBanda = valorActualCpu < bandaCpu.limiteInferior();
        boolean cpuBajaAbs = valorActualCpu < p.pisoCpu();

        boolean reqAltaBanda = valorActualRequest > bandaRequest.limiteSuperior() && valorActualRequest > riesgoRequest;
        boolean reqAltaAbs = valorActualRequest > p.techoRequest();
        boolean reqBajaBanda = valorActualRequest < bandaRequest.limiteInferior();
        boolean reqBajaAbs = valorActualRequest < p.pisoRequest();

        boolean cpuAlta = cpuAltaBanda || cpuAltaAbs;
        boolean cpuBaja = cpuBajaBanda || cpuBajaAbs;
        boolean reqAlta = reqAltaBanda || reqAltaAbs;
        boolean reqBaja = reqBajaBanda || reqBajaAbs;

        // 4. componente proactivo (5.3). V2: la proyección se compara contra el TECHO absoluto,
        //    no contra la banda: anticipa saturación, no cambios. En el experimento 1, proyectar
        //    45% contra una banda de 22,7% disparó un aumento con carga baja.
        //    Solo cuenta si la CPU actual ya está por encima de su nivel normal (promedio de la
        //    banda): tras un pico aislado, la pendiente todavía "arrastra" ese pico.
        double pendienteCpu = calcularPendienteRegresion(ventanaCpu);
        double valorProyectadoCpu = proyectarValor(pendienteCpu, valorActualCpu);
        double limiteBreach = p.techoCpu();
        boolean breachProximo = valorActualCpu > bandaCpu.promedio()
                && proyectarBreachSuperior(pendienteCpu, valorActualCpu, limiteBreach);

        // 5. señal cruda de este ciclo (matriz 5.4, todavía sin confirmar)
        boolean senalCrudaIncrease = cpuAlta || reqAlta || breachProximo;
        boolean senalCrudaReduce = cpuBaja && reqBaja && !breachProximo;

        Decision decision;
        String razon;

        if (estado.enCooldown()) {
            // 6a. en cooldown se calcula todo (para el registro) pero no se actúa ni se cuentan
            //     confirmaciones: las métricas aún no reflejan la última acción (5.6 y 6.2)
            decision = Decision.MAINTAIN;
            razon = "En cooldown: las métricas aún no reflejan la última acción de escalado.";

        } else {
            // 6b. actualizar contadores de confirmación según la señal cruda
            if (senalCrudaIncrease) {
                estado.incrementarContadorConfirmacionIncrease();
            } else if (senalCrudaReduce) {
                estado.incrementarContadorConfirmacionReduce();
            } else {
                estado.resetearConfirmaciones();
            }

            if (senalCrudaIncrease) {
                String causas = causasAumento(cpuAltaBanda, cpuAltaAbs, reqAltaBanda, reqAltaAbs, breachProximo,
                        valorActualCpu, valorActualRequest, valorProyectadoCpu, limiteBreach, bandaCpu, bandaRequest);
                int confirmaciones = estado.getContadorConfirmacionIncrease();

                if (confirmaciones < p.confirmacionesIncrease()) {
                    decision = Decision.MAINTAIN;
                    razon = String.format("Señal de aumento sin confirmar (%d/%d): %s",
                            confirmaciones, p.confirmacionesIncrease(), causas);
                } else if (instanciasActivas >= MAX_INSTANCIAS) {
                    decision = Decision.MAINTAIN;
                    razon = "Aumento confirmado pero se alcanzó el máximo de " + MAX_INSTANCIAS
                            + " instancias (5.5): " + causas;
                } else {
                    decision = Decision.INCREASE;
                    razon = String.format("Aumento confirmado (%d/%d): %s",
                            confirmaciones, p.confirmacionesIncrease(), causas);
                }

            } else if (senalCrudaReduce) {
                String causas = causasReduccion(cpuBajaBanda, cpuBajaAbs, reqBajaBanda, reqBajaAbs,
                        valorActualCpu, valorActualRequest, bandaCpu, bandaRequest);
                int confirmaciones = estado.getContadorConfirmacionReduce();

                // compuertas de seguridad (5.7)
                if (confirmaciones < p.confirmacionesReduce()) {
                    decision = Decision.MAINTAIN;
                    razon = String.format("Señal de reducción sin confirmar (%d/%d): %s",
                            confirmaciones, p.confirmacionesReduce(), causas);

                } else if (instanciasActivas <= MIN_INSTANCIAS) {
                    decision = Decision.MAINTAIN;
                    razon = "Reducción confirmada pero ya se está en el mínimo de "
                            + MIN_INSTANCIAS + " instancia (5.5): " + causas;

                } else if (healthyHostCountActual <= p.healthyHostCountMinimo()) {
                    decision = Decision.MAINTAIN;
                    razon = String.format(Locale.US,
                            "Reducción bloqueada: HealthyHostCount=%.0f no supera el mínimo %d (5.7): %s",
                            healthyHostCountActual, p.healthyHostCountMinimo(), causas);

                } else {
                    // ¿es seguro? si se quita una instancia, la misma carga se reparte entre
                    // (n-1) instancias; no se reduce si eso dejaría al sistema por encima del techo
                    double factor = instanciasActivas / (instanciasActivas - 1.0);
                    double cpuTrasReducir = valorActualCpu * factor;
                    double reqTrasReducir = valorActualRequest * factor;

                    if (cpuTrasReducir >= p.techoCpu() || reqTrasReducir >= p.techoRequest()) {
                        decision = Decision.MAINTAIN;
                        razon = String.format(Locale.US,
                                "Reducción bloqueada: con %d instancias la carga estimada sería CPU %.2f / Requests %.2f, "
                                        + "por encima del techo (%.2f / %.2f): %s",
                                instanciasActivas - 1, cpuTrasReducir, reqTrasReducir,
                                p.techoCpu(), p.techoRequest(), causas);
                    } else {
                        decision = Decision.REDUCE;
                        razon = String.format(Locale.US,
                                "Reducción confirmada (%d/%d) y segura (carga estimada con %d instancias: CPU %.2f, Requests %.2f): %s",
                                confirmaciones, p.confirmacionesReduce(), instanciasActivas - 1,
                                cpuTrasReducir, reqTrasReducir, causas);
                    }
                }

            } else if (cpuBaja || reqBaja) {
                // una métrica pide bajar y la otra no: evidencia dividida (5.4)
                decision = Decision.MAINTAIN;
                razon = "Señales contradictorias: solo " + (cpuBaja ? "CPU" : "Requests")
                        + " está baja.";

            } else {
                decision = Decision.MAINTAIN;
                razon = "Métricas en rango y sin breach próximo.";
            }
        }

        return new ResultadoDecision(decision, razon, bandaCpu, bandaRequest,
                valorActualCpu, valorActualRequest, pendienteCpu, valorProyectadoCpu,
                breachProximo, healthyHostCountActual,
                estado.getContadorConfirmacionIncrease(), estado.getContadorConfirmacionReduce());
    }

    /**
     * V2: con la ventana insuficiente (arranque o justo después de una acción, porque el Daemon
     * limpia las ventanas) no hay banda ni regresión, pero el controller NO queda ciego:
     * el techo absoluto sigue funcionando como salvaguarda y puede confirmar un aumento.
     * Reducir nunca se hace sin ventana completa (es la acción riesgosa).
     */
    private ResultadoDecision decidirConVentanaInsuficiente(State estado, int instanciasActivas,
                                                           List<Metrica> ventanaCpu,
                                                           List<Metrica> ventanaRequest,
                                                           List<Metrica> ventanaHealthy) {
        String estadoVentana = String.format(Locale.US,
                "Ventana insuficiente (CPU=%d, Requests=%d, Healthy=%d lecturas; se requieren %d)",
                ventanaCpu.size(), ventanaRequest.size(), ventanaHealthy.size(), p.lecturasMinimas());

        if (estado.enCooldown()) {
            return ResultadoDecision.sinCalculo(Decision.MAINTAIN,
                    "En cooldown: las métricas aún no reflejan la última acción de escalado. " + estadoVentana + ".",
                    estado.getContadorConfirmacionIncrease(), estado.getContadorConfirmacionReduce());
        }

        List<String> causas = new ArrayList<>();
        if (!ventanaCpu.isEmpty() && ultima(ventanaCpu).getValor() > p.techoCpu()) {
            causas.add(String.format(Locale.US, "CPU %.2f > techo absoluto %.2f",
                    ultima(ventanaCpu).getValor(), p.techoCpu()));
        }
        if (!ventanaRequest.isEmpty() && ultima(ventanaRequest).getValor() > p.techoRequest()) {
            causas.add(String.format(Locale.US, "Requests %.2f > techo absoluto %.2f",
                    ultima(ventanaRequest).getValor(), p.techoRequest()));
        }

        Decision decision;
        String razon;
        if (causas.isEmpty()) {
            estado.resetearConfirmaciones();
            decision = Decision.MAINTAIN;
            razon = estadoVentana + ". Se mantiene la capacidad.";
        } else {
            estado.incrementarContadorConfirmacionIncrease();
            int confirmaciones = estado.getContadorConfirmacionIncrease();
            String txt = String.join("; ", causas) + " [salvaguarda con ventana insuficiente]";
            if (confirmaciones < p.confirmacionesIncrease()) {
                decision = Decision.MAINTAIN;
                razon = String.format("Señal de aumento sin confirmar (%d/%d): %s",
                        confirmaciones, p.confirmacionesIncrease(), txt);
            } else if (instanciasActivas >= MAX_INSTANCIAS) {
                decision = Decision.MAINTAIN;
                razon = "Aumento confirmado pero se alcanzó el máximo de " + MAX_INSTANCIAS
                        + " instancias (5.5): " + txt;
            } else {
                decision = Decision.INCREASE;
                razon = String.format("Aumento confirmado (%d/%d): %s",
                        confirmaciones, p.confirmacionesIncrease(), txt);
            }
        }
        return ResultadoDecision.sinCalculo(decision, razon,
                estado.getContadorConfirmacionIncrease(), estado.getContadorConfirmacionReduce());
    }

    // ---------- auxiliares ----------

    /** V2: nivel desde el cual una subida relativa (fuera de banda) se considera riesgo real. */
    private double nivelRiesgoCpu() {
        return (p.pisoCpu() + p.techoCpu()) / 2;
    }

    private double nivelRiesgoRequest() {
        return (p.pisoRequest() + p.techoRequest()) / 2;
    }

    private static Metrica ultima(List<Metrica> ventana) {
        return ventana.get(ventana.size() - 1);
    }

    /**
     * Lecturas de referencia para la banda: todas menos la actual y menos las
     * últimas 'racha' lecturas (las que ya forman parte del cambio en confirmación).
     * Siempre deja al menos 2 lecturas para poder calcular la desviación.
     */
    private static List<Metrica> referencia(List<Metrica> ventana, int racha) {
        List<Metrica> anteriores = ventana.subList(0, ventana.size() - 1);
        int excluir = Math.max(0, Math.min(racha, anteriores.size() - 2));
        return new ArrayList<>(anteriores.subList(0, anteriores.size() - excluir));
    }

    private String causasAumento(boolean cpuAltaBanda, boolean cpuAltaAbs, boolean reqAltaBanda,
                                 boolean reqAltaAbs, boolean breachProximo, double cpu, double req,
                                 double cpuProyectada, double limiteBreach,
                                 BandaReactiva bandaCpu, BandaReactiva bandaRequest) {
        List<String> causas = new ArrayList<>();
        if (cpuAltaAbs) {
            causas.add(String.format(Locale.US, "CPU %.2f > techo absoluto %.2f", cpu, p.techoCpu()));
        } else if (cpuAltaBanda) {
            causas.add(String.format(Locale.US, "CPU %.2f > límite superior de banda %.2f y > nivel de riesgo %.2f",
                    cpu, bandaCpu.limiteSuperior(), nivelRiesgoCpu()));
        }
        if (reqAltaAbs) {
            causas.add(String.format(Locale.US, "Requests %.2f > techo absoluto %.2f", req, p.techoRequest()));
        } else if (reqAltaBanda) {
            causas.add(String.format(Locale.US, "Requests %.2f > límite superior de banda %.2f y > nivel de riesgo %.2f",
                    req, bandaRequest.limiteSuperior(), nivelRiesgoRequest()));
        }
        if (breachProximo) {
            causas.add(String.format(Locale.US, "tendencia de CPU proyecta %.2f en %d min > techo %.2f",
                    cpuProyectada, p.horizonteMinutos(), limiteBreach));
        }
        return String.join("; ", causas);
    }

    private String causasReduccion(boolean cpuBajaBanda, boolean cpuBajaAbs, boolean reqBajaBanda,
                                   boolean reqBajaAbs, double cpu, double req,
                                   BandaReactiva bandaCpu, BandaReactiva bandaRequest) {
        String cpuTxt = cpuBajaAbs
                ? String.format(Locale.US, "CPU %.2f < piso absoluto %.2f", cpu, p.pisoCpu())
                : String.format(Locale.US, "CPU %.2f < límite inferior de banda %.2f", cpu, bandaCpu.limiteInferior());
        String reqTxt = reqBajaAbs
                ? String.format(Locale.US, "Requests %.2f < piso absoluto %.2f", req, p.pisoRequest())
                : String.format(Locale.US, "Requests %.2f < límite inferior de banda %.2f", req, bandaRequest.limiteInferior());
        return cpuTxt + " y " + reqTxt;
    }
}
