using System.Threading.Tasks;
using DIAN_NET.Models;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Mvc;

namespace DIAN_NET.Controllers
{
    [ApiController]
    [Route("api/v1/emit")]
    public class EmitController : ControllerBase
    {
        private readonly IEmissionService _emissionService;

        public EmitController(IEmissionService emissionService)
        {
            _emissionService = emissionService;
        }

        [HttpPost("invoice")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 400)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 502)]
        public async Task<ActionResult<EmitDocumentResponse>> EmitInvoice([FromBody] EmitInvoiceRequest request)
        {
            if (request?.Factura == null && string.IsNullOrWhiteSpace(request?.XMLBase))
            {
                return BadRequest(Failed("La factura o el XML base son requeridos."));
            }

            var response = await _emissionService.EmitInvoiceAsync(request);
            return response.Exitoso ? Ok(response) : StatusCode(502, response);
        }

        [HttpPost("credit-note")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 400)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 502)]
        public async Task<ActionResult<EmitDocumentResponse>> EmitCreditNote([FromBody] EmitCreditNoteRequest request)
        {
            if (request?.NotaCredito == null)
            {
                return BadRequest(Failed("La nota crédito es requerida."));
            }

            var response = await _emissionService.EmitCreditNoteAsync(request);
            return response.Exitoso ? Ok(response) : StatusCode(502, response);
        }

        [HttpPost("support-document")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 400)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 502)]
        public async Task<ActionResult<EmitDocumentResponse>> EmitSupportDocument([FromBody] EmitSupportDocumentRequest request)
        {
            if (request?.DocumentoSoporte == null)
            {
                return BadRequest(Failed("El documento soporte es requerido."));
            }

            var response = await _emissionService.EmitSupportDocumentAsync(request);
            return response.Exitoso ? Ok(response) : StatusCode(502, response);
        }

        [HttpPost("payroll")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 400)]
        [ProducesResponseType(typeof(EmitDocumentResponse), 502)]
        public async Task<ActionResult<EmitDocumentResponse>> EmitPayroll([FromBody] EmitPayrollRequest request)
        {
            if (request?.Nomina == null)
            {
                return BadRequest(Failed("La nómina es requerida."));
            }

            var response = await _emissionService.EmitPayrollAsync(request);
            return response.Exitoso ? Ok(response) : StatusCode(502, response);
        }

        private static EmitDocumentResponse Failed(string error)
        {
            return new EmitDocumentResponse
            {
                Status = "Fallido",
                Exitoso = false,
                StatusCode = "BAD_REQUEST",
                StatusDescription = error,
                Errores = new[] { error }
            };
        }
    }
}
