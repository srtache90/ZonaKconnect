using System.Security.Cryptography.X509Certificates;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para firmar XML con XAdES-EPES
    /// </summary>
    public interface IXadesSignService
    {
        string FirmarXml(string xmlSinFirma, X509Certificate2 certificado, string cufe);
    }
}
