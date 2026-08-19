using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para transformar DTOs a XML UBL 2.1
    /// </summary>
    public interface IXmlTransformService
    {
        string GenerarXmlFactura(FacturaDto factura);
        string GenerarXmlNotaCredito(NotaCreditoDto notaCredito);
        string GenerarXmlDocumentoSoporte(DocumentoSoporteDto documentoSoporte);
        string GenerarXmlNomina(NominaDto nomina);
    }
}
