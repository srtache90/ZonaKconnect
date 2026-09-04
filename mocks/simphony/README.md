# Mock Simphony / POS — inyector JSON

Simula la inyeccion de tickets JSON que llegan desde Simphony hacia el portal Zona K.

El payload real de Harmony/Simphony (campos `numero_factura`, `caja_wsid`, `Resolucion`, `items[].nombre`) entra por:

```text
POST /api/v1/ingest/pos
Header: X-API-Key: <api_key de la sociedad>
Header opcional: X-Emission-Point-ID: <uuid del PV>
```

`POST /api/v1/ingest/simphony` espera otro contrato (`ticketId`, `customer`, `items[].precioUnitario`). No uses ese path con los JSON Harmony.

## Destino local

| Desde | URL |
|---|---|
| Docker (mock -> portal) | `http://portal-java:8081/api/v1/ingest/pos` |
| Host (navegador / python local) | `http://localhost:8080/api/v1/ingest/pos` |

API key local (migracion `005_security_api_key.sql`):

```text
local-sap-simphony-api-key
```

Sin ese header el portal responde **401** (`X-API-Key invalida o punto de venta no autorizado`).

## GUI

```text
http://localhost:8096/
```

Desde la UI puedes:

- pegar o cargar uno o varios `.json`
- cargar el ticket de muestra Pravda `P660502`
- enviar con `X-API-Key` (y PV opcional)
- ver request, respuesta HTTP y diagnostico de 401

## Levantar

```powershell
docker compose -f docker-compose.local.yml up -d --build simphony-mock
```

Sin Docker (portal ya en `localhost:8080`):

```powershell
cd mocks\simphony
$env:SIMPHONY_TARGET_URL = "http://localhost:8080/api/v1/ingest/pos"
$env:SIMPHONY_API_KEY = "local-sap-simphony-api-key"
$env:SIMPHONY_MOCK_PORT = "8096"
python app.py
```

GUI: `http://localhost:8096/`

## Salud

```powershell
curl.exe http://localhost:8096/health
```
