using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace DIAN_NET.Models
{
    public class EmitInvoiceRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public FacturaDto? Factura { get; set; }
        [JsonPropertyName("xml_base")]
        public string? XMLBase { get; set; }
    }

    public class EmitSupportDocumentRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public DocumentoSoporteDto? DocumentoSoporte { get; set; }
    }

    public class EmitCreditNoteRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public NotaCreditoDto? NotaCredito { get; set; }
    }

    public class EmitPayrollRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public NominaDto? Nomina { get; set; }
    }

    public class EmitDocumentResponse
    {
        public string Status { get; set; } = "Fallido";
        public bool Exitoso { get; set; }
        public string? CufeCune { get; set; }
        public string? CUFE { get; set; }
        public string? CUNE { get; set; }
        public string? TrackID { get; set; }
        public string? UUID { get; set; }
        [JsonPropertyName("debug_xml_id")]
        public string? DebugXmlId { get; set; }
        public string? StatusCode { get; set; }
        public string? StatusDescription { get; set; }
        public string? StatusMessage { get; set; }
        public string? SignedXmlBase64 { get; set; }
        public string? ApplicationResponseXml { get; set; }
        public string? ApplicationResponseXmlBase64 { get; set; }
        public string? ZipBase64 { get; set; }
        public string[] Errores { get; set; } = Array.Empty<string>();
    }

    public class NominaDto
    {
        public string TipoDocumento { get; set; } = "NominaIndividual";
        public string NumeroDocumento { get; set; } = string.Empty;
        public DateTime FechaEmision { get; set; }
        public string PeriodoNomina { get; set; } = string.Empty;
        public string Moneda { get; set; } = "COP";
        public EmisorDto? Empleador { get; set; }
        public TrabajadorNominaDto? Trabajador { get; set; }
        public PagoNominaDto? Pago { get; set; }
        public List<ConceptoNominaDto> Devengados { get; set; } = new();
        public List<ConceptoNominaDto> Deducciones { get; set; } = new();
        public ConfiguracionDianDto? ConfiguracionDian { get; set; }
        public List<string> Notas { get; set; } = new();
    }

    public class TrabajadorNominaDto
    {
        public string TipoIdentificacion { get; set; } = "13";
        public string NumeroIdentificacion { get; set; } = string.Empty;
        public string PrimerNombre { get; set; } = string.Empty;
        public string? OtrosNombres { get; set; }
        public string PrimerApellido { get; set; } = string.Empty;
        public string? SegundoApellido { get; set; }
        public string TipoContrato { get; set; } = string.Empty;
        public string SubtipoTrabajador { get; set; } = string.Empty;
        public string TipoTrabajador { get; set; } = string.Empty;
        public decimal Sueldo { get; set; }
    }

    public class PagoNominaDto
    {
        public DateTime FechaIngreso { get; set; }
        public DateTime FechaLiquidacionInicio { get; set; }
        public DateTime FechaLiquidacionFin { get; set; }
        public DateTime FechaPago { get; set; }
        public decimal TotalDevengados { get; set; }
        public decimal TotalDeducciones { get; set; }
        public decimal TotalComprobante { get; set; }
    }

    public class ConceptoNominaDto
    {
        public string Codigo { get; set; } = string.Empty;
        public string Descripcion { get; set; } = string.Empty;
        public decimal Valor { get; set; }
    }
}
