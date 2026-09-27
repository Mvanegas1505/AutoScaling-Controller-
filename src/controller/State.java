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

    /**
     * V2: vacía las ventanas de todas las métricas. Se llama después de cada acción de escalado,
     * porque las métricas son POR INSTANCIA: al agregar o quitar una instancia cambian de nivel
     * aunque la demanda no cambie, y mezclar lecturas de antes y después de la acción hace que
     * la regresión vea una "tendencia" que causó el propio controller (experimento 1, 07:40).
     */
    public void limpiarVentanas() {
        ventanas.clear();
    }

    public List<Metrica> getVentana(String nombreMetrica) {
        return ventanas.getOrDefault(nombreMetrica, new LinkedList<>());
    }

    public boolean enCooldown() {
        return cooldownLeft > 0;
    }

    public int getCooldownRestante() {
        return cooldownLeft;
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
