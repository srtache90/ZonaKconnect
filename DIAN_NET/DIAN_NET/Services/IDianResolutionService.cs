using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public interface IDianResolutionService
    {
        DianNumberingRangeQueryResponse ConsultarResoluciones(
            string nit,
            string softwareId,
            string ambiente,
            string? resolutionNumber = null,
            string? prefix = null);
    }
}
