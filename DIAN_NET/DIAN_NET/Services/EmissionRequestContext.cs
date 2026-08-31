namespace DIAN_NET.Services
{
    /// <summary>Datos de certificado de la sociedad para la petición HTTP actual (scoped).</summary>
    public sealed class EmissionRequestContext
    {
        public string? TenantId { get; set; }
        public string? CertS3Key { get; set; }
        public string? PasswordSecretKey { get; set; }
        public string? CertificatePfxBase64 { get; set; }
        public string? CertificatePassword { get; set; }
        public string? AmbienteHeader { get; set; }
    }
}
