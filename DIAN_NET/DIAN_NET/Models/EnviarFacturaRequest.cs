namespace DIAN_NET.Models
{
    /// <summary>
    /// Request para enviar factura
    /// </summary>
    public class EnviarFacturaRequest
    {
        public string Ambiente { get; set; } = "Habilitacion"; // Habilitacion o Produccion
        public FacturaDto Factura { get; set; }
    }
    
    public class EnviarNotaCreditoRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public NotaCreditoDto NotaCredito { get; set; }
    }
    
    public class EnviarDocumentoSoporteRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public DocumentoSoporteDto DocumentoSoporte { get; set; }
    }
}
