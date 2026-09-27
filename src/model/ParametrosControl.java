package model;

/**
 * Parámetros de la política de control (sección 5). Se definen en un solo lugar
 * y se le pasan a Logic por constructor, para no dejar números quemados en el código.
 */
public record ParametrosControl(
        double k,                        // multiplicador de la desviación estándar de la banda (5.2)
        int horizonteMinutos,            // H de la proyección de tendencia (5.3)
        int confirmacionesIncrease,      // lecturas seguidas para confirmar un aumento (5.6)
        int confirmacionesReduce,        // lecturas seguidas para confirmar una reducción (5.6)
        int healthyHostCountMinimo,      // HealthyHostCount debe superarlo para poder reducir (5.7)
        double margenMinimoCpu,          // piso del margen de la banda de CPU, en puntos de % (5.2)
        double margenMinimoRequest,      // piso del margen de la banda de requests (5.2)
        int lecturasMinimas,             // lecturas necesarias antes de empezar a decidir (arranque)
        double techoCpu,                 // CPU por encima de esto siempre es señal de aumento (%)
        double pisoCpu,                  // CPU por debajo de esto cuenta como baja (%)
        double techoRequest,             // RequestCountPerTarget por encima de esto siempre es señal de aumento
        double pisoRequest) {            // RequestCountPerTarget por debajo de esto cuenta como baja

    public ParametrosControl {
        // con menos de 3 lecturas no hay al menos 2 anteriores para la banda ni puntos para la regresión
        if (lecturasMinimas < 3) {
            throw new IllegalArgumentException("lecturasMinimas debe ser al menos 3");
        }
        if (pisoCpu >= techoCpu || pisoRequest >= techoRequest) {
            throw new IllegalArgumentException("cada piso absoluto debe ser menor que su techo");
        }
    }

    /**
     * Valores iniciales. Los de CPU son razonables para cualquier app; los de requests
     * dependen de cuántas peticiones por minuto aguanta UNA instancia de tu app, así que
     * hay que calibrarlos con una prueba de carga antes del experimento.
     */
    public static ParametrosControl porDefecto() {
        return new ParametrosControl(
                2.0,    // k
                5,      // horizonteMinutos
                2,      // confirmacionesIncrease
                4,      // confirmacionesReduce
                1,      // healthyHostCountMinimo
                5.0,    // margenMinimoCpu
                10.0,   // margenMinimoRequest
                5,      // lecturasMinimas
                75.0,   // techoCpu
                30.0,   // pisoCpu
                600.0,  // techoRequest  (CALIBRAR)
                60.0);  // pisoRequest   (CALIBRAR)
    }
}