using System;
using System.Collections.Generic;

namespace DIAN_NET.Models
{
    /// <summary>
    /// DTO simplificado para Nota Crédito
    /// </summary>
    public class NotaCreditoDto
    {
        public string TipoDocumento { get; set; } = "NC"; // NC: Nota Crédito
        public string CustomizationID { get; set; } = "20";
        public string CreditNoteTypeCode { get; set; } = "91";
        public string NumeroDocumento { get; set; }
        public DateTime FechaEmision { get; set; }
        public string Moneda { get; set; } = "COP";
        
        // Referencia a la factura original
        public ReferenciaDocumentoDto FacturaReferencia { get; set; }
        
        // Emisor
        public EmisorDto Emisor { get; set; }
        
        // Cliente
        public ClienteDto Cliente { get; set; }
        
        // Conceptos de corrección
        public List<ConceptoCorreccionDto> ConceptosCorreccion { get; set; } = new List<ConceptoCorreccionDto>();
        
        // Items ajustados
        public List<ItemDto> Items { get; set; } = new List<ItemDto>();
        
        // Totales
        public TotalesDto Totales { get; set; }
        
        // Información adicional
        public string Observaciones { get; set; }
        public List<string> Notas { get; set; } = new List<string>();
        
        // Configuración DIAN
        public ConfiguracionDianDto ConfiguracionDian { get; set; }
    }
    
    public class ReferenciaDocumentoDto
    {
        public string TipoDocumento { get; set; } = "FV"; // FV, NC, ND
        public string NumeroDocumento { get; set; }
        public DateTime FechaEmision { get; set; }
        public string CUFE { get; set; }
        public string SchemeName { get; set; } = "CUFE-SHA384";
    }
    
    public class ConceptoCorreccionDto
    {
        public string ReferenceID { get; set; }
        public string Codigo { get; set; } = "1"; // 1: Devolución parcial, 2: Anulación, etc.
        public string Descripcion { get; set; }
    }
}
