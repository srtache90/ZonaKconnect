import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


TARGET_URL = os.getenv("SIMPHONY_TARGET_URL", "http://portal-java:8081/api/v1/ingest/pos")
API_KEY = os.getenv("SIMPHONY_API_KEY", "local-sap-simphony-api-key")
EMISSION_POINT_ID = os.getenv("SIMPHONY_EMISSION_POINT_ID", "")
SERVER_PORT = int(os.getenv("SIMPHONY_MOCK_PORT", "8080"))
MAX_BATCH_SIZE = int(os.getenv("SIMPHONY_MAX_BATCH_SIZE", "200"))
MAX_HISTORY_SIZE = int(os.getenv("SIMPHONY_MAX_HISTORY_SIZE", "80"))
REQUEST_TIMEOUT = int(os.getenv("SIMPHONY_REQUEST_TIMEOUT", "90"))

ROOT = Path(__file__).resolve().parent
UI_PATH = ROOT / "ui.html"
SAMPLES_DIR = ROOT / "samples"
SEND_HISTORY = deque(maxlen=MAX_HISTORY_SIZE)


def load_sample(name="ticket-pravda-p660502.json"):
    path = SAMPLES_DIR / name
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def list_samples():
    if not SAMPLES_DIR.is_dir():
        return []
    return sorted(p.name for p in SAMPLES_DIR.glob("*.json"))


def summarize_ticket(payload):
    if not isinstance(payload, dict):
        return {"valid": False, "error": "El JSON debe ser un objeto"}

    numero = payload.get("numero_factura")
    items = payload.get("items") if isinstance(payload.get("items"), list) else []
    warnings = []

    if numero is not None and not isinstance(numero, int):
        warnings.append(
            "numero_factura llega como string. PosTicketRequest lo declara Integer; "
            "si el portal responde 400, extrae solo el consecutivo numerico o usa Prefijo."
        )
    if not items:
        warnings.append("items vacio: el ingest POS exige al menos una linea.")
    pagos = payload.get("pagos")
    if isinstance(pagos, list) and pagos:
        first = pagos[0] if isinstance(pagos[0], dict) else {}
        if "tenderMediaId" in first and "forma_pago" not in first:
            warnings.append(
                "pagos usa esquema Harmony (tenderMediaId/tenderAmount). "
                "El DTO POS espera forma_pago/medio_pago/valor; Jackson ignora el resto."
            )
    if payload.get("cliente") is None and payload.get("identificacion_cliente"):
        warnings.append(
            "cliente es null; el mapper POS usa consumidor final 222222222222. "
            "identificacion_cliente no se mapea en PosTicketRequest."
        )

    return {
        "valid": True,
        "numeroFactura": numero,
        "numeroTicket": payload.get("numero_ticket"),
        "checkId": payload.get("check_id"),
        "cajaWsid": payload.get("caja_wsid"),
        "resolucion": payload.get("Resolucion") or payload.get("resolucion"),
        "restaurante": payload.get("restaurante"),
        "total": payload.get("total"),
        "items": len(items),
        "warnings": warnings,
        "suggestedEndpoint": "/api/v1/ingest/pos",
    }


def parse_response_body(body):
    trimmed = (body or "").strip()
    if not trimmed:
        return {}
    try:
        data = json.loads(trimmed)
        return data if isinstance(data, dict) else {"body": data}
    except json.JSONDecodeError:
        return {"body": trimmed}


def diagnose_status(http_status, response):
    error_text = ""
    if isinstance(response, dict):
        error_text = str(response.get("error") or response.get("message") or "")

    if http_status == 401:
        if "API Key requerida" in error_text:
            return "Falta X-API-Key. El filtro Spring Security de /api/v1/ingest/** exige ROLE_API_INGEST."
        return (
            "401: X-API-Key invalida o el PV de X-Emission-Point-ID no pertenece a la sociedad. "
            "Local: local-sap-simphony-api-key"
        )
    if http_status == 400:
        return error_text or "400: JSON aceptado por auth, rechazado por mapper/ruteo de PV (Resolucion/caja_wsid)."
    if http_status == 202:
        return "Aceptado. El portal persistio y disparo orquestacion DIAN."
    if http_status == 502:
        return error_text or "502: fallo al emitir hacia Core Go / DIAN_NET."
    if http_status >= 500:
        return error_text or "Error interno del portal."
    return error_text or ""


def send_ticket(payload, api_key, emission_point_id, target_url):
    body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if api_key:
        headers["X-API-Key"] = api_key
    if emission_point_id:
        headers["X-Emission-Point-ID"] = emission_point_id

    request = urllib.request.Request(target_url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
            raw = response.read().decode("utf-8")
            parsed = parse_response_body(raw)
            return response.status, parsed, dict(headers)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        parsed = parse_response_body(raw)
        if not parsed:
            parsed = {"error": raw}
        return error.code, parsed, dict(headers)
    except urllib.error.URLError as error:
        return 503, {
            "error": "No fue posible conectar con el portal",
            "detail": str(error.reason),
            "targetUrl": target_url,
        }, dict(headers)


def is_accepted(http_status):
    return http_status in (200, 202)


def record_history(filename, payload, headers, http_status, response, target_url, summary):
    entry = {
        "id": str(uuid.uuid4()),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "filename": filename,
        "numeroFactura": summary.get("numeroFactura"),
        "checkId": summary.get("checkId"),
        "cajaWsid": summary.get("cajaWsid"),
        "restaurante": summary.get("restaurante"),
        "total": summary.get("total"),
        "httpStatus": http_status,
        "accepted": is_accepted(http_status),
        "diagnosis": diagnose_status(http_status, response),
        "targetUrl": target_url,
        "requestHeaders": {
            "Content-Type": headers.get("Content-Type"),
            "X-API-Key": mask_secret(headers.get("X-API-Key")),
            "X-Emission-Point-ID": headers.get("X-Emission-Point-ID") or "",
        },
        "payload": payload,
        "response": response,
    }
    SEND_HISTORY.appendleft(entry)
    return entry


def mask_secret(value):
    if not value:
        return "(vacio)"
    if len(value) <= 8:
        return "***"
    return value[:4] + "..." + value[-4:]


def process_send(request_payload):
    target_url = (request_payload.get("targetUrl") or TARGET_URL).strip()
    api_key = request_payload.get("apiKey")
    if api_key is None:
        api_key = API_KEY
    api_key = str(api_key).strip()
    emission_point_id = str(
        request_payload.get("emissionPointId") or EMISSION_POINT_ID or ""
    ).strip()
    delay_ms = max(0, int(request_payload.get("delayMs") or 0))

    tickets = request_payload.get("tickets")
    if not isinstance(tickets, list) or not tickets:
        single = request_payload.get("payload")
        if single is None:
            return 400, {"error": "Envie payload o tickets[] con JSON Simphony/POS"}
        tickets = [{"name": "payload.json", "json": single}]

    if len(tickets) > MAX_BATCH_SIZE:
        return 400, {"error": f"Maximo {MAX_BATCH_SIZE} archivos por lote"}

    results = []
    for index, ticket in enumerate(tickets):
        name = (ticket or {}).get("name") or f"ticket-{index + 1}.json"
        payload = (ticket or {}).get("json")
        if not isinstance(payload, dict):
            summary = {"valid": False, "error": "JSON invalido"}
            results.append({
                "filename": name,
                "httpStatus": 400,
                "accepted": False,
                "summary": summary,
                "diagnosis": "El archivo no contiene un objeto JSON",
                "response": {"error": "JSON invalido"},
            })
            continue

        summary = summarize_ticket(payload)
        status, response, headers = send_ticket(payload, api_key, emission_point_id, target_url)
        entry = record_history(name, payload, headers, status, response, target_url, summary)
        results.append({
            "id": entry["id"],
            "filename": name,
            "httpStatus": status,
            "accepted": entry["accepted"],
            "summary": summary,
            "diagnosis": entry["diagnosis"],
            "requestHeaders": entry["requestHeaders"],
            "response": response,
        })
        if delay_ms and index < len(tickets) - 1:
            time.sleep(delay_ms / 1000)

    accepted = sum(1 for item in results if item["accepted"])
    return 200, {
        "targetUrl": target_url,
        "requested": len(results),
        "accepted": accepted,
        "failed": len(results) - accepted,
        "results": results,
    }


class SimphonyMockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path in ("/", "/ui"):
            self.serve_ui()
            return

        if path == "/health":
            portal_status = "unreachable"
            try:
                probe = TARGET_URL.rsplit("/api/", 1)[0]
                with urllib.request.urlopen(probe, timeout=3) as response:
                    portal_status = "ok" if response.status < 500 else "error"
            except Exception:
                portal_status = "unreachable"
            self.respond(200, {
                "status": "ok",
                "targetUrl": TARGET_URL,
                "portalStatus": portal_status,
                "apiKeyConfigured": bool(API_KEY),
            })
            return

        if path == "/api/config":
            self.respond(200, {
                "targetUrl": TARGET_URL,
                "apiKey": API_KEY,
                "emissionPointId": EMISSION_POINT_ID,
                "requestTimeoutSeconds": REQUEST_TIMEOUT,
                "maxBatchSize": MAX_BATCH_SIZE,
                "samples": list_samples(),
            })
            return

        if path == "/api/sample":
            name = (query.get("name") or ["ticket-pravda-p660502.json"])[0]
            payload = load_sample(name)
            if not payload:
                self.respond(404, {"error": "Muestra no encontrada"})
                return
            self.respond(200, {
                "name": name,
                "payload": payload,
                "summary": summarize_ticket(payload),
            })
            return

        if path == "/api/history":
            self.respond(200, [
                {
                    "id": item["id"],
                    "timestamp": item["timestamp"],
                    "filename": item["filename"],
                    "numeroFactura": item.get("numeroFactura"),
                    "checkId": item.get("checkId"),
                    "httpStatus": item["httpStatus"],
                    "accepted": item["accepted"],
                    "diagnosis": item.get("diagnosis"),
                    "restaurante": item.get("restaurante"),
                    "total": item.get("total"),
                }
                for item in SEND_HISTORY
            ])
            return

        if path.startswith("/api/history/"):
            entry_id = path.split("/")[-1]
            for item in SEND_HISTORY:
                if item["id"] == entry_id:
                    self.respond(200, item)
                    return
            self.respond(404, {"error": "Registro no encontrado"})
            return

        self.respond(404, {"error": "Ruta no encontrada"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        payload = self.read_json_body()

        if path == "/api/analyze":
            ticket = payload.get("payload") if isinstance(payload.get("payload"), dict) else payload
            self.respond(200, summarize_ticket(ticket if isinstance(ticket, dict) else {}))
            return

        if path == "/api/send":
            status, body = process_send(payload)
            self.respond(status, body)
            return

        self.respond(404, {"error": "Ruta no encontrada"})

    def serve_ui(self):
        if not UI_PATH.exists():
            self.respond(500, {"error": "ui.html no encontrado"})
            return
        self.respond_text(200, UI_PATH.read_text(encoding="utf-8"), "text/html; charset=utf-8")

    def read_json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        raw_body = self.rfile.read(length).decode("utf-8")
        try:
            return json.loads(raw_body)
        except json.JSONDecodeError:
            return {"error": "JSON invalido"}

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def respond_text(self, status, body, content_type):
        encoded_body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded_body)))
        self.end_headers()
        self.wfile.write(encoded_body)

    def log_message(self, format, *args):
        print("%s - %s" % (self.address_string(), format % args), flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", SERVER_PORT), SimphonyMockHandler)
    print(
        f"Simphony POS mock UI on :{SERVER_PORT} -> {TARGET_URL} (timeout={REQUEST_TIMEOUT}s)",
        flush=True,
    )
    server.serve_forever()
