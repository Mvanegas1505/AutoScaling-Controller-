package model;

import java.time.Instant;

public class Metrica {

    private final String nombre; // EJ: 'CPUUtilization'
    private final double valor; // 70 %
    private final Instant timestamp; // cuando se tomo la lectura

    public Metrica(String nombre, double valor, Instant timestamp) {
        this.nombre = nombre;
        this.valor = valor;
        this.timestamp = timestamp;
    }

    public String getNombre() {
        return nombre;
    }

    public double getValor() {
        return valor;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

}