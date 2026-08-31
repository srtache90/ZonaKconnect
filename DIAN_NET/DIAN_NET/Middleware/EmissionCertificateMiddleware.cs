using DIAN_NET.Services;
using Microsoft.AspNetCore.Http;

namespace DIAN_NET.Middleware
{
    public sealed class EmissionCertificateMiddleware
    {
        private readonly RequestDelegate _next;

        public EmissionCertificateMiddleware(RequestDelegate next)
        {
            _next = next;
        }

        public async Task InvokeAsync(HttpContext context, EmissionRequestContext emissionContext)
        {
            if (context.Request.Path.StartsWithSegments("/api/v1/emit", StringComparison.OrdinalIgnoreCase)
                || context.Request.Path.StartsWithSegments("/api/v1/factura", StringComparison.OrdinalIgnoreCase)
                || context.Request.Path.StartsWithSegments("/api/v1/dian", StringComparison.OrdinalIgnoreCase))
            {
                emissionContext.TenantId = HeaderValue(context, "X-Tenant-ID");
                emissionContext.CertS3Key = HeaderValue(context, "X-Cert-S3-Key");
                emissionContext.PasswordSecretKey = HeaderValue(context, "X-Cert-Password-Secret-Key");
                emissionContext.AmbienteHeader = HeaderValue(context, "X-DIAN-Ambiente");
            }

            await _next(context);
        }

        private static string? HeaderValue(HttpContext context, string name)
        {
            if (!context.Request.Headers.TryGetValue(name, out var values))
            {
                return null;
            }

            var value = values.FirstOrDefault();
            return string.IsNullOrWhiteSpace(value) ? null : value.Trim();
        }
    }
}
