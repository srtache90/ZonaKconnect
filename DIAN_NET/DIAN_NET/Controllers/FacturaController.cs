using System.Threading.Tasks;
using DIAN_NET.Models;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Mvc;

namespace DIAN_NET.Controllers
{
    /// <summary>
    /// Controlador para el envío de facturas electrónicas a la DIAN
    /// </summary>
    [ApiController]
    [Route("api/v1/[controller]")]
    public class FacturaController : ControllerBase
    {
        private readonly IFacturacionService _facturacionService;

        public FacturaController(IFacturacionService facturacionService)
        {
            _facturacionService = facturacionService ?? throw new System.ArgumentNullException(nameof(facturacionService));
        }

        /// <summary>
        /// Envía una factura electrónica a la DIAN
        /// </summary>
        /// <param name="request">Datos de la factura a enviar</param>
        /// <returns>Respuesta del envío con el ApplicationResponse</returns>
        [HttpPost("enviar")]
        [ProducesResponseType(typeof(EnviarFacturaResponse), 200)]
        [ProducesResponseType(400)]
        [ProducesResponseType(500)]
        public async Task<ActionResult<EnviarFacturaResponse>> EnviarFactura([FromBody] EnviarFacturaRequest request)
        {
            if (request == null || request.Factura == null)
            {
                return BadRequest(new { error = "El request y la factura son requeridos" });
            }

            try
            {
                var respuesta = await _facturacionService.EnviarFacturaAsync(request);
                
                if (respuesta.Exitoso)
                {
                    return Ok(respuesta);
                }
                else
                {
                    return StatusCode(500, respuesta);
                }
            }
            catch (System.Exception ex)
            {
                return StatusCode(500, new EnviarFacturaResponse
                {
                    Exitoso = false,
                    StatusCode = "ERROR",
                    StatusDescription = "Error interno del servidor",
                    StatusMessage = ex.Message,
                    IsValid = false,
                    Errores = new[] { ex.ToString() }
                });
            }
        }
    }
}
