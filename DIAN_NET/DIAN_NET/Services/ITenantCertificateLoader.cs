using System.Security.Cryptography.X509Certificates;

namespace DIAN_NET.Services
{
    public interface ITenantCertificateLoader
    {
        X509Certificate2 LoadCertificate(string ambiente);
    }
}
