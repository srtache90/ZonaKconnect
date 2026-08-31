using System;
using System.Globalization;
using System.Linq;

namespace DIAN_NET.Services
{
    internal static class DianNitHelper
    {
        private static readonly int[] VerificationWeights =
        {
            3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71
        };

        public static string SoloDigitos(string? value)
        {
            return string.IsNullOrWhiteSpace(value)
                ? string.Empty
                : new string(value.Where(char.IsDigit).ToArray());
        }

        public static string CalcularDv(string? nit)
        {
            var digits = SoloDigitos(nit);
            if (digits.Length == 0)
            {
                return "0";
            }

            var sum = 0;
            var reversed = digits.Reverse().ToArray();
            for (var i = 0; i < reversed.Length && i < VerificationWeights.Length; i++)
            {
                sum += (reversed[i] - '0') * VerificationWeights[i];
            }

            var remainder = sum % 11;
            var dv = remainder < 2 ? remainder : 11 - remainder;
            return dv.ToString(CultureInfo.InvariantCulture);
        }

        public static string NormalizarDv(string? nit, string? tipoIdentificacion, string? dvActual)
        {
            var tipo = (tipoIdentificacion ?? "31").Trim();
            if (!string.Equals(tipo, "31", StringComparison.OrdinalIgnoreCase))
            {
                return string.IsNullOrWhiteSpace(dvActual) ? "0" : dvActual.Trim();
            }

            return CalcularDv(nit);
        }

        public static string NormalizarNit(string? nit)
        {
            return SoloDigitos(nit);
        }
    }
}
