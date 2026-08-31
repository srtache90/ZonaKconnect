using System;
using DIAN_NET.Models;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Mvc;

namespace DIAN_NET.Controllers
{
    [ApiController]
    [Route("api/v1/dian")]
    public class DianController : ControllerBase
    {
        private readonly IDianResolutionService _resolutionService;

        public DianController(IDianResolutionService resolutionService)
        {
            _resolutionService = resolutionService ?? throw new ArgumentNullException(nameof(resolutionService));
        }

        /// <summary>
        /// Consulta resoluciones DIAN (GetNumberingRange) incluyendo clave técnica, prefijo y rangos.
        /// </summary>
        [HttpGet("numbering-ranges")]
        [ProducesResponseType(typeof(DianNumberingRangeQueryResponse), 200)]
        [ProducesResponseType(typeof(object), 400)]
        public ActionResult<DianNumberingRangeQueryResponse> GetNumberingRanges(
            [FromQuery] string nit,
            [FromQuery] string softwareId,
            [FromQuery] string ambiente = "Habilitacion",
            [FromQuery] string? resolutionNumber = null,
            [FromQuery] string? prefix = null)
        {
            try
            {
                var response = _resolutionService.ConsultarResoluciones(
                    nit,
                    softwareId,
                    ambiente,
                    resolutionNumber,
                    prefix);
                return Ok(response);
            }
            catch (ArgumentException ex)
            {
                return BadRequest(new { error = ex.Message });
            }
            catch (Exception ex)
            {
                return StatusCode(502, new { error = ex.Message });
            }
        }
    }
}
