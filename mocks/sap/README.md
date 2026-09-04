# Mock SAP local

Este servicio simula el envío de documentos desde SAP hacia el portal Zona K usando el contrato **Dispapeles/SOAP** o REST.

## Destino por defecto (Docker local)

```text
POST http://portal-java:8081/ws/enviardocumento

En SOAMANAGER (SAP) la URL del logical port es esa misma ruta en el host publicado:

```text
Local:       http://<host>:8080/ws/enviardocumento
Producción:  https://<dominio>/ws/enviardocumento
```
```

En el host queda expuesto por:

```text
http://localhost:8095
```

## Credenciales locales de prueba

Deben coincidir con la sociedad local (`id_empresa = 1`) y el punto de venta `EPR`:

| Campo | Valor |
|---|---|
| ID Empresa | `1` |
| Prefijo | `EPR` |
| Usuario SAP | `ULocalSap` |
| Contraseña SAP | `SapMock2026!` |

La migración `database/migrations/023_sap_soap_credentials.sql` carga estas credenciales en la sociedad local.

## Interfaz web

Abre en el navegador:

```text
http://localhost:8095/
```

Desde la UI puedes:

- configurar `idEmpresa`, `prefijo`, `usuario` y `contraseña`
- previsualizar el XML SAP (con descuentos e impuestos por línea)
- enviar vía **SOAP** al portal y esperar la **respuesta DIAN**
- ver el mensaje DIAN en el historial

## Levantar el mock

```powershell
docker compose -f docker-compose.local.yml up -d --build sap-mock
```

Si también necesitas toda la infraestructura local:

```powershell
docker compose -f docker-compose.local.yml up -d --build
```

Asegúrate de aplicar la migración `023_sap_soap_credentials.sql` en PostgreSQL antes de probar.

## Verificar salud

```powershell
curl.exe http://localhost:8095/health
```

## Ver XML SAP de muestra

```powershell
curl.exe http://localhost:8095/sap/sample
```

## Enviar un documento SAP

```powershell
curl.exe -X POST http://localhost:8095/sap/send `
  -H "Content-Type: application/json" `
  -d "{\"companyId\":1,\"prefix\":\"EPR\",\"sapUsuario\":\"ULocalSap\",\"sapPassword\":\"SapMock2026!\",\"customerDocument\":\"900123456\",\"customerName\":\"Cliente Mock SAP\",\"subtotal\":10000,\"discount\":0,\"tax\":1900,\"total\":11900}"
```

La respuesta incluye el mensaje DIAN devuelto por el portal:

```json
{
  "results": [
    {
      "accepted": true,
      "response": {
        "codigo": "0",
        "mensajeDian": "Procesado Correctamente.",
        "cufe": "..."
      }
    }
  ]
}
```

## Simular carga por lote

```powershell
curl.exe -X POST http://localhost:8095/sap/send `
  -H "Content-Type: application/json" `
  -d "{\"count\":5,\"delayMs\":2000,\"prefix\":\"EPR\",\"companyId\":1,\"subtotal\":10000,\"tax\":1900,\"total\":11900}"
```

`count` está limitado por `SAP_MAX_BATCH_SIZE` (por defecto `500`).

## Variables de entorno

| Variable | Descripción | Default local |
|---|---|---|
| `SAP_TARGET_URL` | Endpoint destino del portal | `/ws/enviardocumento` |
| `SAP_TRANSPORT_MODE` | `soap` o `rest` | `soap` |
| `SAP_COMPANY_ID` | `idEmpresa` del XML | `1` |
| `SAP_PREFIX` | Prefijo DIAN | `EPR` |
| `SAP_USUARIO` | Usuario en el XML | `ULocalSap` |
| `SAP_PASSWORD` | Contraseña en el XML | `SapMock2026!` |
| `SAP_REQUEST_TIMEOUT` | Segundos de espera (respuesta DIAN) | `150` |
| `SAP_MAX_BATCH_SIZE` | Máximo documentos por lote | `500` |
| `SAP_MOCK_PORT` | Puerto interno del mock | `8080` |

## Modo REST (alternativo)

Para probar `POST /api/v1/ingest/sap` en lugar de SOAP:

```yaml
SAP_TARGET_URL: http://portal-java:8081/api/v1/ingest/sap
SAP_TRANSPORT_MODE: rest
```
