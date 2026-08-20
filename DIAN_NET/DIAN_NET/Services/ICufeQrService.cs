using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para calcular CUFE/CUDE y generar código QR
    /// </summary>
    public interface ICufeQrService
    {
        string CalcularCUFE(FacturaDto factura, string ambiente);
        string CalcularCUDE(NotaCreditoDto notaCredito, string ambiente);
        string CalcularCUFE(string xmlSinFirma, string numeroDocumento, DateTime fechaEmision, string tipoDocumento);
        string CalcularCUDEEvento(
            string eventId,
            DateTime issueDateTime,
            string senderNit,
            string receiverNit,
            string responseCode,
            string documentReferenceId,
            string documentTypeCode,
            string softwarePin);
        string CalcularCUDEEvento(
            string eventId,
            DateTimeOffset issueDateTime,
            string senderNit,
            string receiverNit,
            string responseCode,
            string documentReferenceId,
            string documentTypeCode,
            string softwarePin);
        string CalcularSoftwareSecurityCode(string softwareId, string softwarePin, string documentId);
        string GenerarQRCode(string cufe, string nitEmisor, string numeroDocumento, decimal total, DateTime fechaEmision);
    }
}
