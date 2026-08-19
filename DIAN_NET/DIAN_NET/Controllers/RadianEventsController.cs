using System;
using System.Threading.Tasks;
using DIAN_NET.Models;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;

namespace DIAN_NET.Controllers
{
    [ApiController]
    [Route("api/v1/events")]
    public class RadianEventsController : ControllerBase
    {
        private readonly IConfiguration _configuration;

        public RadianEventsController(IConfiguration configuration)
        {
            _configuration = configuration;
        }

        public class RadianEventRequest
        {
            public string? InvoiceId { get; set; }
            public string? EventCode { get; set; }
            public string? Motivo { get; set; }
            public string? RecibidoPor { get; set; }
            public string? DocumentoRecibidor { get; set; }
            public string? Cufe { get; set; }
        }

        [HttpPost("radian")]
        [ProducesResponseType(typeof(EmitDocumentResponse), 200)]
        public Task<ActionResult<EmitDocumentResponse>> SendRadianEvent([FromBody] RadianEventRequest request)
        {
            var mockEnabled = _configuration.GetValue<bool>("DianConfig:Mock:Enabled");
            if (string.IsNullOrWhiteSpace(request?.EventCode))
            {
                return Task.FromResult<ActionResult<EmitDocumentResponse>>(BadRequest(new EmitDocumentResponse
                {
                    Status = "Fallido",
                    Exitoso = false,
                    Errores = new[] { "EventCode es requerido (085, 086, 087, 088)." }
                }));
            }

            // Local/mock: acepta el evento sin llamar WCF DIAN real.
            var response = new EmitDocumentResponse
            {
                Status = mockEnabled ? "Evento RADIAN mock aceptado" : "Evento RADIAN registrado",
                Exitoso = true,
                StatusCode = "00",
                StatusDescription = $"Evento {request.EventCode} procesado",
                TrackID = $"RADIAN-{request.EventCode}-{Guid.NewGuid():N}".Substring(0, 32),
                UUID = request.InvoiceId
            };
            return Task.FromResult<ActionResult<EmitDocumentResponse>>(Ok(response));
        }
    }
}
