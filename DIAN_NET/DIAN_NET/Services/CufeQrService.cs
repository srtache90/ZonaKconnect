using System;
using System.Globalization;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para calcular CUFE/CUDE y generar código QR según especificaciones DIAN
    /// </summary>
    public class CufeQrService : ICufeQrService
    {
        public string CalcularCUFE(FacturaDto factura, string ambiente)
        {
            if (factura == null)
            {
                throw new ArgumentNullException(nameof(factura));
            }

            var impuestos = factura.Items?
                .SelectMany(item => item.Impuestos ?? Enumerable.Empty<ImpuestoDto>())
                .ToList() ?? new();

            var valFac = FormatMoneyTruncated(factura.Totales?.Subtotal ?? 0m);
            var valImp1 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "01").Sum(i => i.Valor));
            var valImp2 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "04").Sum(i => i.Valor));
            var valImp3 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "03").Sum(i => i.Valor));
            var valTot = FormatMoneyTruncated(factura.Totales?.Total ?? 0m);
            var tipoAmbiente = NormalizeAmbiente(factura.ConfiguracionDian?.TipoAmbiente, ambiente);

            var cadena = string.Concat(
                factura.NumeroDocumento,
                factura.FechaEmision.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                factura.FechaEmision.ToString("HH:mm:ss", CultureInfo.InvariantCulture),
                "-05:00",
                valFac,
                "01",
                valImp1,
                "04",
                valImp2,
                "03",
                valImp3,
                valTot,
                DigitsOnly(factura.Emisor?.Nit),
                DigitsOnly(factura.Cliente?.NumeroIdentificacion),
                factura.ConfiguracionDian?.ClaveTecnica ?? string.Empty,
                tipoAmbiente);

            return Sha384(cadena);
        }

        public string CalcularCUDE(NotaCreditoDto notaCredito, string ambiente)
        {
            if (notaCredito == null)
            {
                throw new ArgumentNullException(nameof(notaCredito));
            }

            var impuestos = notaCredito.Items?
                .SelectMany(item => item.Impuestos ?? Enumerable.Empty<ImpuestoDto>())
                .ToList() ?? new();

            var valFac = FormatMoneyTruncated(notaCredito.Totales?.Subtotal ?? 0m);
            var valImp1 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "01").Sum(i => i.Valor));
            var valImp2 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "04").Sum(i => i.Valor));
            var valImp3 = FormatMoneyTruncated(impuestos.Where(i => i.Codigo == "03").Sum(i => i.Valor));
            var valTot = FormatMoneyTruncated(notaCredito.Totales?.Total ?? 0m);
            var tipoAmbiente = NormalizeAmbiente(notaCredito.ConfiguracionDian?.TipoAmbiente, ambiente);

            var cadena = string.Concat(
                notaCredito.NumeroDocumento,
                notaCredito.FechaEmision.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                notaCredito.FechaEmision.ToString("HH:mm:ss", CultureInfo.InvariantCulture),
                "-05:00",
                valFac,
                "01",
                valImp1,
                "04",
                valImp2,
                "03",
                valImp3,
                valTot,
                DigitsOnly(notaCredito.Emisor?.Nit),
                DigitsOnly(notaCredito.Cliente?.NumeroIdentificacion),
                notaCredito.ConfiguracionDian?.Pin ?? string.Empty,
                tipoAmbiente);

            return Sha384(cadena);
        }

        public string CalcularCUFE(string xmlSinFirma, string numeroDocumento, DateTime fechaEmision, string tipoDocumento)
        {
            // El CUFE se calcula con SHA384 según especificaciones DIAN
            // Formato: SHA384(NumeroDocumento + FechaEmision + TipoDocumento + ...)
            
            var cadena = $"{numeroDocumento}|{fechaEmision:yyyy-MM-dd}|{tipoDocumento}|";
            
            // Agregar más datos según especificación DIAN
            // Esto es una implementación simplificada - la especificación completa requiere más campos
            
            return Sha384(cadena);
        }

        public string GenerarQRCode(string cufe, string nitEmisor, string numeroDocumento, decimal total, DateTime fechaEmision)
        {
            // Formato QR según DIAN: https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey={CUFE}
            // O formato completo con datos adicionales
            
            var qrData = $"https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey={cufe}";
            
            // Alternativamente, formato con más información:
            // qrData = $"{nitEmisor}|{numeroDocumento}|{fechaEmision:yyyy-MM-dd}|{total:F2}|{cufe}";
            
            return qrData;
        }

        private static string Sha384(string value)
        {
            using var sha384 = SHA384.Create();
            var hashBytes = sha384.ComputeHash(Encoding.UTF8.GetBytes(value));
            return BitConverter.ToString(hashBytes).Replace("-", "").ToLowerInvariant();
        }

        private static string FormatMoneyTruncated(decimal value)
        {
            var truncated = Math.Truncate(value * 100m) / 100m;
            return truncated.ToString("0.00", CultureInfo.InvariantCulture);
        }

        private static string DigitsOnly(string? value)
        {
            return string.IsNullOrWhiteSpace(value)
                ? string.Empty
                : new string(value.Where(char.IsDigit).ToArray());
        }

        private static string NormalizeAmbiente(string? tipoAmbiente, string? ambiente)
        {
            if (!string.IsNullOrWhiteSpace(tipoAmbiente))
            {
                return tipoAmbiente;
            }

            return string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase) ||
                   string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                ? "1"
                : "2";
        }
    }
}
