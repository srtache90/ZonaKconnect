# Ejemplo de flujo DIAN local

Este ejemplo valida el motor fiscal `DIAN_NET` sin depender de los servidores piloto de la DIAN.

## Ejecutar prueba automatizada de DIAN_NET

```powershell
.\examples\dian-flow\test-dian-net-flow.ps1
```

La prueba:

- levanta `DIAN_NET` en `http://localhost:5090`;
- habilita `DianConfig__Mock__Enabled=true`;
- emite `invoice.json`;
- toma el CUFE retornado y lo inyecta en `credit-note.template.json`;
- emite la nota crédito por `/api/v1/emit/credit-note`;
- valida que los XML firmados contengan `TaxTotal`, `WithholdingTaxTotal`, `DiscrepancyResponse` y `BillingReference`;
- guarda respuestas y XML en `examples\dian-flow\out`.

## Flujo completo con Go

Para probar Go + DIAN_NET + Postgres, Docker Desktop debe estar activo:

```powershell
docker compose -f docker-compose.local.yml up --build
```

Tenant local:

```text
X-Tenant-ID: 00000000-0000-0000-0000-000000000001
X-Emission-Point-ID: 00000000-0000-0000-0000-000000000101
```

Endpoints:

```text
POST http://localhost:8081/api/v1/invoices
POST http://localhost:8081/api/v1/credit-notes
GET  http://localhost:8081/api/v1/invoices/{id}/documents/signed-xml
GET  http://localhost:8081/api/v1/invoices/{id}/documents/app-response
GET  http://localhost:8081/api/v1/invoices/{id}/documents/pdf
```

Payloads para Core Go:

```text
examples\dian-flow\core-invoice.json
examples\dian-flow\core-credit-note.template.json
```
