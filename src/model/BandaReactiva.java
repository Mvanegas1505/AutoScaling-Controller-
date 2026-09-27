package model;

/**
 * Banda dinámica de una métrica, calculada con las lecturas ANTERIORES a la actual.
 *
 * promedio            -> centro de la banda (target dinámico)
 * desviacionEstandar  -> dispersión de las lecturas anteriores
 * margen              -> max(k * desviacionEstandar, margenMinimo)
 * limiteInferior/Superior -> promedio -/+ margen
 */
public record BandaReactiva(double promedio,
                            double desviacionEstandar,
                            double margen,
                            double limiteInferior,
                            double limiteSuperior) {

    /** true si el margen salió del piso mínimo y no de k * desviación. */
    public boolean usoPisoMinimo(double k) {
        return margen > k * desviacionEstandar;
    }
}