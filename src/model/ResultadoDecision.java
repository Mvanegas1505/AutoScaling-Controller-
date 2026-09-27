package model;

/**
 * Resultado de un ciclo de decisión: la decisión y todo lo necesario para
 * explicarla (sección 11 del documento). Es lo que recibirá el CicloLogger.
 *
 * Si no hubo datos suficientes para calcular (arranque), las bandas son null
 * y los valores numéricos son NaN.
 */
public record ResultadoDecision(
        Decision decision,
        String razon,
        BandaReactiva bandaCpu,
        BandaReactiva bandaRequest,
        double valorActualCpu,
        double valorActualRequest,
        double pendienteCpu,
        double valorProyectadoCpu,
        boolean breachProximo,
        double healthyHostCount,
        int contadorIncrease,
        int contadorReduce) {

    /** Resultado cuando no se pudo calcular nada (ventana insuficiente). */
    public static ResultadoDecision sinCalculo(Decision decision, String razon,
                                               int contadorIncrease, int contadorReduce) {
        return new ResultadoDecision(decision, razon, null, null,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                false, Double.NaN, contadorIncrease, contadorReduce);
    }
}