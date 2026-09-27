"""
Generador de carga para el experimento del Auto-Scaling Controller.

Manda peticiones al Load Balancer de forma ALEATORIA, como usuarios reales:
  - Llegadas de Poisson: el tiempo entre peticiones es aleatorio (exponencial);
    en promedio se respeta la velocidad pedida (peticiones por segundo, rps).
  - Mezcla de peticiones: /consulta (liviana) y /work (pesada, con n aleatorio).

Solo usa la librería estándar de Python. Se corre desde tu computador:

  1) CALIBRAR (una instancia, controller APAGADO): mantiene cada velocidad unos minutos
     python loadgen.py calibrar --url http://<DNS-del-ALB> --rps 1,2,4,6 --minutos 5

  2) ESCENARIOS del experimento (controller PRENDIDO):
     python loadgen.py escenario pico      --url http://<DNS> --base 2 --alto 8
     python loadgen.py escenario sostenido --url http://<DNS> --base 2 --alto 8
     python loadgen.py escenario rampa     --url http://<DNS> --base 2 --alto 8
     python loadgen.py escenario completo  --url http://<DNS> --base 2 --alto 8

Cada petición queda registrada en un CSV (hora UTC, fase, endpoint, estado, latencia,
instancia que respondió) para cruzarlo después con ciclos.csv del controller.
Ctrl+C detiene y muestra el resumen de lo que alcanzó a correr.
"""

import argparse
import csv
import json
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

# ---------------- límites de seguridad ----------------
RPS_MAXIMO = 30          # nunca más de 30 peticiones/s: suficiente para saturar 5 instancias con /work
MAX_EN_VUELO = 300       # si ya hay 300 peticiones esperando respuesta, no se mandan más (con la app saturada
                         # las respuestas tardan ~10 s: con menos, el generador dejaría de mandar la carga pedida)
TIMEOUT_S = 30

# ---------------- mezcla de peticiones ----------------
PROB_CONSULTA = 0.3      # 30% consultas livianas, 70% trabajo pesado
N_MIN, N_MAX = 40_000, 60_000   # /work con n aleatorio en este rango (costo promedio estable)
IDS_CATALOGO = 1000


class Registro:
    """Guarda cada petición en el CSV y lleva las estadísticas de cada fase (thread-safe)."""

    def __init__(self, ruta):
        self.lock = threading.Lock()
        self.archivo = open(ruta, "w", newline="", encoding="utf-8")
        self.csv = csv.writer(self.archivo, delimiter=";")
        self.csv.writerow(["timestamp_utc", "fase", "rps_objetivo", "endpoint",
                           "estado", "latencia_ms", "instancia"])
        self.fases = {}          # nombre -> dict con datos
        self.orden = []
        self.en_vuelo = 0

    def iniciar_fase(self, nombre, rps):
        with self.lock:
            self.fases[nombre] = {"rps": rps, "inicio": ahora_utc(), "fin": None,
                                  "latencias": [], "ok": 0, "errores": 0,
                                  "descartadas": 0, "instancias": set()}
            self.orden.append(nombre)

    def terminar_fase(self, nombre):
        with self.lock:
            self.fases[nombre]["fin"] = ahora_utc()

    def anotar(self, fase, rps, endpoint, estado, latencia_ms, instancia):
        with self.lock:
            self.csv.writerow([ahora_utc().strftime("%Y-%m-%d %H:%M:%S"), fase, rps, endpoint,
                               estado, f"{latencia_ms:.1f}".replace(".", ","), instancia])
            f = self.fases[fase]
            if isinstance(estado, int) and 200 <= estado < 300:
                f["ok"] += 1
                f["latencias"].append(latencia_ms)
                if instancia:
                    f["instancias"].add(instancia)
            else:
                f["errores"] += 1

    def descartar(self, fase):
        with self.lock:
            self.fases[fase]["descartadas"] += 1

    def cerrar(self):
        self.archivo.close()


def ahora_utc():
    return datetime.now(timezone.utc)


def hacer_peticion(url_base, fase, rps, registro):
    if random.random() < PROB_CONSULTA:
        endpoint = f"/consulta?id={random.randint(1, IDS_CATALOGO)}"
    else:
        endpoint = f"/work?n={random.randint(N_MIN, N_MAX)}"

    inicio = time.perf_counter()
    estado, instancia = None, ""
    try:
        with urllib.request.urlopen(url_base + endpoint, timeout=TIMEOUT_S) as r:
            cuerpo = r.read()
            estado = r.status
            try:
                instancia = json.loads(cuerpo).get("instancia", "")
            except ValueError:
                pass
    except urllib.error.HTTPError as e:
        estado = e.code                      # ej. 502/503 del ALB si no hay instancias sanas
    except Exception as e:                   # timeout, conexión rechazada, etc.
        estado = type(e).__name__
    finally:
        latencia_ms = (time.perf_counter() - inicio) * 1000
        registro.anotar(fase, rps, endpoint.split("?")[0], estado, latencia_ms, instancia)
        with registro.lock:
            registro.en_vuelo -= 1


def correr_fase(url_base, nombre, rps, segundos, pool, registro):
    """Mantiene 'rps' peticiones/s en promedio durante 'segundos' (llegadas de Poisson)."""
    registro.iniciar_fase(nombre, rps)
    print(f"  [{ahora_utc():%H:%M:%S} UTC] fase {nombre}: {rps} rps durante {segundos / 60:.1f} min", flush=True)
    fin = time.monotonic() + segundos
    if rps <= 0:
        time.sleep(segundos)
    else:
        siguiente = time.monotonic()
        while True:
            siguiente += random.expovariate(rps)   # espera aleatoria hasta la próxima llegada
            if siguiente >= fin:
                break
            espera = siguiente - time.monotonic()
            if espera > 0:
                time.sleep(espera)
            with registro.lock:
                lleno = registro.en_vuelo >= MAX_EN_VUELO
                if not lleno:
                    registro.en_vuelo += 1
            if lleno:
                registro.descartar(nombre)
                continue
            pool.submit(hacer_peticion, url_base, nombre, rps, registro)
        restante = fin - time.monotonic()
        if restante > 0:
            time.sleep(restante)
    registro.terminar_fase(nombre)


def resumen(registro, calibrando):
    print("\n" + "=" * 100)
    print(f"{'fase':<14}{'inicio UTC':<11}{'fin UTC':<10}{'rps obj':>8}{'rps real':>9}{'ok':>7}"
          f"{'errores':>8}{'descart.':>9}{'lat prom':>10}{'lat p95':>9}{'instancias':>11}")
    print("-" * 100)
    for nombre in registro.orden:
        f = registro.fases[nombre]
        fin = f["fin"] or ahora_utc()
        duracion = max((fin - f["inicio"]).total_seconds(), 1)
        total = f["ok"] + f["errores"]
        lat = f["latencias"]
        prom = f"{statistics.mean(lat):.0f} ms" if lat else "-"
        p95 = f"{sorted(lat)[int(len(lat) * 0.95) - 1]:.0f} ms" if len(lat) >= 20 else "-"
        print(f"{nombre:<14}{f['inicio']:%H:%M:%S}   {fin:%H:%M:%S}  {f['rps']:>8}{total / duracion:>9.2f}"
              f"{f['ok']:>7}{f['errores']:>8}{f['descartadas']:>9}{prom:>10}{p95:>9}{len(f['instancias']):>11}")
    print("=" * 100)
    if calibrando:
        print("Para calibrar: en CloudWatch mira CPUUtilization (de la instancia) y RequestCountPerTarget")
        print("en el rango de horas de cada fase (ignora el primer minuto de cada una) y arma la tabla")
        print("rps -> requests/min -> CPU. pisoRequest = requests/min con CPU ~30%; techoRequest = con CPU ~75%.")


def escenario(nombre, base, alto):
    """Fases (nombre, rps, minutos). 'base' = carga normal; 'alto' = carga que satura una instancia."""
    medio = round((base + alto) / 2, 2)
    pico = [("base", base, 8), ("pico_1min", alto * 1.5, 1), ("post_pico", base, 10)]
    sostenido = [("base", base, 8), ("alto", alto, 20), ("bajada", base, 20)]
    rampa = ([("base", base, 8)]
             + [(f"rampa_{i}", round(base + (alto - base) * i / 5, 2), 3) for i in range(1, 6)]
             + [("meseta", alto, 10), ("bajada", base, 20)])
    completo = ([("base", base, 8), ("pico_1min", alto * 1.5, 1), ("post_pico", base, 8),
                 ("medio", medio, 8), ("alto", alto, 15), ("bajada", base, 20)])
    return {"pico": pico, "sostenido": sostenido, "rampa": rampa, "completo": completo}[nombre]


def main():
    p = argparse.ArgumentParser(description="Generador de carga aleatoria para el Auto-Scaling Controller")
    sub = p.add_subparsers(dest="modo", required=True)

    c = sub.add_parser("calibrar", help="mantiene cada velocidad unos minutos (una instancia, controller apagado)")
    c.add_argument("--url", required=True, help="http://<DNS-del-ALB>")
    c.add_argument("--rps", default="1,2,4,6", help="velocidades separadas por coma (default 1,2,4,6)")
    c.add_argument("--minutos", type=float, default=5, help="minutos por velocidad (default 5)")

    e = sub.add_parser("escenario", help="corre un escenario del experimento (controller prendido)")
    e.add_argument("nombre", choices=["pico", "sostenido", "rampa", "completo"])
    e.add_argument("--url", required=True, help="http://<DNS-del-ALB>")
    e.add_argument("--base", type=float, required=True, help="rps de carga normal")
    e.add_argument("--alto", type=float, required=True, help="rps de carga alta")

    for s in (c, e):
        s.add_argument("--salida", default=None, help="CSV de salida (default loadgen_<modo>_<hora>.csv)")
        s.add_argument("--acelerar", type=float, default=1.0, help=argparse.SUPPRESS)  # solo para pruebas

    args = p.parse_args()
    url = args.url.rstrip("/")

    if args.modo == "calibrar":
        fases = [(f"calib_{r}rps", float(r), args.minutos) for r in args.rps.split(",")]
    else:
        fases = escenario(args.nombre, args.base, args.alto)

    rps_max = max(f[1] for f in fases)
    if rps_max > RPS_MAXIMO:
        sys.exit(f"Por seguridad el máximo es {RPS_MAXIMO} rps (pediste {rps_max}). "
                 f"Tu app satura una instancia con muchas menos.")

    salida = args.salida or f"loadgen_{args.modo}_{ahora_utc():%Y%m%d_%H%M%S}.csv"
    total_min = sum(f[2] for f in fases)
    print(f"Destino: {url} | {len(fases)} fases | ~{total_min:.0f} min | registro: {salida}")
    print(f"Mezcla: {PROB_CONSULTA:.0%} /consulta, {1 - PROB_CONSULTA:.0%} /work (n entre {N_MIN} y {N_MAX})")

    registro = Registro(salida)
    try:
        with ThreadPoolExecutor(max_workers=MAX_EN_VUELO) as pool:
            for nombre, rps, minutos in fases:
                correr_fase(url, nombre, rps, minutos * 60 / args.acelerar, pool, registro)
    except KeyboardInterrupt:
        print("\nDetenido con Ctrl+C.")
    finally:
        registro.cerrar()
        resumen(registro, args.modo == "calibrar")
        print(f"Detalle de cada petición en: {salida}")


if __name__ == "__main__":
    main()