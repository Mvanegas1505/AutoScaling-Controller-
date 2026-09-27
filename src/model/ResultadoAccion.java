package model;

/**
 * Lo que realmente pasó en AWS al ejecutar (o no) la decisión de un ciclo (sección 6).
 * Lo produce el Actuator y lo guarda el CicloLogger, incluso si la acción falló (6.3).
 *
 * accion    -> qué se le pidió a AWS, ej: "LANZAR_Y_REGISTRAR", "DESREGISTRAR_Y_TERMINAR", "NINGUNA"
 * resultado -> EXITO, FALLO o NO_APLICA (cuando la decisión fue mantener)
 * detalle   -> id de la instancia afectada, o el mensaje de error si falló
 */
public record ResultadoAccion(String accion, Resultado resultado, String detalle) {

    public enum Resultado {
        EXITO,
        FALLO,
        NO_APLICA
    }

    /** No se ejecutó ninguna acción (decisión MAINTAIN). */
    public static ResultadoAccion ninguna() {
        return new ResultadoAccion("NINGUNA", Resultado.NO_APLICA, "");
    }

    public static ResultadoAccion exito(String accion, String detalle) {
        return new ResultadoAccion(accion, Resultado.EXITO, detalle);
    }

    public static ResultadoAccion fallo(String accion, String detalle) {
        return new ResultadoAccion(accion, Resultado.FALLO, detalle);
    }

    public boolean fueExitosa() {
        return resultado == Resultado.EXITO;
    }
}