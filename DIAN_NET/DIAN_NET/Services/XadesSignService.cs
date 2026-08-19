using System;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography.Xml;
using System.Text;
using System.Xml;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para firmar XML con XAdES-EPES según especificaciones DIAN
    /// </summary>
    public class XadesSignService : IXadesSignService
    {
        public string FirmarXml(string xmlSinFirma, X509Certificate2 certificado, string cufe)
        {
            if (certificado == null)
                throw new ArgumentNullException(nameof(certificado));

            if (!certificado.HasPrivateKey)
                throw new InvalidOperationException("El certificado no tiene clave privada");

            var xmlDoc = new XmlDocument();
            xmlDoc.PreserveWhitespace = true;
            xmlDoc.LoadXml(xmlSinFirma);

            // Crear el objeto SignedXml
            var signedXml = new SignedXml(xmlDoc);
            signedXml.SigningKey = certificado.GetRSAPrivateKey();

            // Referencia al documento completo
            var reference = new Reference("");
            reference.AddTransform(new XmlDsigEnvelopedSignatureTransform());
            reference.AddTransform(new XmlDsigC14NTransform());
            reference.DigestMethod = "http://www.w3.org/2001/04/xmlenc#sha256";
            signedXml.AddReference(reference);

            // Referencia a KeyInfo
            var keyInfo = new KeyInfo();
            keyInfo.AddClause(new KeyInfoX509Data(certificado));
            signedXml.KeyInfo = keyInfo;

            // Configurar el método de firma
            signedXml.SignedInfo.SignatureMethod = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
            signedXml.SignedInfo.CanonicalizationMethod = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";

            // Calcular la firma
            signedXml.ComputeSignature();

            // Obtener el elemento de firma
            var xmlDigitalSignature = signedXml.GetXml();

            // Insertar la firma en el XML (en la segunda UBLExtension)
            var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
            nsmgr.AddNamespace("ext", "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2");
            nsmgr.AddNamespace("ds", "http://www.w3.org/2000/09/xmldsig#");

            var extensions = xmlDoc.SelectSingleNode("//ext:UBLExtensions", nsmgr);
            if (extensions != null)
            {
                var extensionNodes = extensions.SelectNodes("ext:UBLExtension", nsmgr);
                if (extensionNodes != null && extensionNodes.Count >= 2)
                {
                    var secondExtension = extensionNodes[1];
                    var extensionContent = secondExtension.SelectSingleNode("ext:ExtensionContent", nsmgr);
                    if (extensionContent != null)
                    {
                        // Importar el nodo de firma
                        var importedNode = xmlDoc.ImportNode(xmlDigitalSignature, true);
                        extensionContent.AppendChild(importedNode);
                    }
                }
            }

            return xmlDoc.OuterXml;
        }
    }
}
