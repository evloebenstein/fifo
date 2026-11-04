"""Cliente MCP SSE para el puente Nexus-Blender-Bridge en http://localhost:3000.

Implementa el protocolo Model Context Protocol (MCP) sobre Server-Sent Events (SSE)
y JSON-RPC 2.0 para interactuar con la instancia activa de Blender.
"""

import json
import queue
import sys
import threading
import time
import urllib.error
import urllib.request


class BlenderMcpClient:
    """Cliente para comunicarse con Blender a través del puente MCP SSE."""

    def __init__(self, base_url="http://localhost:3000", timeout=60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._queue = queue.Queue()
        self._session_id = None
        self._sse_thread = None
        self._running = False
        self._msg_id = 0

    def connect(self, timeout=15):
        """Conecta al stream SSE y extrae el sessionId asignado por el servidor."""
        self._running = True

        def _sse_listener():
            try:
                req = urllib.request.Request(
                    f"{self.base_url}/sse",
                    headers={"Accept": "text/event-stream"}
                )
                with urllib.request.urlopen(req, timeout=300) as resp:
                    for line in resp:
                        if not self._running:
                            break
                        line = line.decode("utf-8", "replace").rstrip("\r\n")
                        if line.startswith("data:"):
                            payload = line[5:].strip()
                            self._queue.put(payload)
            except Exception as e:
                self._queue.put({"_error": str(e)})

        self._sse_thread = threading.Thread(target=_sse_listener, daemon=True)
        self._sse_thread.start()

        start = time.time()
        while time.time() - start < timeout:
            try:
                item = self._queue.get(timeout=2)
                if isinstance(item, dict) and "_error" in item:
                    raise RuntimeError(f"Error conectando a SSE: {item['_error']}")
                if "sessionId=" in item:
                    self._session_id = item.split("sessionId=")[-1].strip()
                    break
                elif item.startswith("/message?sessionId="):
                    self._session_id = item.split("sessionId=")[-1].strip()
                    break
            except queue.Empty:
                continue

        if not self._session_id:
            raise TimeoutError(f"No se recibio sessionId de {self.base_url}/sse")

        # Handshake inicial MCP
        self._send_rpc("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "fifo-blender-client", "version": "1.0.0"}
        })
        return self._session_id

    def _next_id(self):
        self._msg_id += 1
        return self._msg_id

    def _send_rpc(self, method, params=None, timeout=None):
        """Envía un mensaje JSON-RPC al endpoint /message?sessionId=..."""
        if not self._session_id:
            self.connect()

        msg_id = self._next_id()
        payload = {
            "jsonrpc": "2.0",
            "id": msg_id,
            "method": method,
            "params": params or {}
        }
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/message?sessionId={self._session_id}",
            data=body,
            headers={"Content-Type": "application/json"}
        )

        urllib.request.urlopen(req, timeout=timeout or self.timeout)

        # Esperar respuesta correspondiente
        start = time.time()
        max_wait = timeout or self.timeout
        while time.time() - start < max_wait:
            raw = self._queue.get(timeout=max_wait)
            if isinstance(raw, dict) and "_error" in raw:
                raise RuntimeError(f"Error SSE: {raw['_error']}")
            try:
                data = json.loads(raw)
                if data.get("id") == msg_id or "result" in data or "error" in data:
                    return data
            except json.JSONDecodeError:
                continue
        raise TimeoutError(f"Timeout esperando respuesta para {method}")

    def list_tools(self):
        """Lista las herramientas disponibles en el servidor MCP."""
        res = self._send_rpc("tools/list", {})
        return res.get("result", {}).get("tools", [])

    def call_tool(self, name, arguments=None, timeout=None):
        """Invoca una herramienta mediante tools/call según especificación MCP."""
        res = self._send_rpc("tools/call", {"name": name, "arguments": arguments or {}}, timeout=timeout)
        if "error" in res:
            raise RuntimeError(f"Error MCP en {name}: {res['error']}")
        return res.get("result", {})

    def execute_python(self, code, timeout=120):
        """Ejecuta código Python arbitrario dentro del proceso de Blender."""
        res = self.call_tool("execute_python", {"code": code}, timeout=timeout)
        return res

    def get_scene_inventory(self):
        """Obtiene el inventario de objetos, materiales, cámaras y luces."""
        res = self.call_tool("get_scene_inventory")
        content = res.get("content", [])
        if content and isinstance(content[0], dict) and "text" in content[0]:
            try:
                return json.loads(content[0]["text"])
            except Exception:
                return content[0]["text"]
        return res

    def clear_scene(self):
        """Elimina todos los objetos de la escena activa."""
        return self.call_tool("clear_scene")

    def setup_cinematic_scene(self, style="three_point"):
        """Configura iluminación de estudio y cámara."""
        return self.call_tool("setup_cinematic_scene", {"style": style})

    def render_still(self):
        """Ejecuta un render en la vista activa."""
        return self.call_tool("render_still")

    def close(self):
        """Cierra el listener SSE."""
        self._running = False


# Instancia por defecto para uso directo
_default_client = None


def get_client(base_url="http://localhost:3000"):
    global _default_client
    if _default_client is None:
        _default_client = BlenderMcpClient(base_url=base_url)
        _default_client.connect()
    return _default_client


def execute(code, timeout=120):
    return get_client().execute_python(code, timeout=timeout)


if __name__ == "__main__":
    print("Conectando al servidor MCP en http://localhost:3000 ...")
    client = BlenderMcpClient()
    sid = client.connect()
    print(f"Sesion establecida: {sid}")

    tools = client.list_tools()
    print(f"Herramientas encontradas ({len(tools)}):")
    for t in tools:
        print(f"  - {t['name']}: {t.get('description', '')[:60]}...")

    inv = client.get_scene_inventory()
    print("Inventario de escena actual:", inv)
