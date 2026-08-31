using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public interface IDianDocumentInfoService
    {
        DianDocumentInfoQueryResponse ConsultarEventosPorCufe(string uuid, string ambiente);
    }
}
