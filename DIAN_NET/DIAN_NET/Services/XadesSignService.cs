using System;
using System.IO;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Xml;
using FirmaXadesNetCore;
using FirmaXadesNetCore.Crypto;
using FirmaXadesNetCore.Signature.Parameters;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Firma XAdES-EPES alineada a política DIAN (SigningTime, SigningCertificate, SignaturePolicy).
    /// </summary>
    public class XadesSignService : IXadesSignService
    {
        private const string DianPolicyUri = "https://facturaelectronica.dian.gov.co/politicadefirma/v2/politicadefirmav2.pdf";
        // Digest SHA-1 del PDF de política v2 (anexo técnico DIAN).
        private const string DianPolicyHashSha1 = "dMoMvtcG5aIzgYo0tIsSQeVJBDnUnfSOfBpxXrmor0Y=";

        public string FirmarXml(string xmlSinFirma, X509Certificate2 certificado, string cufe)
        {
            return FirmarXml(xmlSinFirma, certificado, cufe, DateTimeOffset.Now);
        }

        public string FirmarXml(string xmlSinFirma, X509Certificate2 certificado, string cufe, DateTimeOffset signingTime)
        {
            if (string.IsNullOrWhiteSpace(xmlSinFirma))
            {
                throw new ArgumentException("XML vacío", nameof(xmlSinFirma));
            }
            if (certificado == null)
            {
                throw new ArgumentNullException(nameof(certificado));
            }
            if (!certificado.HasPrivateKey)
            {
                throw new InvalidOperationException("El certificado no tiene clave privada");
            }

            var parameters = new SignatureParameters
            {
                SignatureMethod = SignatureMethod.RSAwithSHA256,
                DigestMethod = DigestMethod.SHA256,
                SigningDate = new DateTime(
                    signingTime.Year,
                    signingTime.Month,
                    signingTime.Day,
                    signingTime.Hour,
                    signingTime.Minute,
                    signingTime.Second,
                    DateTimeKind.Unspecified),
                SignaturePackaging = SignaturePackaging.ENVELOPED,
                Signer = new Signer(certificado),
                SignaturePolicyInfo = new SignaturePolicyInfo
                {
                    PolicyIdentifier = DianPolicyUri,
                    PolicyHash = DianPolicyHashSha1,
                    PolicyDigestAlgorithm = DigestMethod.SHA1
                },
                SignatureDestination = BuildDestination(xmlSinFirma)
            };

            parameters.SignerRole = new SignerRole();
            parameters.SignerRole.ClaimedRoles.Add("supplier");

            var xadesService = new XadesService();
            using var input = new MemoryStream(Encoding.UTF8.GetBytes(xmlSinFirma));
            var signed = xadesService.Sign(input, parameters);

            using var output = new MemoryStream();
            signed.Save(output);
            return Encoding.UTF8.GetString(output.ToArray());
        }

        private static SignatureXPathExpression BuildDestination(string xmlSinFirma)
        {
            var destination = new SignatureXPathExpression();
            destination.Namespaces.Add("ext", "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2");
            destination.Namespaces.Add("cac", "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2");
            destination.Namespaces.Add("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");
            destination.Namespaces.Add("sts", "dian:gov:co:facturaelectronica:Structures-2-1");

            destination.XPathExpression = ResolveSignatureDestinationXPath(DetectRootLocalName(xmlSinFirma));
            return destination;
        }

        private static string DetectRootLocalName(string xmlSinFirma)
        {
            var doc = new XmlDocument { PreserveWhitespace = true };
            doc.LoadXml(xmlSinFirma);
            return doc.DocumentElement?.LocalName ?? string.Empty;
        }

        private static string ResolveSignatureDestinationXPath(string rootLocalName) =>
            rootLocalName switch
            {
                "ApplicationResponse" =>
                    "/*[local-name()='ApplicationResponse']/ext:UBLExtensions/ext:UBLExtension[2]/ext:ExtensionContent",
                "CreditNote" =>
                    "/*[local-name()='CreditNote']/ext:UBLExtensions/ext:UBLExtension[2]/ext:ExtensionContent",
                "DebitNote" =>
                    "/*[local-name()='DebitNote']/ext:UBLExtensions/ext:UBLExtension[2]/ext:ExtensionContent",
                "Invoice" =>
                    "/*[local-name()='Invoice']/ext:UBLExtensions/ext:UBLExtension[2]/ext:ExtensionContent",
                _ => "//ext:UBLExtensions/ext:UBLExtension[2]/ext:ExtensionContent"
            };
    }
}
