import aws.AwsGateway;
import controller.Daemon;
import controller.Logic;
import controller.State;
import logging.CicloLogger;
import model.ParametrosControl;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Arma todas las piezas y arranca el controller.
 *
 * Uso:  java -jar autoscaling-controller.jar [ruta/controller.properties]
 * Si no se pasa ruta, busca controller.properties en la carpeta actual.
 */
public class Main {

    public static void main(String[] args) throws IOException {

        String rutaConfig = args.length > 0 ? args[0] : "controller.properties";
        Properties config = cargar(rutaConfig);

        // --- AWS (obligatorios) ---
        String region = requerido(config, "aws.region");
        String launchTemplateId = requerido(config, "aws.launchTemplateId");
        String targetGroupArn = requerido(config, "aws.targetGroupArn");

        // --- Controller ---
        String rutaLog = config.getProperty("log.archivo", "ciclos.csv");
        int intervaloSegundos = entero(config, "controller.intervaloSegundos", 60);
        int cooldownCiclos = entero(config, "controller.cooldownCiclos", 3);
        int tamanoVentana = entero(config, "controller.tamanoVentana", 11);

        // --- Política: se parte de los valores por defecto y se sobreescriben los que haya
        //     en el archivo (así se calibran los límites sin recompilar) ---
        ParametrosControl d = ParametrosControl.porDefecto();
        ParametrosControl parametros = new ParametrosControl(
                d.k(), d.horizonteMinutos(), d.confirmacionesIncrease(), d.confirmacionesReduce(),
                d.healthyHostCountMinimo(),
                decimal(config, "politica.margenMinimoCpu", d.margenMinimoCpu()),
                decimal(config, "politica.margenMinimoRequest", d.margenMinimoRequest()),
                entero(config, "politica.lecturasMinimas", d.lecturasMinimas()),
                decimal(config, "politica.techoCpu", d.techoCpu()),
                decimal(config, "politica.pisoCpu", d.pisoCpu()),
                decimal(config, "politica.techoRequest", d.techoRequest()),
                decimal(config, "politica.pisoRequest", d.pisoRequest()));

        System.out.println("[Main] Región " + region + " | plantilla " + launchTemplateId);
        System.out.println("[Main] Política: " + parametros);
        System.out.println("[Main] Registro de ciclos en: " + Path.of(rutaLog).toAbsolutePath());

        // --- Armar las piezas (el Gateway falla aquí si la configuración de AWS está mal) ---
        CicloLogger logger = new CicloLogger(rutaLog);
        AwsGateway gateway = new AwsGateway(region, launchTemplateId, targetGroupArn);
        Logic logic = new Logic(parametros);
        State estado = new State(tamanoVentana);

        Daemon daemon = new Daemon(gateway, logic, estado, logger,
                Duration.ofSeconds(intervaloSegundos), cooldownCiclos);

        // Ctrl+C o apagado de la instancia: se detiene limpio y se cierra el CSV
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::detener));

        daemon.iniciar();
    }

    private static Properties cargar(String ruta) throws IOException {
        Path archivo = Path.of(ruta);
        if (Files.notExists(archivo)) {
            throw new IllegalArgumentException("No se encontró el archivo de configuración: "
                    + archivo.toAbsolutePath());
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static String requerido(Properties p, String clave) {
        String valor = p.getProperty(clave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Falta '" + clave + "' en el archivo de configuración");
        }
        return valor.trim();
    }

    private static int entero(Properties p, String clave, int porDefecto) {
        String valor = p.getProperty(clave);
        return (valor == null || valor.isBlank()) ? porDefecto : Integer.parseInt(valor.trim());
    }

    private static double decimal(Properties p, String clave, double porDefecto) {
        String valor = p.getProperty(clave);
        return (valor == null || valor.isBlank()) ? porDefecto : Double.parseDouble(valor.trim());
    }
}
