package logging;

import controller.Logic;
import model.BandaReactiva;
import model.Decision;
import model.Metrica;
import model.ResultadoAccion;
import model.ResultadoDecision;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Registro persistente de cada ciclo del controller (secciones 3.2.3 y 11 del documento).
 *
 * Escribe un archivo CSV con una fila por ciclo: qué se observó, cómo se analizó,
 * qué se decidió y por qué, qué se le pidió a AWS y qué pasó realmente.
 * Con eso se puede reconstruir cualquier decisión y graficar el experimento (10.3 / 10.4).
 */
public class CicloLogger implements AutoCloseable {

    // Métricas de contexto: no entran en la decisión pero se registran para el análisis (4.1)
    public static final String TARGET_RESPONSE_TIME = "TargetResponseTime";
    public static final String PROCESSED_BYTES = "ProcessedBytes";

    // ';' como separador y ',' como decimal: así lo abre directo el Excel en español.
    // Si tu Excel usa punto decimal, cambia DECIMAL_CON_COMA a false.
    private static final String SEPARADOR = ";";
    private static final boolean DECIMAL_CON_COMA = true;

    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String[] COLUMNAS = {
            "timestamp_utc",
            // observación
            "cpu", "requests_per_target", "healthy_host_count", "target_response_time", "processed_bytes",
            "instancias_antes",
            // análisis
            "cpu_banda_inf", "cpu_banda_prom", "cpu_banda_sup",
            "req_banda_inf", "req_banda_prom", "req_banda_sup",
            "pendiente_cpu", "cpu_proyectada", "breach_proximo",
            "confirmaciones_increase", "confirmaciones_reduce",
            // decisión
            "decision", "razon",
            // acción y resultado
            "accion", "resultado_accion", "detalle_accion",
            "instancias_despues"
    };

    private final Path archivo;
    private final BufferedWriter writer;

    /**
     * Abre (o crea) el archivo en modo append: si el controller se reinicia,
     * sigue agregando filas debajo sin borrar el historial.
     * Si el archivo no se puede abrir, lanza la excepción al arrancar: es mejor
     * enterarse de inmediato que correr el experimento sin registro.
     */
    public CicloLogger(String rutaArchivo) throws IOException {
        this.archivo = Path.of(rutaArchivo);

        Path carpeta = archivo.toAbsolutePath().getParent();
        if (carpeta != null) {
            Files.createDirectories(carpeta);
        }

        boolean archivoNuevo = Files.notExists(archivo) || Files.size(archivo) == 0;

        this.writer = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        if (archivoNuevo) {
            // BOM de UTF-8: sin esto, Excel muestra mal las tildes y la ñ
            writer.write('﻿');
            writer.write(String.join(SEPARADOR, COLUMNAS));
            writer.newLine();
            writer.flush();
        }
    }

    /**
     * Registra un ciclo completo. Se llama una vez por ciclo, al final,
     * después de que el Actuator intentó (o no) la acción.
     *
     * Nunca lanza excepción: si falla la escritura, lo reporta por consola y el
     * controller sigue funcionando (la disponibilidad va primero).
     */
    public synchronized void registrarCiclo(Instant timestamp,
                                            List<Metrica> lecturasDelCiclo,
                                            int instanciasAntes,
                                            ResultadoDecision decision,
                                            ResultadoAccion accion,
                                            int instanciasDespues) {

        BandaReactiva bandaCpu = decision.bandaCpu();
        BandaReactiva bandaReq = decision.bandaRequest();

        String[] valores = {
                FORMATO_FECHA.format(timestamp),

                numero(buscar(lecturasDelCiclo, Logic.CPU)),
                numero(buscar(lecturasDelCiclo, Logic.REQUESTS)),
                numero(buscar(lecturasDelCiclo, Logic.HEALTHY)),
                numero(buscar(lecturasDelCiclo, TARGET_RESPONSE_TIME)),
                numero(buscar(lecturasDelCiclo, PROCESSED_BYTES)),
                String.valueOf(instanciasAntes),

                bandaCpu == null ? "" : numero(bandaCpu.limiteInferior()),
                bandaCpu == null ? "" : numero(bandaCpu.promedio()),
                bandaCpu == null ? "" : numero(bandaCpu.limiteSuperior()),
                bandaReq == null ? "" : numero(bandaReq.limiteInferior()),
                bandaReq == null ? "" : numero(bandaReq.promedio()),
                bandaReq == null ? "" : numero(bandaReq.limiteSuperior()),
                numero(decision.pendienteCpu()),
                numero(decision.valorProyectadoCpu()),
                decision.breachProximo() ? "SI" : "NO",
                String.valueOf(decision.contadorIncrease()),
                String.valueOf(decision.contadorReduce()),

                nombreDelReto(decision.decision()),
                texto(decision.razon()),

                texto(accion.accion()),
                accion.resultado().name(),
                texto(accion.detalle()),
                String.valueOf(instanciasDespues)
        };

        String lineaConsola = String.format("[%s UTC] instancias %d -> %d | %s | %s %s | %s",
                FORMATO_FECHA.format(timestamp), instanciasAntes, instanciasDespues,
                nombreDelReto(decision.decision()), accion.accion(), accion.resultado(),
                decision.razon());

        try {
            writer.write(String.join(SEPARADOR, valores));
            writer.newLine();
            writer.flush(); // se guarda de una vez: si el proceso se cae, no se pierde el ciclo
            System.out.println(lineaConsola);
        } catch (IOException e) {
            System.err.println("[CicloLogger] No se pudo escribir en " + archivo + ": " + e.getMessage());
            System.err.println("[CicloLogger] Ciclo no persistido -> " + lineaConsola);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("[CicloLogger] Error al cerrar " + archivo + ": " + e.getMessage());
        }
    }

    // ---------- auxiliares ----------

    /** Nombres exactos que exige la sección 9 del reto. */
    private static String nombreDelReto(Decision decision) {
        return switch (decision) {
            case INCREASE -> "INCREASE_CAPACITY";
            case REDUCE -> "REDUCE_CAPACITY";
            case MAINTAIN -> "MAINTAIN_CAPACITY";
        };
    }

    /** Valor de una métrica en las lecturas del ciclo; NaN si no llegó (dato faltante, 4.4). */
    private static double buscar(List<Metrica> lecturas, String nombre) {
        if (lecturas == null) {
            return Double.NaN;
        }
        for (Metrica m : lecturas) {
            if (m.getNombre().equals(nombre)) {
                return m.getValor();
            }
        }
        return Double.NaN;
    }

    /** Número con 4 decimales; celda vacía si no hay valor (NaN o infinito). */
    private static String numero(double valor) {
        if (Double.isNaN(valor) || Double.isInfinite(valor)) {
            return "";
        }
        String s = String.format(Locale.US, "%.4f", valor);
        return DECIMAL_CON_COMA ? s.replace('.', ',') : s;
    }

    /**
     * Campo de texto siempre entre comillas, con las comillas internas duplicadas
     * (regla del formato CSV). Así la razón puede traer ';', comas o comillas sin
     * partir la fila.
     */
    private static String texto(String valor) {
        if (valor == null) {
            return "\"\"";
        }
        String limpio = valor.replace("\r", " ").replace("\n", " ");
        return "\"" + limpio.replace("\"", "\"\"") + "\"";
    }
}