# Microservicio de Facturación Electrónica DIAN

## Descripción

Microservicio Web API para transformación y envío de facturas electrónicas a la DIAN. Procesa JSON simplificado, genera XML UBL 2.1, firma digitalmente con XAdES-EPES y envía a los servicios web de la DIAN.

## Arquitectura

### Componentes Principales

1. **Modelos DTO** (`Models/`)
   - `FacturaDto`: Estructura simplificada para facturas
   - `NotaCreditoDto`: Estructura para notas crédito
   - `DocumentoSoporteDto`: Estructura para documentos soporte
   - `EnviarFacturaRequest/Response`: DTOs de API

2. **Servicios** (`Services/`)
   - `XmlTransformService`: Transforma DTOs a XML UBL 2.1 con extensiones DIAN
   - `CufeQrService`: Calcula CUFE/CUDE y genera códigos QR
   - `XadesSignService`: Firma XML con XAdES-EPES usando certificado .pfx
   - `DianService` (DianManager): Cliente WCF para servicios DIAN
   - `FacturacionService`: Orquesta todo el proceso

3. **Controladores** (`Controllers/`)
   - `FacturaController`: Endpoint REST para envío de facturas

## Endpoints

### POST /api/v1/factura/enviar

Envía una factura electrónica a la DIAN.

**Request Body:**
```json
{
  "ambiente": "Habilitacion",
  "factura": {
    "tipoDocumento": "FV",
    "numeroDocumento": "UN1807341",
    "fechaEmision": "2026-03-07T19:30:28",
    "fechaVencimiento": "2026-03-07T19:30:28",
    "moneda": "COP",
    "emisor": {
      "nit": "860504410",
      "razonSocial": "INDUSTRIA COMERCIAL DE ALIMENTOS NUTRIX SAS",
      "direccion": {
        "codigoPostal": "11001",
        "departamento": "BOGOTA",
        "codigoDepartamento": "11",
        "municipio": "BOGOTA",
        "direccionCompleta": "CRA 47 A 91 44",
        "pais": "CO"
      },
      "telefono": "6350806",
      "email": "katafactel@zonak.com.co",
      "regimenFiscal": "O-23"
    },
    "cliente": {
      "tipoIdentificacion": "13",
      "numeroIdentificacion": "222222222222",
      "tipoPersona": "2",
      "razonSocial": "Consumidor final",
      "regimenFiscal": "R-99-PN"
    },
    "items": [
      {
        "numeroLinea": 1,
        "codigo": "110385",
        "descripcion": "NudoAvellana",
        "cantidad": 1.00,
        "unidadMedida": "94",
        "precioUnitario": 10000.00,
        "descuento": 0.00,
        "subtotal": 10000.00,
        "impuestos": [
          {
            "codigo": "04",
            "nombre": "INC",
            "porcentaje": 8.00,
            "baseImponible": 10000.00,
            "valor": 800.00
          }
        ],
        "total": 10800.00
      }
    ],
    "totales": {
      "subtotal": 24815.00,
      "totalDescuentos": 0.00,
      "totalImpuestos": 1985.20,
      "total": 26800.20
    },
    "configuracionDian": {
      "numeroResolucion": "18764071802825",
      "fechaResolucion": "2024-05-30T00:00:00",
      "fechaInicio": "2024-05-30T00:00:00",
      "fechaFin": "2026-05-30T00:00:00",
      "prefijo": "UN",
      "rangoInicio": "1500001",
      "rangoFin": "2000000",
      "softwareId": "0b4269ea-d70a-438e-bf30-b90a2139f74e",
      "pin": "20191",
      "claveTecnica": "25bde1952cc4aa87321733ffe792ef440ca63eb9a236956984f7b03db750180a"
    }
  }
}
```

**Response:**
```json
{
  "exitoso": true,
  "statusCode": "00",
  "statusDescription": "Documento procesado correctamente",
  "statusMessage": "Documento aceptado",
  "isValid": true,
  "xmlDocumentKey": "...",
  "xmlFileName": "...",
  "cufe": "46c37920b1aa76df...",
  "qrCode": "https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=...",
  "applicationResponseXml": "<?xml version=\"1.0\"?>...",
  "errores": null
}
```

## Proceso de Envío

1. **Recepción del JSON** → El controlador recibe el `EnviarFacturaRequest`
2. **Transformación a XML** → `XmlTransformService` genera XML UBL 2.1
3. **Cálculo de CUFE** → `CufeQrService` calcula el CUFE con SHA384
4. **Generación de QR** → Se genera el código QR con el CUFE
5. **Firma Digital** → `XadesSignService` firma el XML con XAdES-EPES
6. **Compresión ZIP** → El XML firmado se comprime en un archivo ZIP
7. **Envío a DIAN** → `DianService` envía el ZIP usando `SendBillSync`
8. **Respuesta** → Se parsea el `ApplicationResponse` y se retorna

## Configuración

### appsettings.json

```json
{
  "DianConfig": {
    "Urls": {
      "Habilitacion": "https://vpfe-hab.dian.gov.co/WcfDianCustomerServices.svc",
      "Produccion": "https://vpfe.dian.gov.co/WcfDianCustomerServices.svc"
    },
    "Certificado": {
      "RutaPfx": "ruta/al/certificado.pfx",
      "Password": "contraseña"
    }
  }
}
```

## Dependencias

- .NET 8.0
- ASP.NET Core
- System.ServiceModel (WCF)
- System.Security.Cryptography.Xml (Firma XAdES)

## Notas de Implementación

### CUFE/CUDE
El cálculo del CUFE actualmente es una implementación simplificada. La especificación completa de la DIAN requiere más campos. Se recomienda validar contra la documentación oficial.

### Firma XAdES-EPES
La implementación actual usa `System.Security.Cryptography.Xml`. Para una implementación completa de XAdES-EPES según especificaciones DIAN, considere usar una librería especializada como XAdES.Net.

### Validaciones
El servicio no valida completamente el XML contra los XSD de la DIAN antes de enviar. Se recomienda agregar validación XSD antes del envío.

## Próximos Pasos

1. Implementar validación XSD antes del envío
2. Completar implementación de Nota Crédito y Documento Soporte
3. Agregar logging estructurado
4. Implementar retry policies para envíos fallidos
5. Agregar métricas y monitoreo
6. Implementar caché para certificados y configuraciones
