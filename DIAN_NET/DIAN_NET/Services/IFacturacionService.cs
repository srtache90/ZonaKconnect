using DIAN_NET.Models;
using System.Threading.Tasks;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio de orquestación para el proceso completo de facturación
    /// </summary>
    public interface IFacturacionService
    {
        Task<EnviarFacturaResponse> EnviarFacturaAsync(EnviarFacturaRequest request);
        Task<EnviarFacturaResponse> EnviarNotaCreditoAsync(EnviarNotaCreditoRequest request);
        Task<EnviarFacturaResponse> EnviarNotaDebitoAsync(EnviarNotaDebitoRequest request);
        Task<EnviarFacturaResponse> EnviarXmlFacturaAsync(string xmlBase, string ambiente);
        Task<EnviarFacturaResponse> EnviarDocumentoSoporteAsync(EnviarDocumentoSoporteRequest request);
        Task<EnviarFacturaResponse> EnviarNominaAsync(EmitPayrollRequest request);
    }
}
