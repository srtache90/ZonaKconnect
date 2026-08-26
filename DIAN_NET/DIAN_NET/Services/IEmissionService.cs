using System.Threading.Tasks;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public interface IEmissionService
    {
        Task<EmitDocumentResponse> EmitInvoiceAsync(EmitInvoiceRequest request);
        Task<EmitDocumentResponse> EmitCreditNoteAsync(EmitCreditNoteRequest request);
        Task<EmitDocumentResponse> EmitDebitNoteAsync(EmitDebitNoteRequest request);
        Task<EmitDocumentResponse> EmitSupportDocumentAsync(EmitSupportDocumentRequest request);
        Task<EmitDocumentResponse> EmitPayrollAsync(EmitPayrollRequest request);
    }
}
