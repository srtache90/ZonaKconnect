import html
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


TARGET_URL = os.getenv("SAP_TARGET_URL", "http://portal-java:8081/ws/enviardocumento")
TRANSPORT_MODE = os.getenv("SAP_TRANSPORT_MODE", "soap").strip().lower()
COMPANY_ID = os.getenv("SAP_COMPANY_ID", "1")
SAP_PREFIX = os.getenv("SAP_PREFIX", "EPR")
SAP_USUARIO = os.getenv("SAP_USUARIO", "ULocalSap")
SAP_PASSWORD = os.getenv("SAP_PASSWORD", "SapMock2026!")
SERVER_PORT = int(os.getenv("SAP_MOCK_PORT", "8080"))
MAX_BATCH_SIZE = int(os.getenv("SAP_MAX_BATCH_SIZE", "500"))
MAX_HISTORY_SIZE = int(os.getenv("SAP_MAX_HISTORY_SIZE", "50"))
REQUEST_TIMEOUT = int(os.getenv("SAP_REQUEST_TIMEOUT", "150"))

UI_PATH = Path(__file__).with_name("ui.html")
SEND_HISTORY = deque(maxlen=MAX_HISTORY_SIZE)
SOAP_NAMESPACE = "http://wsenviardocumento.webservice.dispapeles.com/"


def xml_escape(value):
    return html.escape(str(value), quote=True)


def build_consecutivo(index=1):
    base = int(datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"))
    return str(base + index)


def build_sap_xml(consecutivo, payload):
    subtotal = float(payload.get("subtotal", 10000))
    discount = float(payload.get("discount", 0))
    tax = float(payload.get("tax", round((subtotal - discount) * 0.19, 2)))
    total = float(payload.get("total", subtotal - discount + tax))
    quantity = float(payload.get("quantity", 1))
    unit_price = float(payload.get("unitPrice", subtotal / quantity if quantity else subtotal))
    company_id = payload.get("companyId", COMPANY_ID)
    prefix = payload.get("prefix", SAP_PREFIX)
    usuario = payload.get("sapUsuario", SAP_USUARIO)
    contrasenia = payload.get("sapPassword", SAP_PASSWORD)

    discount_block = ""
    if discount > 0:
        discount_block = f"""
      <listaDescuentos>
        <codigoDescuento>ZDES</codigoDescuento>
        <descripcion>Descuento</descripcion>
        <descuento>{discount:.4f}</descuento>
        <porcentajeDescuento>{round((discount / subtotal) * 100, 2) if subtotal else 0:.1f}</porcentajeDescuento>
      </listaDescuentos>"""

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<enviarDocumento>
  <felCabezaDocumento>
    <aplicafel>SI</aplicafel>
    <cantidadLineas>1</cantidadLineas>
    <idEmpresa>{xml_escape(company_id)}</idEmpresa>
    <consecutivo>{xml_escape(consecutivo)}</consecutivo>
    <prefijo>{xml_escape(prefix)}</prefijo>
    <usuario>{xml_escape(usuario)}</usuario>
    <contrasenia>{xml_escape(contrasenia)}</contrasenia>
    <fechafacturacion>{datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}</fechafacturacion>
    <listaDetalle>
      <cantidad>{quantity:.1f}</cantidad>
      <codigoproducto>{xml_escape(payload.get("itemCode", "SAP-ITEM-001"))}</codigoproducto>
      <descripcion>{xml_escape(payload.get("itemDescription", "Producto mock SAP"))}</descripcion>
      <nombreProducto>{xml_escape(payload.get("itemDescription", "Producto mock SAP"))}</nombreProducto>
      <valorunitario>{unit_price:.4f}</valorunitario>
      <preciosinimpuestos>{subtotal - discount:.4f}</preciosinimpuestos>
      <preciototal>{total:.4f}</preciototal>
      <unidadmedida>{xml_escape(payload.get("unit", "EA"))}</unidadmedida>{discount_block}
      <listaImpuestos>
        <baseimponible>{subtotal - discount:.4f}</baseimponible>
        <codigoImpuestoRetencion>01</codigoImpuestoRetencion>
        <porcentaje>19.0</porcentaje>
        <valorImpuestoRetencion>{tax:.4f}</valorImpuestoRetencion>
      </listaImpuestos>
    </listaDetalle>
    <pago>
      <moneda>{xml_escape(payload.get("currency", "COP"))}</moneda>
      <tipocompra>2</tipocompra>
      <totalbaseimponible>{subtotal - discount:.4f}</totalbaseimponible>
      <totalbaseconimpuestos>{total:.4f}</totalbaseconimpuestos>
      <totalimportebruto>{subtotal:.4f}</totalimportebruto>
      <totalfactura>{total:.4f}</totalfactura>
    </pago>
    <listaImpuestos>
      <baseimponible>{subtotal - discount:.4f}</baseimponible>
      <codigoImpuestoRetencion>01</codigoImpuestoRetencion>
      <porcentaje>19.0</porcentaje>
      <valorImpuestoRetencion>{tax:.4f}</valorImpuestoRetencion>
    </listaImpuestos>
    <listaAdquirentes>
      <tipoIdentificacion>{xml_escape(payload.get("customerDocumentType", "31"))}</tipoIdentificacion>
      <numeroIdentificacion>{xml_escape(payload.get("customerDocument", "900123456"))}</numeroIdentificacion>
      <nombreCompleto>{xml_escape(payload.get("customerName", "Cliente Mock SAP"))}</nombreCompleto>
      <email>{xml_escape(payload.get("customerEmail", "cliente.sap@zonak.local"))}</email>
      <direccion>{xml_escape(payload.get("customerAddress", "Direccion mock SAP"))}</direccion>
    </listaAdquirentes>
  </felCabezaDocumento>
</enviarDocumento>
"""


def wrap_soap_envelope(xml_document):
    inner = xml_document
    if inner.startswith("<?xml"):
        inner = inner.split("?>", 1)[1].strip()
    inner = inner.replace(
        "<enviarDocumento>",
        f'<n0:enviarDocumento xmlns:n0="{SOAP_NAMESPACE}">',
        1,
    )
    inner = inner.replace("</enviarDocumento>", "</n0:enviarDocumento>", 1)
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    {inner}
  </soap:Body>
</soap:Envelope>
"""


def extract_xml_tag(xml_text, tag_name):
    match = re.search(rf"<{tag_name}>(.*?)</{tag_name}>", xml_text, re.DOTALL | re.IGNORECASE)
    if not match:
        return ""
    return html.unescape(match.group(1)).strip()


def parse_portal_response(body):
    trimmed = (body or "").strip()
    if not trimmed:
        return {}

    if trimmed.startswith("<"):
        parsed = {
            "transport": TRANSPORT_MODE,
            "codigo": extract_xml_tag(trimmed, "codigo"),
            "mensaje": extract_xml_tag(trimmed, "mensaje"),
            "mensajeDian": extract_xml_tag(trimmed, "mensaje"),
            "cufe": extract_xml_tag(trimmed, "cufe"),
            "idDocumento": extract_xml_tag(trimmed, "idDocumento"),
            "numeroDocumento": extract_xml_tag(trimmed, "numeroDocumento"),
            "rawXml": trimmed,
        }
        parsed["exitoso"] = parsed["codigo"] == "0"
        return parsed

    try:
        data = json.loads(trimmed)
        if isinstance(data, dict):
            data.setdefault("mensajeDian", data.get("mensajeDian") or data.get("mensaje"))
            data["exitoso"] = data.get("status") == "DIAN_EXITOSA" or data.get("codigo") == "0"
        return data
    except json.JSONDecodeError:
        return {"body": trimmed}


def send_xml(xml):
    payload = wrap_soap_envelope(xml) if TRANSPORT_MODE == "soap" else xml
    content_type = "application/soap+xml" if TRANSPORT_MODE == "soap" else "application/xml"
    accept = "application/soap+xml, text/xml, application/xml, application/json"

    request = urllib.request.Request(
        TARGET_URL,
        data=payload.encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": content_type,
            "Accept": accept,
        },
    )

    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
            body = response.read().decode("utf-8")
            parsed = parse_portal_response(body)
            return response.status, parsed, payload
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        parsed = parse_portal_response(body)
        if not parsed:
            parsed = {"error": body}
        return error.code, parsed, payload
    except urllib.error.URLError as error:
        return 503, {
            "error": "No fue posible conectar con portal-java",
            "detail": str(error.reason),
            "targetUrl": TARGET_URL,
        }, payload


def record_history(consecutivo, payload, xml, request_body, http_status, response):
    entry = {
        "id": str(uuid.uuid4()),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "consecutivo": consecutivo,
        "customerName": payload.get("customerName"),
        "total": payload.get("total"),
        "httpStatus": http_status,
        "transportMode": TRANSPORT_MODE,
        "targetUrl": TARGET_URL,
        "mensajeDian": response.get("mensajeDian") or response.get("mensaje"),
        "codigo": response.get("codigo"),
        "exitoso": response.get("exitoso"),
        "cufe": response.get("cufe"),
        "xml": xml,
        "requestBody": request_body,
        "response": response,
    }
    SEND_HISTORY.appendleft(entry)
    return entry


def is_accepted(http_status, response):
    if isinstance(response, dict) and response.get("exitoso"):
        return True
    if TRANSPORT_MODE == "soap" and http_status == 200:
        codigo = str(response.get("codigo", "")).strip()
        return codigo == "0"
    if http_status == 200 and isinstance(response, dict):
        return response.get("status") == "DIAN_EXITOSA"
    return 200 <= http_status < 300


def process_send(payload):
    count = max(1, min(int(payload.get("count", 1)), MAX_BATCH_SIZE))
    delay_ms = max(0, int(payload.get("delayMs", 0)))
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    results = []

    for index in range(count):
        consecutivo = payload.get("consecutivo") or build_consecutivo(index + 1)
        xml = build_sap_xml(consecutivo, payload)
        status, response, request_body = send_xml(xml)
        record_history(consecutivo, payload, xml, request_body, status, response)
        results.append({
            "consecutivo": consecutivo,
            "httpStatus": status,
            "xml": xml,
            "requestBody": request_body,
            "response": response,
            "accepted": is_accepted(status, response),
        })

        if delay_ms and index < count - 1:
            time.sleep(delay_ms / 1000)

    accepted = sum(1 for result in results if result["accepted"])
    return {
        "targetUrl": TARGET_URL,
        "transportMode": TRANSPORT_MODE,
        "requested": count,
        "accepted": accepted,
        "failed": count - accepted,
        "results": results,
    }


class SapMockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path

        if path in ("/", "/ui"):
            self.serve_ui()
            return

        if path == "/health":
            portal_status = "unreachable"
            portal_base_url = TARGET_URL.rsplit("/ws/", 1)[0]
            if "/api/" in TARGET_URL:
                portal_base_url = TARGET_URL.rsplit("/api/", 1)[0]
            try:
                with urllib.request.urlopen(portal_base_url, timeout=3) as response:
                    portal_status = "ok" if response.status < 500 else "error"
            except Exception:
                portal_status = "unreachable"
            self.respond(200, {
                "status": "ok",
                "targetUrl": TARGET_URL,
                "transportMode": TRANSPORT_MODE,
                "portalStatus": portal_status,
            })
            return

        if path == "/api/config":
            self.respond(200, {
                "targetUrl": TARGET_URL,
                "transportMode": TRANSPORT_MODE,
                "companyId": COMPANY_ID,
                "prefix": SAP_PREFIX,
                "sapUsuario": SAP_USUARIO,
                "requestTimeoutSeconds": REQUEST_TIMEOUT,
                "maxBatchSize": MAX_BATCH_SIZE,
            })
            return

        if path == "/sap/sample":
            xml = build_sap_xml(build_consecutivo(), {})
            self.respond_text(200, xml, "application/xml")
            return

        if path == "/sap/history":
            self.respond(200, [
                {
                    "id": item["id"],
                    "timestamp": item["timestamp"],
                    "consecutivo": item["consecutivo"],
                    "customerName": item["customerName"],
                    "total": item["total"],
                    "httpStatus": item["httpStatus"],
                    "codigo": item.get("codigo"),
                    "mensajeDian": item.get("mensajeDian"),
                    "exitoso": item.get("exitoso"),
                }
                for item in SEND_HISTORY
            ])
            return

        history_match = path.startswith("/sap/history/")
        if history_match:
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

        if path == "/sap/preview":
            consecutivo = payload.get("consecutivo") or build_consecutivo()
            xml = build_sap_xml(consecutivo, payload)
            request_body = wrap_soap_envelope(xml) if TRANSPORT_MODE == "soap" else xml
            self.respond(200, {
                "consecutivo": consecutivo,
                "xml": xml,
                "requestBody": request_body,
                "transportMode": TRANSPORT_MODE,
            })
            return

        if path == "/sap/send":
            self.respond(200, process_send(payload))
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
            return {}

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
    server = ThreadingHTTPServer(("0.0.0.0", SERVER_PORT), SapMockHandler)
    print(
        f"SAP mock UI on :{SERVER_PORT} -> {TARGET_URL} ({TRANSPORT_MODE}, timeout={REQUEST_TIMEOUT}s)",
        flush=True,
    )
    server.serve_forever()
