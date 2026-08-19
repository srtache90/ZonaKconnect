namespace DIAN_NET.Models
{
    /// <summary>
    /// Response del envío de factura
    /// </summary>
    public class EnviarFacturaResponse
    {
        public bool Exitoso { get; set; }
        public string StatusCode { get; set; }
        public string StatusDescription { get; set; }
        public string StatusMessage { get; set; }
        public bool IsValid { get; set; }
        public string XmlDocumentKey { get; set; }
        public string XmlFileName { get; set; }
        public string CUFE { get; set; }
        public string QRCode { get; set; }
        public string? DebugXmlId { get; set; }
        public string ApplicationResponseXml { get; set; }
        public string SignedXmlBase64 { get; set; }
        public string ApplicationResponseXmlBase64 { get; set; }
        public string ZipBase64 { get; set; }
        public string[] Errores { get; set; }
    }
}
