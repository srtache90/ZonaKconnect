using System;
using System.Linq;
using System.Threading.Tasks;
using System.Xml;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public class EmissionService : IEmissionService
    {
        private readonly IFacturacionService _facturacionService;

        public EmissionService(IFacturacionService facturacionService)
        {
            _facturacionService = facturacionService ?? throw new ArgumentNullException(nameof(facturacionService));
        }

        public async Task<EmitDocumentResponse> EmitInvoiceAsync(EmitInvoiceRequest request)
        {
            var response = !string.IsNullOrWhiteSpace(request.XMLBase) && request.Factura == null
                ? await _facturacionService.EnviarXmlFacturaAsync(request.XMLBase!, request.Ambiente)
                : await _facturacionService.EnviarFacturaAsync(new EnviarFacturaRequest
                {
                    Ambiente = request.Ambiente,
                    Factura = request.Factura!
                });

            return MapResponse(response, "CUFE");
        }

        public async Task<EmitDocumentResponse> EmitCreditNoteAsync(EmitCreditNoteRequest request)
        {
            var response = await _facturacionService.EnviarNotaCreditoAsync(new EnviarNotaCreditoRequest
            {
                Ambiente = request.Ambiente,
                NotaCredito = request.NotaCredito!
            });

            return MapResponse(response, "CUDE");
        }

        public async Task<EmitDocumentResponse> EmitDebitNoteAsync(EmitDebitNoteRequest request)
        {
            var response = await _facturacionService.EnviarNotaDebitoAsync(new EnviarNotaDebitoRequest
            {
                Ambiente = request.Ambiente,
                NotaDebito = request.NotaDebito!
            });

            return MapResponse(response, "CUDE");
        }

        public async Task<EmitDocumentResponse> EmitSupportDocumentAsync(EmitSupportDocumentRequest request)
        {
            var response = await _facturacionService.EnviarDocumentoSoporteAsync(new EnviarDocumentoSoporteRequest
            {
                Ambiente = request.Ambiente,
                DocumentoSoporte = request.DocumentoSoporte!
            });

            return MapResponse(response, "CUFE");
        }

        public async Task<EmitDocumentResponse> EmitPayrollAsync(EmitPayrollRequest request)
        {
            var response = await _facturacionService.EnviarNominaAsync(request);
            return MapResponse(response, "CUNE");
        }

        private static EmitDocumentResponse MapResponse(EnviarFacturaResponse response, string identifierKind)
        {
            var uuid = ExtractUuid(response.ApplicationResponseXml) ?? response.CUFE;
            var errores = response.Errores ?? Array.Empty<string>();
            var exitoso = response.Exitoso || response.IsValid ||
                          string.Equals(response.StatusCode, "00", StringComparison.OrdinalIgnoreCase);

            return new EmitDocumentResponse
            {
                Status = exitoso ? "Exitoso" : "Fallido",
                Exitoso = exitoso,
                EstadoDian = exitoso ? "ENVIADO" : "RECHAZADO_DIAN",
                CufeCune = response.CUFE,
                CUFE = identifierKind == "CUFE" ? response.CUFE : null,
                CUNE = identifierKind == "CUNE" ? response.CUFE : null,
                TrackID = response.XmlDocumentKey,
                UUID = uuid,
                DebugXmlId = response.DebugXmlId,
                StatusCode = response.StatusCode,
                StatusDescription = response.StatusDescription,
                StatusMessage = response.StatusMessage,
                SignedXmlBase64 = response.SignedXmlBase64,
                ApplicationResponseXml = response.ApplicationResponseXml,
                ApplicationResponseXmlBase64 = response.ApplicationResponseXmlBase64,
                ZipBase64 = response.ZipBase64,
                Errores = errores.Where(error => !string.IsNullOrWhiteSpace(error)).ToArray()
            };
        }

        private static string? ExtractUuid(string? applicationResponseXml)
        {
            if (string.IsNullOrWhiteSpace(applicationResponseXml))
            {
                return null;
            }

            try
            {
                var xmlDoc = new XmlDocument();
                xmlDoc.LoadXml(applicationResponseXml);
                var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
                nsmgr.AddNamespace("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");

                return xmlDoc.SelectSingleNode("//cbc:UUID", nsmgr)?.InnerText;
            }
            catch
            {
                return null;
            }
        }
    }
}
