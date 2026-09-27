"""
App de prueba para el Auto-Scaling Controller.

Corre en cada instancia EC2 detrás del Application Load Balancer.
Solo usa la librería estándar de Python: no hay que instalar nada con pip.

Endpoints:
  GET /health         -> "OK". Lo usa el ALB para saber si la instancia está viva.
  GET /consulta?id=7  -> consulta liviana: un producto de un catálogo en memoria.
                         Es el tráfico "normal" de la app; casi no gasta CPU.
  GET /work?n=50000   -> cuenta los primos hasta n. Consume CPU de verdad, para que
                         la prueba de carga suba CPUUtilization sin mandar mucho tráfico.
  GET /               -> info básica (qué instancia respondió), útil para ver el balanceo.
"""

import json
import os
import socket
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ForkingMixIn
from urllib.parse import parse_qs, urlparse

PUERTO = int(os.environ.get("APP_PORT", "8080"))
N_POR_DEFECTO = 50_000
N_MAXIMO = 500_000  # tope para que nadie tumbe la instancia con un n gigante
HOSTNAME = socket.gethostname()

# Catálogo en memoria (se genera igual en todas las instancias: mismos datos en todas)
CATEGORIAS = ["hogar", "tecnologia", "deportes", "libros", "ropa"]
CATALOGO = {
    i: {"id": i,
        "nombre": f"Producto {i}",
        "categoria": CATEGORIAS[i % len(CATEGORIAS)],
        "precio": round(10 + (i * 7919) % 990 + (i % 100) / 100, 2),
        "stock": (i * 31) % 200}
    for i in range(1, 1001)
}


def contar_primos(n):
    """Trabajo de CPU a propósito (división por tentativa)."""
    total = 0
    for candidato in range(2, n + 1):
        es_primo = True
        divisor = 2
        while divisor * divisor <= candidato:
            if candidato % divisor == 0:
                es_primo = False
                break
            divisor += 1
        if es_primo:
            total += 1
    return total


class Manejador(BaseHTTPRequestHandler):

    def do_GET(self):
        url = urlparse(self.path)

        if url.path == "/health":
            self._responder(200, "text/plain", b"OK")

        elif url.path == "/consulta":
            try:
                producto_id = int(parse_qs(url.query).get("id", ["1"])[0])
            except ValueError:
                self._responder(400, "text/plain", b"id debe ser un entero")
                return
            producto = CATALOGO.get(producto_id)
            if producto is None:
                self._json(404, {"instancia": HOSTNAME, "error": "producto no existe", "id": producto_id})
            else:
                self._json(200, {"instancia": HOSTNAME, "producto": producto})

        elif url.path == "/work":
            try:
                n = int(parse_qs(url.query).get("n", [N_POR_DEFECTO])[0])
            except ValueError:
                self._responder(400, "text/plain", b"n debe ser un entero")
                return
            n = max(2, min(n, N_MAXIMO))

            inicio = time.perf_counter()
            primos = contar_primos(n)
            duracion_ms = (time.perf_counter() - inicio) * 1000

            self._json(200, {"instancia": HOSTNAME, "n": n, "primos": primos,
                             "duracion_ms": round(duracion_ms, 1)})

        elif url.path == "/":
            self._json(200, {"instancia": HOSTNAME, "endpoints": ["/health", "/consulta?id=7", "/work?n=50000"]})

        else:
            self._responder(404, "text/plain", b"no encontrado")

    def _json(self, codigo, datos):
        self._responder(codigo, "application/json", json.dumps(datos).encode())

    def _responder(self, codigo, tipo, cuerpo):
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        self.wfile.write(cuerpo)

    def log_message(self, formato, *args):
        pass  # sin log por petición: con carga alta llenaría el disco


class ServidorMultiproceso(ForkingMixIn, HTTPServer):
    """
    Un proceso por petición (no hilos). En Python los hilos no pueden usar varios
    núcleos a la vez para cálculo (por el GIL): con hilos, una t3.micro de 2 vCPU
    nunca pasaría de ~50% de CPU y el techo de 75% del controller jamás se alcanzaría.
    """
    allow_reuse_address = True
    max_children = 64


if __name__ == "__main__":
    print(f"App escuchando en el puerto {PUERTO} ({HOSTNAME})", flush=True)
    ServidorMultiproceso(("0.0.0.0", PUERTO), Manejador).serve_forever()