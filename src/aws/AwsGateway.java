package aws;

import controller.Logic;
import logging.CicloLogger;
import model.Metrica;
import model.ResultadoAccion;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeregisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Único punto de contacto con AWS (fachada). Cumple dos roles del Controller Daemon:
 *
 *  - Observer: leer las métricas de CloudWatch y saber qué instancias están en el target group.
 *  - Actuator: lanzar/registrar y desregistrar/terminar instancias (sección 6.1).
 *
 * Credenciales: no se escriben en ningún lado. El SDK las toma solo del rol de la instancia
 * (LabInstanceProfile) cuando corre en EC2, o de ~/.aws/credentials en pruebas locales.
 */
public class AwsGateway implements AutoCloseable {

    // Etiqueta que se pone a cada instancia creada por el controller.
    // Solo se terminan instancias con esta etiqueta: la inicial (creada a mano) nunca se toca.
    public static final String TAG_CLAVE = "CreadoPor";
    public static final String TAG_VALOR = "autoscaling-controller";

    public static final String ACCION_LANZAR = "LANZAR_Y_REGISTRAR";
    public static final String ACCION_RETIRAR = "DESREGISTRAR_Y_TERMINAR";

    private static final int PERIODO_SEGUNDOS = 60;                          // resolución de 1 minuto
    private static final Duration VENTANA_CONSULTA = Duration.ofMinutes(10);  // cuánto hacia atrás se busca
    private static final Duration EDAD_MAXIMA_DATO = Duration.ofMinutes(3);   // más viejo = "llegó tarde" (4.4)
    private static final Duration ESPERA_MAX_ARRANQUE = Duration.ofMinutes(4);
    private static final Duration ESPERA_MAX_DRENADO = Duration.ofMinutes(2);

    private final CloudWatchClient cloudWatch;
    private final Ec2Client ec2;
    private final ElasticLoadBalancingV2Client elb;

    private final String launchTemplateId;
    private final String targetGroupArn;
    private final String dimensionTargetGroup;   // "targetgroup/nombre/id"
    private final String dimensionLoadBalancer;  // "app/nombre/id"

    /**
     * Crea los clientes y averigua a qué Load Balancer pertenece el target group.
     * Si la configuración está mal (ARN equivocado, sin permisos, sin credenciales),
     * falla aquí, al arrancar, y no a mitad del experimento.
     */
    public AwsGateway(String region, String launchTemplateId, String targetGroupArn) {
        Region r = Region.of(region);
        this.cloudWatch = CloudWatchClient.builder().region(r).build();
        this.ec2 = Ec2Client.builder().region(r).build();
        this.elb = ElasticLoadBalancingV2Client.builder().region(r).build();

        this.launchTemplateId = launchTemplateId;
        this.targetGroupArn = targetGroupArn;

        // CloudWatch identifica el target group por la parte final del ARN
        this.dimensionTargetGroup = targetGroupArn.substring(targetGroupArn.lastIndexOf(':') + 1);
        this.dimensionLoadBalancer = consultarDimensionLoadBalancer();
    }

    // =====================================================================
    // OBSERVER
    // =====================================================================

    /**
     * Instancias registradas en el target group, sin contar las que están saliendo (draining).
     * Es la fuente de verdad de "cuántas instancias hay": lo que realmente recibe tráfico.
     *
     * @throws SdkException si AWS no responde; el Daemon debe tratarlo como dato faltante.
     */
    public List<String> instanciasRegistradas() {
        DescribeTargetHealthResponse respuesta = elb.describeTargetHealth(
                DescribeTargetHealthRequest.builder().targetGroupArn(targetGroupArn).build());

        List<String> ids = new ArrayList<>();
        for (TargetHealthDescription d : respuesta.targetHealthDescriptions()) {
            // "draining" y "unhealthy.draining" = saliendo del balanceador: no cuentan
            if (!d.targetHealth().stateAsString().contains("draining")) {
                ids.add(d.target().id());
            }
        }
        return ids;
    }

    /**
     * Las 5 métricas del último minuto completo, en UNA sola llamada GetMetricData (4.2).
     *
     * - CPUUtilization: se pide por instancia y se promedia (sin Auto Scaling Group no hay
     *   una métrica de CPU del grupo). Instancias sin dato (recién creadas) no entran al promedio.
     * - Una métrica sin dato reciente NO se incluye en la lista: el Daemon la trata como faltante.
     * - Excepción: RequestCountPerTarget y ProcessedBytes. CloudWatch no publica nada cuando no
     *   hay tráfico; si el ALB sí está reportando (hay HealthyHostCount), su ausencia significa 0.
     *
     * El timestamp de cada Metrica es el del dato en CloudWatch, así el Daemon puede detectar
     * si le llegó el mismo minuto dos veces.
     *
     * @throws SdkException si CloudWatch no responde; el Daemon debe tratarlo como dato faltante.
     */
    public List<Metrica> obtenerMetricas(List<String> instanceIds) {

        // Último minuto COMPLETO: el minuto en curso todavía está acumulando datos.
        Instant fin = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);
        Instant inicio = fin.minus(VENTANA_CONSULTA);

        List<MetricDataQuery> consultas = new ArrayList<>();

        for (int i = 0; i < instanceIds.size(); i++) {
            consultas.add(consulta("cpu" + i, "AWS/EC2", "CPUUtilization", "Average",
                    dimension("InstanceId", instanceIds.get(i))));
        }
        consultas.add(consulta("req", "AWS/ApplicationELB", "RequestCountPerTarget", "Sum",
                dimension("TargetGroup", dimensionTargetGroup),
                dimension("LoadBalancer", dimensionLoadBalancer)));
        consultas.add(consulta("healthy", "AWS/ApplicationELB", "HealthyHostCount", "Average",
                dimension("TargetGroup", dimensionTargetGroup),
                dimension("LoadBalancer", dimensionLoadBalancer)));
        consultas.add(consulta("latencia", "AWS/ApplicationELB", "TargetResponseTime", "Average",
                dimension("TargetGroup", dimensionTargetGroup),
                dimension("LoadBalancer", dimensionLoadBalancer)));
        consultas.add(consulta("bytes", "AWS/ApplicationELB", "ProcessedBytes", "Sum",
                dimension("LoadBalancer", dimensionLoadBalancer)));

        GetMetricDataResponse respuesta = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                .startTime(inicio)
                .endTime(fin)
                .scanBy(ScanBy.TIMESTAMP_DESCENDING) // el dato más reciente queda primero
                .metricDataQueries(consultas)
                .build());

        Map<String, MetricDataResult> porId = new HashMap<>();
        for (MetricDataResult resultado : respuesta.metricDataResults()) {
            porId.put(resultado.id(), resultado);
        }

        Instant limite = fin.minus(EDAD_MAXIMA_DATO);
        List<Metrica> metricas = new ArrayList<>();

        // CPU: promedio entre las instancias que sí reportaron
        double sumaCpu = 0;
        int instanciasConDato = 0;
        Instant timestampCpu = null;
        for (int i = 0; i < instanceIds.size(); i++) {
            Punto p = ultimoPunto(porId.get("cpu" + i), limite);
            if (p != null) {
                sumaCpu += p.valor();
                instanciasConDato++;
                if (timestampCpu == null || p.timestamp().isAfter(timestampCpu)) {
                    timestampCpu = p.timestamp();
                }
            }
        }
        if (instanciasConDato > 0) {
            metricas.add(new Metrica(Logic.CPU, sumaCpu / instanciasConDato, timestampCpu));
        }

        Punto healthy = ultimoPunto(porId.get("healthy"), limite);
        if (healthy != null) {
            metricas.add(new Metrica(Logic.HEALTHY, healthy.valor(), healthy.timestamp()));
        }

        agregarConteo(metricas, Logic.REQUESTS, ultimoPunto(porId.get("req"), limite), healthy);
        agregarConteo(metricas, CicloLogger.PROCESSED_BYTES, ultimoPunto(porId.get("bytes"), limite), healthy);

        Punto latencia = ultimoPunto(porId.get("latencia"), limite);
        if (latencia != null) {
            metricas.add(new Metrica(CicloLogger.TARGET_RESPONSE_TIME, latencia.valor(), latencia.timestamp()));
        }

        return metricas;
    }

    // =====================================================================
    // ACTUATOR
    // =====================================================================

    /**
     * Aumentar capacidad (6.1): RunInstances con la plantilla → esperar a que esté "running"
     * → RegisterTargets en el target group. Desde ahí el ALB empieza a mandarle tráfico
     * (cuando pase el health check).
     *
     * Si algo falla después de crear la instancia, se termina para no dejar una máquina
     * prendida por fuera del balanceador. Nunca lanza excepción: devuelve el resultado.
     */
    public ResultadoAccion lanzarYRegistrar() {
        String instanceId = null;
        try {
            RunInstancesResponse respuesta = ec2.runInstances(RunInstancesRequest.builder()
                    .launchTemplate(LaunchTemplateSpecification.builder()
                            .launchTemplateId(launchTemplateId)
                            .build())
                    .minCount(1)
                    .maxCount(1)
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key(TAG_CLAVE).value(TAG_VALOR).build())
                            .build())
                    .build());

            instanceId = respuesta.instances().get(0).instanceId();

            ec2.waiter().waitUntilInstanceRunning(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build(),
                    WaiterOverrideConfiguration.builder().waitTimeout(ESPERA_MAX_ARRANQUE).build());

            elb.registerTargets(RegisterTargetsRequest.builder()
                    .targetGroupArn(targetGroupArn)
                    .targets(TargetDescription.builder().id(instanceId).build())
                    .build());

            return ResultadoAccion.exito(ACCION_LANZAR, instanceId);

        } catch (SdkException e) {
            String detalle = e.getMessage();
            if (instanceId != null) {
                detalle = instanceId + ": " + detalle + terminarPorLimpieza(instanceId);
            }
            return ResultadoAccion.fallo(ACCION_LANZAR, detalle);
        }
    }

    /**
     * Reducir capacidad (6.1): elegir la instancia más nueva creada por el controller →
     * DeregisterTargets (el ALB deja de mandarle tráfico) → esperar a que termine las
     * peticiones en curso → TerminateInstances. En ese orden, para no cortar peticiones.
     *
     * Nunca lanza excepción: devuelve el resultado.
     */
    public ResultadoAccion desregistrarYTerminar() {
        String instanceId;
        try {
            instanceId = elegirInstanciaParaRetirar();
            if (instanceId == null) {
                return ResultadoAccion.fallo(ACCION_RETIRAR,
                        "No hay instancias creadas por el controller para retirar (la inicial nunca se retira)");
            }
            elb.deregisterTargets(DeregisterTargetsRequest.builder()
                    .targetGroupArn(targetGroupArn)
                    .targets(TargetDescription.builder().id(instanceId).build())
                    .build());
        } catch (SdkException e) {
            return ResultadoAccion.fallo(ACCION_RETIRAR, e.getMessage());
        }

        // Esperar el drenado. Si tarda más de la cuenta se termina igual: ya no recibe tráfico nuevo.
        String nota = "";
        try {
            elb.waiter().waitUntilTargetDeregistered(
                    DescribeTargetHealthRequest.builder()
                            .targetGroupArn(targetGroupArn)
                            .targets(TargetDescription.builder().id(instanceId).build())
                            .build(),
                    WaiterOverrideConfiguration.builder().waitTimeout(ESPERA_MAX_DRENADO).build());
        } catch (SdkException e) {
            nota = " (drenado no confirmado a tiempo; se terminó igual)";
        }

        try {
            ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
            return ResultadoAccion.exito(ACCION_RETIRAR, instanceId + nota);
        } catch (SdkException e) {
            return ResultadoAccion.fallo(ACCION_RETIRAR,
                    instanceId + " quedó fuera del balanceador pero no se pudo terminar: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        cloudWatch.close();
        ec2.close();
        elb.close();
    }

    // =====================================================================
    // auxiliares
    // =====================================================================

    private record Punto(double valor, Instant timestamp) {}

    private String consultarDimensionLoadBalancer() {
        TargetGroup tg = elb.describeTargetGroups(DescribeTargetGroupsRequest.builder()
                .targetGroupArns(targetGroupArn)
                .build()).targetGroups().get(0);

        if (tg.loadBalancerArns().isEmpty()) {
            throw new IllegalStateException(
                    "El target group no está asociado a ningún Load Balancer: " + targetGroupArn);
        }
        // "arn:aws:elasticloadbalancing:...:loadbalancer/app/nombre/id" -> "app/nombre/id"
        String lbArn = tg.loadBalancerArns().get(0);
        String marcador = "loadbalancer/";
        return lbArn.substring(lbArn.indexOf(marcador) + marcador.length());
    }

    /** Entre las instancias registradas, la más nueva que tenga la etiqueta del controller. */
    private String elegirInstanciaParaRetirar() {
        List<String> registradas = instanciasRegistradas();
        if (registradas.isEmpty()) {
            return null;
        }

        List<Reservation> reservas = ec2.describeInstances(DescribeInstancesRequest.builder()
                .instanceIds(registradas)
                .filters(
                        Filter.builder().name("tag:" + TAG_CLAVE).values(TAG_VALOR).build(),
                        Filter.builder().name("instance-state-name").values("running").build())
                .build()).reservations();

        Instance masNueva = null;
        for (Reservation reserva : reservas) {
            for (Instance instancia : reserva.instances()) {
                if (masNueva == null || instancia.launchTime().isAfter(masNueva.launchTime())) {
                    masNueva = instancia;
                }
            }
        }
        return masNueva == null ? null : masNueva.instanceId();
    }

    /** Si falla el lanzamiento a medias, se termina la instancia huérfana. */
    private String terminarPorLimpieza(String instanceId) {
        try {
            ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
            return " (la instancia se terminó para no dejarla huérfana)";
        } catch (SdkException e) {
            return " (OJO: no se pudo terminar la instancia huérfana: " + e.getMessage() + ")";
        }
    }

    /** RequestCountPerTarget / ProcessedBytes: sin datos pero con el ALB reportando = 0 tráfico. */
    private static void agregarConteo(List<Metrica> metricas, String nombre, Punto punto, Punto healthy) {
        if (punto != null) {
            metricas.add(new Metrica(nombre, punto.valor(), punto.timestamp()));
        } else if (healthy != null) {
            metricas.add(new Metrica(nombre, 0.0, healthy.timestamp()));
        }
    }

    /** El dato más reciente de una consulta, o null si no hay o si es demasiado viejo. */
    private static Punto ultimoPunto(MetricDataResult resultado, Instant limite) {
        if (resultado == null || resultado.values().isEmpty()) {
            return null;
        }
        Instant ts = resultado.timestamps().get(0);
        if (ts.isBefore(limite)) {
            return null; // llegó tarde: se trata como faltante (4.4)
        }
        return new Punto(resultado.values().get(0), ts);
    }

    private static Dimension dimension(String nombre, String valor) {
        return Dimension.builder().name(nombre).value(valor).build();
    }

    private static MetricDataQuery consulta(String id, String namespace, String metrica,
                                            String estadistica, Dimension... dimensiones) {
        return MetricDataQuery.builder()
                .id(id)
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                                .namespace(namespace)
                                .metricName(metrica)
                                .dimensions(dimensiones)
                                .build())
                        .period(PERIODO_SEGUNDOS)
                        .stat(estadistica)
                        .build())
                .returnData(true)
                .build();
    }
}