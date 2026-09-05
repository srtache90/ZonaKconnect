# Mock SAP local

Simula el envío SOAP Dispapeles desde SAP (`enviarDocumento`) hacia Zona K. Incluye GUI para generar XML o pegar el XML real del proxy KAP.

## Destino por defecto (Docker local)

```text
POST http://portal-java:8081/ws/enviardocumento
```

En el host:

```text
http://localhost:8095
```

SOAMANAGER (SAP real) usa la misma ruta publicada:

```text
Local:       http://<host>:8080/ws/enviardocumento
Producción:  https://<dominio>/ws/enviardocumento
```

## Credenciales locales

Deben coincidir con la sociedad local (`id_empresa = 1`) y el PV `EPR`:

| Campo | Valor |
|---|---|
| ID Empresa | `1` |
| Prefijo | `EPR` |
| Usuario SAP | `ULocalSap` |
| Contraseña SAP | `SapMock2026!` |

La migración `database/migrations/023_sap_soap_credentials.sql` carga estas credenciales.

## GUI

```text
http://localhost:8095/
```

- **Generar XML:** arma un `enviarDocumento` (descuentos/IVA por línea) y lo envuelve en SOAP 1.1.
- **Pegar XML SAP:** carga un `.xml` o pega el payload del proxy KAP (`n0:enviarDocumento`) y lo manda tal cual.
- **Cargar muestra KAP:** `mocks/sap/samples/enviar-documento-kap.xml`.
- Espera la respuesta DIAN (`codigo`, `mensaje`, `cufe`) y la deja en el historial.

## Levantar

```powershell
docker compose -f docker-compose.local.yml up -d --build sap-mock
```

## Verificar

```powershell
curl.exe http://localhost:8095/health
```

## Enviar XML crudo (como SAP)

```powershell
curl.exe -X POST http://localhost:8095/sap/send-xml `
  -H "Content-Type: application/json" `
  --data-binary "@mocks/sap/samples/enviar-documento-kap.xml"
```

Mejor desde la GUI (el endpoint espera JSON `{ "xml": "..." }`):

```powershell
curl.exe http://localhost:8095/sap/sample-kap
```

## Enviar documento generado

```powershell
curl.exe -X POST http://localhost:8095/sap/send `
  -H "Content-Type: application/json" `
  -d "{\"companyId\":1,\"prefix\":\"EPR\",\"sapUsuario\":\"ULocalSap\",\"sapPassword\":\"SapMock2026!\",\"customerDocument\":\"900123456\",\"customerName\":\"Cliente Mock SAP\",\"subtotal\":10000,\"discount\":0,\"tax\":1900,\"total\":11900}"
```

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
