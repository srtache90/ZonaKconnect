using System;
using System.Collections.Generic;

namespace DIAN_NET.Models
{
    /// <summary>
    /// DTO simplificado para Factura Electrónica
    /// </summary>
    public class FacturaDto
    {
        public string TipoDocumento { get; set; } = "FV"; // FV: Factura de Venta
        public string InvoiceTypeCode { get; set; } = "01";
        public string NumeroDocumento { get; set; }
        public DateTime FechaEmision { get; set; }
        public DateTime FechaVencimiento { get; set; }
        public string Moneda { get; set; } = "COP";
        
        // Emisor
        public EmisorDto Emisor { get; set; }
        
        // Cliente
        public ClienteDto Cliente { get; set; }
        
        // Productos/Servicios
        public List<ItemDto> Items { get; set; } = new List<ItemDto>();
        
        // Totales
        public TotalesDto Totales { get; set; }
        
        // Información adicional
        public string Observaciones { get; set; }
        public List<string> Notas { get; set; } = new List<string>();
        
        // Configuración DIAN
        public ConfiguracionDianDto ConfiguracionDian { get; set; }
    }
    
    public class EmisorDto
    {
        public string Nit { get; set; }
        public string Dv { get; set; } = "0";
        public string TipoIdentificacion { get; set; } = "31";
        public string TipoPersona { get; set; } = "1";
        public string RazonSocial { get; set; }
        public string NombreComercial { get; set; }
        public DireccionDto Direccion { get; set; }
        public string Telefono { get; set; }
        public string Email { get; set; }
        public string RegimenFiscal { get; set; } // O-23, R-99-PN, etc.
        public string TributoId { get; set; } = "01";
        public string TributoNombre { get; set; } = "IVA";
        public string ActividadEconomica { get; set; }
    }
    
    public class ClienteDto
    {
        public string TipoIdentificacion { get; set; } // 13: NIT, 31: Número de documento de identificación
        public string NumeroIdentificacion { get; set; }
        public string Dv { get; set; } = "0";
        public string TipoPersona { get; set; } // 1: Jurídica, 2: Natural
        public string RazonSocial { get; set; }
        public string NombreComercial { get; set; }
        public DireccionDto Direccion { get; set; }
        public string Telefono { get; set; }
        public string Email { get; set; }
        public string RegimenFiscal { get; set; }
        public string TributoId { get; set; } = "ZZ";
        public string TributoNombre { get; set; } = "No Aplica";
    }
    
    public class DireccionDto
    {
        public string CodigoPostal { get; set; }
        public string Departamento { get; set; }
        public string CodigoDepartamento { get; set; }
        public string Municipio { get; set; }
        public string CodigoMunicipio { get; set; }
        public string DireccionCompleta { get; set; }
        public string Pais { get; set; } = "CO";
    }
    
    public class ItemDto
    {
        public int NumeroLinea { get; set; }
        public string Codigo { get; set; }
        public string Descripcion { get; set; }
        public decimal Cantidad { get; set; }
        public string UnidadMedida { get; set; } = "94"; // Unidad
        public decimal PrecioUnitario { get; set; }
        public decimal Descuento { get; set; }
        public decimal Subtotal { get; set; }
        public List<ImpuestoDto> Impuestos { get; set; } = new List<ImpuestoDto>();
        public decimal Total { get; set; }
    }
    
    public class ImpuestoDto
    {
        public string Codigo { get; set; } // 01: IVA, 04: INC (Impuesto al Consumo)
        public string Nombre { get; set; }
        public string Tipo { get; set; } = "Porcentual";
        public decimal Porcentaje { get; set; }
        public decimal BaseImponible { get; set; }
        public decimal Valor { get; set; }
        public decimal BaseUnitMeasure { get; set; }
        public string UnitCode { get; set; } = "94";
        public decimal PerUnitAmount { get; set; }
        public bool EsRetencion { get; set; }
    }
    
    public class TotalesDto
    {
        public decimal Subtotal { get; set; }
        public decimal TotalDescuentos { get; set; }
        public decimal TotalImpuestos { get; set; }
        public decimal Total { get; set; }
    }
    
    public class ConfiguracionDianDto
    {
        public string NumeroResolucion { get; set; }
        public DateTime FechaResolucion { get; set; }
        public DateTime FechaInicio { get; set; }
        public DateTime FechaFin { get; set; }
        public string Prefijo { get; set; }
        public string RangoInicio { get; set; }
        public string RangoFin { get; set; }
        public string TipoAmbiente { get; set; } = "2";
        public string SoftwareId { get; set; }
        public string Pin { get; set; }
        public string ClaveTecnica { get; set; }
    }
}
