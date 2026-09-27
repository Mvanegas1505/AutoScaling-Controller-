package controller;

import model.Metrica;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;

public class State {

    private final int tamanoVentana;
    private final Map<String, List<Metrica>> ventanas;

    private int contadorConfirmacionIncrease;
    private int contadorConfirmacionReduce;

    private int cooldownLeft;

    public State(int tamanoVentana) {
        this.tamanoVentana = tamanoVentana;
        this.ventanas = new HashMap<>();
        this.contadorConfirmacionIncrease = 0;
        this.contadorConfirmacionReduce = 0;
        this.cooldownLeft = 0;
    }

    public void agregarLectura(Metrica metrica) {
        String nombre = metrica.getNombre();
        List<Metrica> lista = ventanas.getOrDefault(nombre, new LinkedList<>());
        lista.add(metrica);
        if (lista.size() > tamanoVentana) {
            lista.remove(0);
        }
        ventanas.put(nombre, lista);
    }

    public List<Metrica> getVentana(String nombreMetrica) {
        return ventanas.getOrDefault(nombreMetrica, new LinkedList<>());
    }

    public boolean enCooldown() {
        return cooldownLeft > 0;
    }

    public void activarCooldown(int ciclos) {
        this.cooldownLeft = ciclos;
    }

    public void avanzarCiclo() {
        if (cooldownLeft > 0) {
            cooldownLeft--;
        }
    }

    public void incrementarContadorConfirmacionIncrease() {
        contadorConfirmacionIncrease++;
        contadorConfirmacionReduce = 0;
    }

    public void incrementarContadorConfirmacionReduce() {
        contadorConfirmacionReduce++;
        contadorConfirmacionIncrease = 0; // una señal de reducción rompe la racha de aumento
    }

    public void resetearConfirmaciones() {
        contadorConfirmacionIncrease = 0;
        contadorConfirmacionReduce = 0;
    }

    public int getContadorConfirmacionIncrease() {
        return contadorConfirmacionIncrease;
    }

    public int getContadorConfirmacionReduce() {
        return contadorConfirmacionReduce;
    }
}