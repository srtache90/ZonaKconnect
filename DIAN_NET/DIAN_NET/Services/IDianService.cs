using DIAN_NET.DIANreference;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para interactuar con los servicios web de la DIAN
    /// </summary>
    public interface IDianService
    {
        DianResponse ConsultarEstado(string trackId, string ambiente);
        NumberRangeResponseList ConsultarRangos(string nit, string idSoftware, string ambiente);
        ConsultarEmpresaDIANResponse ConsultarEmpresaDIAN(string nit);
        DianResponse EnviarFactura(byte[] zipData, string nombreArchivo, string ambiente);
        DianResponse EnviarNomina(byte[] zipData, string ambiente);
        DianResponse EnviarEvento(byte[] zipData, string ambiente);
        DocumentInfoResponse ConsultarDocumentoInfo(string uuid, string ambiente);
    }
}
