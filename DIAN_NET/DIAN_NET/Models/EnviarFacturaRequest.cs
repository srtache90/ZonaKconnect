namespace DIAN_NET.Models
{
    /// <summary>
    /// Request para enviar factura
    /// </summary>
    public class EnviarFacturaRequest
    {
        public string Ambiente { get; set; } = "Habilitacion"; // Habilitacion o Produccion
        public string? TestSetId { get; set; }
        public FacturaDto Factura { get; set; }
    }
    
    public class EnviarNotaCreditoRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public string? TestSetId { get; set; }
        public NotaCreditoDto NotaCredito { get; set; }
    }

    public class EnviarNotaDebitoRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public string? TestSetId { get; set; }
        public NotaDebitoDto NotaDebito { get; set; }
    }
    
    public class EnviarDocumentoSoporteRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        public DocumentoSoporteDto DocumentoSoporte { get; set; }
    }
}
