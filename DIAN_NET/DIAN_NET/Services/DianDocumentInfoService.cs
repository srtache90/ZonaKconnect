using System;
using System.Collections.Generic;
using System.Linq;
using DIAN_NET.DIANreference;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public sealed class DianDocumentInfoService : IDianDocumentInfoService
    {
        private readonly IDianService _dianService;

        public DianDocumentInfoService(IDianService dianService)
        {
            _dianService = dianService ?? throw new ArgumentNullException(nameof(dianService));
        }

        public DianDocumentInfoQueryResponse ConsultarEventosPorCufe(string uuid, string ambiente)
        {
            if (string.IsNullOrWhiteSpace(uuid))
            {
                throw new ArgumentException("El UUID/CUFE es requerido.", nameof(uuid));
            }

            var response = _dianService.ConsultarDocumentoInfo(uuid.Trim(), ResolveAmbiente(ambiente));
            return MapResponse(response, uuid.Trim());
        }

        private static DianDocumentInfoQueryResponse MapResponse(DocumentInfoResponse? response, string requestedUuid)
        {
            var mapped = new DianDocumentInfoQueryResponse
            {
                StatusCode = response?.StatusCode ?? "ERROR",
                StatusDescription = response?.StatusDescription ?? "Sin respuesta DIAN",
                DocumentUuid = requestedUuid
            };

            if (response?.DocumentInfo == null || response.DocumentInfo.Length == 0)
            {
                return mapped;
            }

            var events = new List<DianDocumentEventDto>();
            foreach (var document in response.DocumentInfo.Where(item => item != null))
            {
                if (!string.IsNullOrWhiteSpace(document.UUID))
                {
                    mapped.DocumentUuid = document.UUID.Trim();
                }

                if (document.Eventos == null)
                {
                    continue;
                }

                foreach (var evento in document.Eventos.Where(item => item != null))
                {
                    events.Add(MapEvent(evento));
                }
            }

            mapped.Events = events
                .GroupBy(item => $"{item.Code}|{item.EventUuid}|{item.Label}")
                .Select(group => group.First())
                .ToList();

            return mapped;
        }

        private static DianDocumentEventDto MapEvent(Evento evento)
        {
            var code = (evento.Codigo ?? string.Empty).Trim();
            var label = (evento.Descripcion ?? string.Empty).Trim();
            if (string.IsNullOrWhiteSpace(label))
            {
                label = LabelForCode(code);
            }

            return new DianDocumentEventDto
            {
                Code = code,
                Label = label,
                Estado = ResolveEstado(evento),
                EventUuid = string.IsNullOrWhiteSpace(evento.UUID) ? null : evento.UUID.Trim()
            };
        }

        private static string ResolveEstado(Evento evento)
        {
            var validation = evento.ValidacionesDoc?
                .FirstOrDefault(item => item != null && !string.IsNullOrWhiteSpace(item.Status));
            if (validation != null)
            {
                return validation.Status!.Trim();
            }

            if (evento.ValidacionesDoc?.Any(item => item != null && item.IsValida) == true)
            {
                return "VALIDADO";
            }

            return "REGISTRADO";
        }

        private static string LabelForCode(string code) => code switch
        {
            "030" or "085" => "Acuse de recibo",
            "031" or "088" => "Reclamo de factura electrónica de venta",
            "032" or "086" => "Recibo del bien y/o prestación del servicio",
            "033" or "087" => "Aceptación expresa",
            "034" => "Aceptación tácita",
            "035" => "Aval",
            "036" => "Inscripción RADIAN",
            "037" => "Endoso en propiedad",
            "041" => "Limitación de circulación",
            "042" => "Terminación de limitación",
            "043" => "Mandato",
            "044" => "Terminación del mandato",
            "045" => "Pago de la factura como título valor",
            _ => string.IsNullOrWhiteSpace(code) ? "Evento RADIAN" : $"Evento {code}"
        };

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
    }
}
