using System;
using System.Collections.Generic;

namespace DIAN_NET.Models
{
    /// <summary>
    /// DTO simplificado para Nota Débito electrónica (tipo 92).
    /// </summary>
    public class NotaDebitoDto
    {
        public string TipoDocumento { get; set; } = "ND";
        public string CustomizationID { get; set; } = "30";
        public string DebitNoteTypeCode { get; set; } = "92";
        public string NumeroDocumento { get; set; }
        public DateTime FechaEmision { get; set; }
        public string Moneda { get; set; } = "COP";

        public ReferenciaDocumentoDto FacturaReferencia { get; set; }
        public EmisorDto Emisor { get; set; }
        public ClienteDto Cliente { get; set; }
        public List<ConceptoCorreccionDto> ConceptosCorreccion { get; set; } = new List<ConceptoCorreccionDto>();
        public List<ItemDto> Items { get; set; } = new List<ItemDto>();
        public TotalesDto Totales { get; set; }
        public string Observaciones { get; set; }
        public List<string> Notas { get; set; } = new List<string>();
        public ConfiguracionDianDto ConfiguracionDian { get; set; }
    }
}
