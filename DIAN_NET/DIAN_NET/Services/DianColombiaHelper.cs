using System;
using System.Globalization;

namespace DIAN_NET.Services
{
    internal static class DianColombiaHelper
    {
        private static readonly TimeSpan ColombiaOffset = TimeSpan.FromHours(-5);

        public static DateTimeOffset ToColombia(DateTime value)
        {
            return value.Kind switch
            {
                DateTimeKind.Utc => new DateTimeOffset(value, TimeSpan.Zero).ToOffset(ColombiaOffset),
                DateTimeKind.Local => new DateTimeOffset(value.ToUniversalTime(), TimeSpan.Zero).ToOffset(ColombiaOffset),
                // FechaEmision desde Core Go llega como hora de pared Colombia sin Kind explícito.
                _ => new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Unspecified), ColombiaOffset)
            };
        }

        public static string FormatIssueDate(DateTime value) =>
            ToColombia(value).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

        public static string FormatIssueTime(DateTime value) =>
            ToColombia(value).ToString("HH:mm:ss", CultureInfo.InvariantCulture) + "-05:00";

        public static string FormatCufeTime(DateTime value) =>
            ToColombia(value).ToString("HH:mm:ss", CultureInfo.InvariantCulture);
    }
}
