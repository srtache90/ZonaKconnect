using System;
using System.Collections.Generic;

namespace DIAN_NET.Models
{
    /// <summary>
    /// DTO simplificado para Documento Soporte
    /// </summary>
    public class DocumentoSoporteDto
    {
        public string TipoDocumento { get; set; } = "DS"; // DS: Documento Soporte
        public string NumeroDocumento { get; set; }
        public DateTime FechaEmision { get; set; }
        public string Moneda { get; set; } = "COP";
        
        // Emisor
        public EmisorDto Emisor { get; set; }
        
        // Cliente
        public ClienteDto Cliente { get; set; }
        
        // Items
        public List<ItemDto> Items { get; set; } = new List<ItemDto>();
        
        // Totales
        public TotalesDto Totales { get; set; }
        
        // Información adicional
        public string Observaciones { get; set; }
        public List<string> Notas { get; set; } = new List<string>();
        
        // Configuración DIAN
        public ConfiguracionDianDto ConfiguracionDian { get; set; }
    }
}
