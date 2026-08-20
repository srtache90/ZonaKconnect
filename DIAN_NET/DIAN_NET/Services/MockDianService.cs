using System;
using System.Text;
using DIAN_NET.DIANreference;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public class MockDianService : IDianService
    {
        public DianResponse ConsultarEstado(string trackId, string ambiente)
        {
            return BuildSuccessfulResponse(trackId);
        }

        public NumberRangeResponseList ConsultarRangos(string nit, string idSoftware, string ambiente)
        {
            return new NumberRangeResponseList();
        }

        public ConsultarEmpresaDIANResponse ConsultarEmpresaDIAN(string nit)
        {
            return new ConsultarEmpresaDIANResponse();
        }

        public DianResponse EnviarFactura(byte[] zipData, string nombreArchivo, string ambiente)
        {
            return BuildSuccessfulResponse(nombreArchivo);
        }

        public DianResponse EnviarNomina(byte[] zipData, string ambiente)
        {
            return BuildSuccessfulResponse($"nomina-{Guid.NewGuid():N}.zip");
        }

        public DianResponse EnviarEvento(byte[] zipData, string ambiente)
        {
            return BuildSuccessfulResponse($"evento-{Guid.NewGuid():N}.zip");
        }

        private static DianResponse BuildSuccessfulResponse(string documentKey)
        {
            var applicationResponseXml = """
                <ApplicationResponse xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ResponseCode>00</cbc:ResponseCode>
                  <cbc:Description>Documento Validado Exitosamente</cbc:Description>
                  <cbc:UUID>MOCK-DIAN-APPLICATION-RESPONSE</cbc:UUID>
                </ApplicationResponse>
                """;

            return new DianResponse
            {
                IsValid = true,
                StatusCode = "00",
                StatusDescription = "Documento Validado Exitosamente",
                StatusMessage = "ApplicationResponse mock DIAN generado correctamente",
                XmlDocumentKey = documentKey,
                XmlFileName = documentKey,
                XmlBytes = Encoding.UTF8.GetBytes(applicationResponseXml),
                ErrorMessage = Array.Empty<string>()
            };
        }
    }
}
