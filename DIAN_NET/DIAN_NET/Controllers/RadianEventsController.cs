using System.Threading.Tasks;
using DIAN_NET.Models;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Mvc;

namespace DIAN_NET.Controllers
{
    [ApiController]
    [Route("api/v1/events")]
    public class RadianEventsController : ControllerBase
    {
        private readonly IRadianEventService _radianEventService;

        public RadianEventsController(IRadianEventService radianEventService)
        {
            _radianEventService = radianEventService;
        }

        [HttpPost("radian")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        public async Task<ActionResult<EmitDocumentResponse>> SendRadianEvent([FromBody] RadianEventRequest request)
        {
            if (request == null)
            {
                return BadRequest(new EmitDocumentResponse
                {
                    Status = "Fallido",
                    Exitoso = false,
                    Errores = new[] { "Body requerido." }
                });
            }

            var response = await _radianEventService.EnviarEventoAsync(request);
            if (!response.Exitoso)
            {
                return BadRequest(response);
            }
            return Ok(response);
        }
    }
}
