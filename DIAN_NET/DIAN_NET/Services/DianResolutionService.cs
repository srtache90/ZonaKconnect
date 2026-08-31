using System;
using System.Collections.Generic;
using System.Linq;
using DIAN_NET.DIANreference;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public sealed class DianResolutionService : IDianResolutionService
    {
        private readonly IDianService _dianService;

        public DianResolutionService(IDianService dianService)
        {
            _dianService = dianService ?? throw new ArgumentNullException(nameof(dianService));
        }

        public DianNumberingRangeQueryResponse ConsultarResoluciones(
            string nit,
            string softwareId,
            string ambiente,
            string? resolutionNumber = null,
            string? prefix = null)
        {
            if (string.IsNullOrWhiteSpace(nit))
            {
                throw new ArgumentException("El NIT es requerido.", nameof(nit));
            }

            if (string.IsNullOrWhiteSpace(softwareId))
            {
                throw new ArgumentException("El Software ID es requerido.", nameof(softwareId));
            }

            var nitConsulta = NormalizarNitConsulta(nit);
            var response = _dianService.ConsultarRangos(nitConsulta, softwareId.Trim(), ResolveAmbiente(ambiente));
            var mapped = MapResponse(response);
            mapped.Resolutions = FilterResolutions(mapped.Resolutions, resolutionNumber, prefix);
            return mapped;
        }

        private static string ResolveAmbiente(string? ambiente)
        {
            if (string.Equals(ambiente?.Trim(), "Produccion", StringComparison.OrdinalIgnoreCase)
                || string.Equals(ambiente?.Trim(), "Producción", StringComparison.OrdinalIgnoreCase))
            {
                return "Produccion";
            }

            if (AmbienteRoutingDianService.IsMock(ambiente))
            {
                return "Mock";
            }

            return "Habilitacion";
        }

        private static string NormalizarNitConsulta(string nit)
        {
            var digits = DianNitHelper.SoloDigitos(nit);
            if (digits.Length > 9)
            {
                return digits[..^1];
            }

            return digits;
        }

        private static DianNumberingRangeQueryResponse MapResponse(NumberRangeResponseList? response)
        {
            var result = new DianNumberingRangeQueryResponse
            {
                OperationCode = response?.OperationCode ?? string.Empty,
                OperationDescription = response?.OperationDescription ?? string.Empty
            };

            if (response?.ResponseList == null)
            {
                return result;
            }

            result.Resolutions = response.ResponseList
                .Where(item => item != null)
                .Select(item => new DianNumberingRangeDto
                {
                    ResolutionNumber = item.ResolutionNumber ?? string.Empty,
                    ResolutionDate = item.ResolutionDate ?? string.Empty,
                    Prefix = item.Prefix ?? string.Empty,
                    FromNumber = item.FromNumber,
                    ToNumber = item.ToNumber,
                    ValidDateFrom = item.ValidDateFrom ?? string.Empty,
                    ValidDateTo = item.ValidDateTo ?? string.Empty,
                    TechnicalKey = item.TechnicalKey ?? string.Empty
                })
                .ToList();

            return result;
        }

        private static List<DianNumberingRangeDto> FilterResolutions(
            List<DianNumberingRangeDto> resolutions,
            string? resolutionNumber,
            string? prefix)
        {
            IEnumerable<DianNumberingRangeDto> filtered = resolutions;

            if (!string.IsNullOrWhiteSpace(resolutionNumber))
            {
                var target = resolutionNumber.Trim();
                filtered = filtered.Where(item =>
                    string.Equals(item.ResolutionNumber?.Trim(), target, StringComparison.OrdinalIgnoreCase));
            }

            if (!string.IsNullOrWhiteSpace(prefix))
            {
                var targetPrefix = prefix.Trim();
                filtered = filtered.Where(item =>
                    string.Equals(item.Prefix?.Trim(), targetPrefix, StringComparison.OrdinalIgnoreCase));
            }

            return filtered.ToList();
        }
    }
}
