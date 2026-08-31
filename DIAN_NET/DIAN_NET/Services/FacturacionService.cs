using System;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Tasks;
using System.Xml;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio de orquestación para el proceso completo de facturación electrónica
    /// </summary>
    public class FacturacionService : IFacturacionService
    {
        private static readonly string[] AllowedTaxCodes =
        {
            "01", "02", "03", "04", "05", "06", "07", "20", "21", "22",
            "23", "24", "25", "26", "30", "32", "33", "34", "35", "36", "ZZ"
        };
        private static readonly string[] NominalTaxCodes = { "22", "23", "24", "25", "26", "32", "33" };

        private readonly IXmlTransformService _xmlTransformService;
        private readonly ICufeQrService _cufeQrService;
        private readonly IXadesSignService _xadesSignService;
        private readonly IDianService _dianService;
        private readonly IDianXmlDebugStore _xmlDebugStore;
        private readonly ITenantCertificateLoader _tenantCertificateLoader;
        private string? _lastDebugXmlId;

        public FacturacionService(
            IXmlTransformService xmlTransformService,
            ICufeQrService cufeQrService,
            IXadesSignService xadesSignService,
            IDianService dianService,
            IDianXmlDebugStore xmlDebugStore,
            ITenantCertificateLoader tenantCertificateLoader)
        {
            _xmlTransformService = xmlTransformService ?? throw new ArgumentNullException(nameof(xmlTransformService));
            _cufeQrService = cufeQrService ?? throw new ArgumentNullException(nameof(cufeQrService));
            _xadesSignService = xadesSignService ?? throw new ArgumentNullException(nameof(xadesSignService));
            _dianService = dianService ?? throw new ArgumentNullException(nameof(dianService));
            _xmlDebugStore = xmlDebugStore ?? throw new ArgumentNullException(nameof(xmlDebugStore));
            _tenantCertificateLoader = tenantCertificateLoader
                ?? throw new ArgumentNullException(nameof(tenantCertificateLoader));
        }

        public async Task<EnviarFacturaResponse> EnviarFacturaAsync(EnviarFacturaRequest request)
        {
            try
            {
                ValidarFacturaElectronica(request.Factura, request.Ambiente);
                SincronizarTipoAmbienteFactura(request.Factura, request.Ambiente);
                var xmlSinFirma = _xmlTransformService.GenerarXmlFactura(request.Factura);
                var cufe = _cufeQrService.CalcularCUFE(request.Factura, request.Ambiente);
                var cufeChain = _cufeQrService.ConstruirCadenaCUFE(request.Factura, request.Ambiente);
                var qrCode = _cufeQrService.GenerarQRCode(
                    cufe,
                    request.Factura.Emisor.Nit,
                    request.Factura.NumeroDocumento,
                    request.Factura.Totales.Total,
                    request.Factura.FechaEmision);

                var nombreArchivo = $"{request.Factura.TipoDocumento}_{request.Factura.Emisor.Nit}_{request.Factura.NumeroDocumento}.xml";
                return await FirmarZipYEnviarAsync(
                    xmlSinFirma,
                    cufe,
                    "CUFE-SHA384",
                    qrCode,
                    cufeChain,
                    nombreArchivo,
                    request.Ambiente,
                    "Factura",
                    DianColombiaHelper.ToColombia(request.Factura.FechaEmision),
                    (zipData, zipName, ambiente) => _dianService.EnviarFactura(zipData, zipName, ambiente));
            }
            catch (Exception ex)
            {
                return new EnviarFacturaResponse
                {
                    Exitoso = false,
                    StatusCode = "ERROR",
                    StatusDescription = "Error al procesar la factura",
                    StatusMessage = ex.Message,
                    IsValid = false,
                    DebugXmlId = _lastDebugXmlId,
                    Errores = new[] { ex.ToString() }
                };
            }
        }

        public async Task<EnviarFacturaResponse> EnviarXmlFacturaAsync(string xmlBase, string ambiente)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(xmlBase))
                {
                    throw new ArgumentException("El XML base es requerido.", nameof(xmlBase));
                }

                var xmlDoc = new XmlDocument { PreserveWhitespace = true };
                xmlDoc.LoadXml(xmlBase);

                var identificador = ExtraerIdentificadorXml(xmlDoc);
                var nombreArchivo = $"XML_IMPORTADO_{identificador}.xml";

                return await FirmarZipYEnviarAsync(
                    xmlBase,
                    identificador,
                    "CUFE-SHA384",
                    null,
                    null,
                    nombreArchivo,
                    ambiente,
                    "XMLImportado",
                    ExtraerSigningTimeXml(xmlDoc),
                    (zipData, zipName, ambienteDian) => _dianService.EnviarFactura(zipData, zipName, ambienteDian));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al firmar y enviar el XML importado", ex, _lastDebugXmlId);
            }
        }

        public async Task<EnviarFacturaResponse> EnviarNotaCreditoAsync(EnviarNotaCreditoRequest request)
        {
            try
            {
                SincronizarTipoAmbienteNotaCredito(request.NotaCredito, request.Ambiente);
                ValidarNotaCredito(request.NotaCredito, request.Ambiente);
                var xmlSinFirma = _xmlTransformService.GenerarXmlNotaCredito(request.NotaCredito);
                var cude = _cufeQrService.CalcularCUDE(request.NotaCredito, request.Ambiente);
                var qrCode = _cufeQrService.GenerarQRCode(
                    cude,
                    request.NotaCredito.Emisor.Nit,
                    request.NotaCredito.NumeroDocumento,
                    request.NotaCredito.Totales.Total,
                    request.NotaCredito.FechaEmision);

                var nombreArchivo = $"{request.NotaCredito.TipoDocumento}_{request.NotaCredito.Emisor.Nit}_{request.NotaCredito.NumeroDocumento}.xml";
                return await FirmarZipYEnviarAsync(
                    xmlSinFirma,
                    cude,
                    "CUDE-SHA384",
                    qrCode,
                    null,
                    nombreArchivo,
                    request.Ambiente,
                    "NotaCredito",
                    DianColombiaHelper.ToColombia(request.NotaCredito.FechaEmision),
                    (zipData, zipName, ambiente) => _dianService.EnviarFactura(zipData, zipName, ambiente));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al procesar la nota crédito", ex, _lastDebugXmlId);
            }
        }

        public async Task<EnviarFacturaResponse> EnviarNotaDebitoAsync(EnviarNotaDebitoRequest request)
        {
            try
            {
                SincronizarTipoAmbienteNotaDebito(request.NotaDebito, request.Ambiente);
                ValidarNotaDebito(request.NotaDebito, request.Ambiente);
                var xmlSinFirma = _xmlTransformService.GenerarXmlNotaDebito(request.NotaDebito);
                var cude = _cufeQrService.CalcularCUDE(request.NotaDebito, request.Ambiente);
                var qrCode = _cufeQrService.GenerarQRCode(
                    cude,
                    request.NotaDebito.Emisor.Nit,
                    request.NotaDebito.NumeroDocumento,
                    request.NotaDebito.Totales.Total,
                    request.NotaDebito.FechaEmision);

                var nombreArchivo = $"{request.NotaDebito.TipoDocumento}_{request.NotaDebito.Emisor.Nit}_{request.NotaDebito.NumeroDocumento}.xml";
                return await FirmarZipYEnviarAsync(
                    xmlSinFirma,
                    cude,
                    "CUDE-SHA384",
                    qrCode,
                    null,
                    nombreArchivo,
                    request.Ambiente,
                    "NotaDebito",
                    DianColombiaHelper.ToColombia(request.NotaDebito.FechaEmision),
                    (zipData, zipName, ambiente) => _dianService.EnviarFactura(zipData, zipName, ambiente));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al procesar la nota débito", ex, _lastDebugXmlId);
            }
        }

        public async Task<EnviarFacturaResponse> EnviarDocumentoSoporteAsync(EnviarDocumentoSoporteRequest request)
        {
            try
            {
                var xmlSinFirma = _xmlTransformService.GenerarXmlDocumentoSoporte(request.DocumentoSoporte);
                var cuds = _cufeQrService.CalcularCUFE(
                    xmlSinFirma,
                    request.DocumentoSoporte.NumeroDocumento,
                    request.DocumentoSoporte.FechaEmision,
                    request.DocumentoSoporte.TipoDocumento);
                var qrCode = _cufeQrService.GenerarQRCode(
                    cuds,
                    request.DocumentoSoporte.Emisor.Nit,
                    request.DocumentoSoporte.NumeroDocumento,
                    request.DocumentoSoporte.Totales.Total,
                    request.DocumentoSoporte.FechaEmision);

                var nombreArchivo = $"{request.DocumentoSoporte.TipoDocumento}_{request.DocumentoSoporte.Emisor.Nit}_{request.DocumentoSoporte.NumeroDocumento}.xml";
                return await FirmarZipYEnviarAsync(
                    xmlSinFirma,
                    cuds,
                    "CUDS-SHA384",
                    qrCode,
                    null,
                    nombreArchivo,
                    request.Ambiente,
                    "DocumentoSoporte",
                    DianColombiaHelper.ToColombia(request.DocumentoSoporte.FechaEmision),
                    (zipData, zipName, ambiente) => _dianService.EnviarFactura(zipData, zipName, ambiente));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al procesar el documento soporte", ex, _lastDebugXmlId);
            }
        }

        public async Task<EnviarFacturaResponse> EnviarNominaAsync(EmitPayrollRequest request)
        {
            try
            {
                var xmlSinFirma = _xmlTransformService.GenerarXmlNomina(request.Nomina!);
                var cune = CalcularCune(request.Nomina!);
                var nombreArchivo = $"NI_{request.Nomina!.Empleador?.Nit}_{request.Nomina.NumeroDocumento}.xml";

                return await FirmarZipYEnviarAsync(
                    xmlSinFirma,
                    cune,
                    "CUNE-SHA384",
                    null,
                    null,
                    nombreArchivo,
                    request.Ambiente,
                    "Nomina",
                    DianColombiaHelper.ToColombia(request.Nomina!.FechaEmision),
                    (zipData, _, ambiente) => _dianService.EnviarNomina(zipData, ambiente));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al procesar la nómina electrónica", ex, _lastDebugXmlId);
            }
        }

        private async Task<EnviarFacturaResponse> FirmarZipYEnviarAsync(
            string xmlSinFirma,
            string identificador,
            string schemeName,
            string? qrCode,
            string? cufeChain,
            string nombreArchivo,
            string ambiente,
            string documentKind,
            DateTimeOffset signingTime,
            Func<byte[], string, string, DIAN_NET.DIANreference.DianResponse> enviar)
        {
            var xmlConIdentificador = AgregarIdentificadorXml(xmlSinFirma, identificador, schemeName, qrCode, cufeChain);
            var debugSnapshot = _xmlDebugStore.SaveBeforeSign(
                documentKind,
                ambiente,
                identificador,
                schemeName,
                nombreArchivo,
                xmlSinFirma,
                xmlConIdentificador);
            _lastDebugXmlId = debugSnapshot.Id;

            var certificate = _tenantCertificateLoader.LoadCertificate(ambiente);
            var xmlFirmado = _xadesSignService.FirmarXml(
                xmlConIdentificador,
                certificate,
                identificador,
                signingTime);
            _xmlDebugStore.SaveSignedXml(debugSnapshot.Id, xmlFirmado);
            var zipData = CrearZip(xmlFirmado, nombreArchivo);
            var respuestaDian = await Task.Run(() => enviar(zipData, nombreArchivo + ".zip", ambiente));
            var applicationResponseXml = ObtenerApplicationResponseXml(respuestaDian);
            var applicationResponseBytes = Encoding.UTF8.GetBytes(applicationResponseXml ?? string.Empty);

            return new EnviarFacturaResponse
            {
                Exitoso = respuestaDian.IsValid,
                StatusCode = respuestaDian.StatusCode,
                StatusDescription = respuestaDian.StatusDescription,
                StatusMessage = respuestaDian.StatusMessage,
                IsValid = respuestaDian.IsValid,
                XmlDocumentKey = respuestaDian.XmlDocumentKey,
                XmlFileName = respuestaDian.XmlFileName,
                CUFE = identificador,
                QRCode = qrCode ?? string.Empty,
                DebugXmlId = debugSnapshot.Id,
                ApplicationResponseXml = applicationResponseXml,
                SignedXmlBase64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(xmlFirmado)),
                ApplicationResponseXmlBase64 = Convert.ToBase64String(applicationResponseBytes),
                ZipBase64 = Convert.ToBase64String(zipData),
                Errores = respuestaDian.ErrorMessage
            };
        }

        private static string AgregarIdentificadorXml(string xmlSinFirma, string identificador, string schemeName, string? qrCode, string? cufeChain)
        {
            var xmlDoc = new XmlDocument();
            xmlDoc.LoadXml(xmlSinFirma);
            var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
            nsmgr.AddNamespace("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");
            nsmgr.AddNamespace("sts", "dian:gov:co:facturaelectronica:Structures-2-1");
            nsmgr.AddNamespace("nom", "dian:gov:co:facturaelectronica:NominaIndividual");

            var invoiceTypeCodeNode = xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/*[local-name()='InvoiceTypeCode']", nsmgr)
                ?? xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/cbc:InvoiceTypeCode", nsmgr);
            if (!string.IsNullOrWhiteSpace(cufeChain) && invoiceTypeCodeNode != null)
            {
                var noteNode = xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/*[local-name()='Note']", nsmgr)
                    ?? xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/cbc:Note", nsmgr);
                if (noteNode != null)
                {
                    noteNode.InnerText = cufeChain;
                }
                else
                {
                    var noteElement = xmlDoc.CreateElement("cbc", "Note", nsmgr.LookupNamespace("cbc"));
                    noteElement.InnerText = cufeChain;
                    invoiceTypeCodeNode.ParentNode?.InsertAfter(noteElement, invoiceTypeCodeNode);
                }
            }

            var uuidNode = xmlDoc.SelectSingleNode("//cbc:UUID", nsmgr);
            var cuneNode = xmlDoc.SelectSingleNode("//nom:CUNE", nsmgr);
            var profileExecutionID = ExtraerProfileExecutionID(xmlDoc, nsmgr);

            if (cuneNode != null)
            {
                cuneNode.InnerText = identificador;
            }
            else if (uuidNode != null)
            {
                uuidNode.InnerText = identificador;
                AddOrSetAttribute(xmlDoc, uuidNode, "schemeID", profileExecutionID);
                AddOrSetAttribute(xmlDoc, uuidNode, "schemeName", schemeName);
                AddOrSetAttribute(xmlDoc, uuidNode, "schemeAgencyID", "195");
                AddOrSetAttribute(xmlDoc, uuidNode, "schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)");
            }
            else if (xmlDoc.DocumentElement != null)
            {
                var uuidElement = xmlDoc.CreateElement("cbc", "UUID", nsmgr.LookupNamespace("cbc"));
                uuidElement.SetAttribute("schemeID", profileExecutionID);
                uuidElement.SetAttribute("schemeName", schemeName);
                uuidElement.SetAttribute("schemeAgencyID", "195");
                uuidElement.SetAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)");
                uuidElement.InnerText = identificador;
                xmlDoc.DocumentElement.InsertAfter(uuidElement, xmlDoc.DocumentElement.SelectSingleNode("cbc:ID", nsmgr));
            }

            if (!string.IsNullOrWhiteSpace(qrCode))
            {
                var dianExtensions = xmlDoc.SelectSingleNode("//sts:DianExtensions", nsmgr);
                if (dianExtensions != null)
                {
                    var qrNode = xmlDoc.SelectSingleNode("//sts:QRCode", nsmgr);
                    if (qrNode != null)
                    {
                        qrNode.InnerText = qrCode;
                    }
                    else
                    {
                        var qrElement = xmlDoc.CreateElement("sts", "QRCode", nsmgr.LookupNamespace("sts"));
                        qrElement.InnerText = qrCode;
                        dianExtensions.AppendChild(qrElement);
                    }
                }
            }

            return xmlDoc.OuterXml;
        }

        private static void AddOrSetAttribute(XmlDocument xmlDoc, XmlNode node, string name, string value)
        {
            var attribute = node.Attributes?[name] ?? xmlDoc.CreateAttribute(name);
            attribute.Value = value;
            if (node.Attributes?[name] == null)
            {
                node.Attributes?.Append(attribute);
            }
        }

        private static void ValidarFacturaElectronica(FacturaDto factura, string ambiente)
        {
            if (factura == null)
            {
                throw new ArgumentException("La factura es requerida.", nameof(factura));
            }
            if (string.IsNullOrWhiteSpace(factura.NumeroDocumento) || factura.NumeroDocumento.Length > 20 || factura.NumeroDocumento.Any(char.IsWhiteSpace))
            {
                throw new ArgumentException("El número de factura DIAN debe tener 1 a 20 caracteres y no contener espacios.");
            }
            if (!string.Equals(factura.Moneda, "COP", StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("La factura electrónica de venta local debe usar moneda COP.");
            }
            if (factura.Items == null || factura.Items.Count == 0)
            {
                throw new ArgumentException("La factura debe tener al menos una línea.");
            }
            foreach (var item in factura.Items)
            {
                if (item.Cantidad <= 0)
                {
                    throw new ArgumentException("La cantidad de cada línea debe ser mayor a 0.00.");
                }
                if (item.PrecioUnitario < 0 || item.Descuento < 0 || item.Subtotal < 0 || item.Total < 0)
                {
                    throw new ArgumentException("Los valores monetarios de la factura no pueden ser negativos.");
                }
                ValidarImpuestos(item.Impuestos);
            }
            if (factura.ConfiguracionDian == null ||
                string.IsNullOrWhiteSpace(factura.ConfiguracionDian.NumeroResolucion) ||
                string.IsNullOrWhiteSpace(factura.ConfiguracionDian.ClaveTecnica) ||
                string.IsNullOrWhiteSpace(factura.ConfiguracionDian.SoftwareId) ||
                string.IsNullOrWhiteSpace(factura.ConfiguracionDian.Pin))
            {
                throw new ArgumentException("La configuración DIAN debe incluir resolución, clave técnica, SoftwareID y PIN.");
            }
            if (string.IsNullOrWhiteSpace(factura.ConfiguracionDian.TipoAmbiente))
            {
                SincronizarTipoAmbienteFactura(factura, ambiente);
            }
        }

        private static void SincronizarTipoAmbienteFactura(FacturaDto factura, string ambiente)
        {
            factura.ConfiguracionDian ??= new ConfiguracionDianDto();
            factura.ConfiguracionDian.TipoAmbiente =
                string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase)
                || string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
        }

        private static void SincronizarTipoAmbienteNotaCredito(NotaCreditoDto notaCredito, string ambiente)
        {
            notaCredito.ConfiguracionDian ??= new ConfiguracionDianDto();
            notaCredito.ConfiguracionDian.TipoAmbiente =
                string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase)
                || string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
        }

        private static void SincronizarTipoAmbienteNotaDebito(NotaDebitoDto notaDebito, string ambiente)
        {
            notaDebito.ConfiguracionDian ??= new ConfiguracionDianDto();
            notaDebito.ConfiguracionDian.TipoAmbiente =
                string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase)
                || string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
        }

        private static string ExtraerProfileExecutionID(XmlDocument xmlDoc, XmlNamespaceManager nsmgr)
        {
            var profileExecutionID =
                xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/*[local-name()='ProfileExecutionID']", nsmgr)?.InnerText
                ?? xmlDoc.SelectSingleNode("/*[local-name()='CreditNote']/*[local-name()='ProfileExecutionID']", nsmgr)?.InnerText
                ?? xmlDoc.SelectSingleNode("/*[local-name()='DebitNote']/*[local-name()='ProfileExecutionID']", nsmgr)?.InnerText
                ?? xmlDoc.SelectSingleNode("/*[local-name()='ApplicationResponse']/*[local-name()='ProfileExecutionID']", nsmgr)?.InnerText;

            return string.IsNullOrWhiteSpace(profileExecutionID) ? "2" : profileExecutionID.Trim();
        }

        private static void ValidarNotaCredito(NotaCreditoDto notaCredito, string ambiente)
        {
            if (notaCredito == null)
            {
                throw new ArgumentException("La nota crédito es requerida.", nameof(notaCredito));
            }
            if (string.IsNullOrWhiteSpace(notaCredito.NumeroDocumento) || notaCredito.NumeroDocumento.Length > 20 || notaCredito.NumeroDocumento.Any(char.IsWhiteSpace))
            {
                throw new ArgumentException("El número de nota crédito DIAN debe tener 1 a 20 caracteres y no contener espacios.");
            }
            if (!string.Equals(notaCredito.Moneda, "COP", StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("La nota crédito local debe usar moneda COP.");
            }
            if (notaCredito.FacturaReferencia == null ||
                string.IsNullOrWhiteSpace(notaCredito.FacturaReferencia.NumeroDocumento) ||
                string.IsNullOrWhiteSpace(notaCredito.FacturaReferencia.CUFE))
            {
                throw new ArgumentException("La nota crédito debe referenciar la factura afectada con número y CUFE.");
            }
            if (notaCredito.ConceptosCorreccion == null || notaCredito.ConceptosCorreccion.Count == 0 ||
                notaCredito.ConceptosCorreccion.Any(c => string.IsNullOrWhiteSpace(c.Codigo) || string.IsNullOrWhiteSpace(c.Descripcion)))
            {
                throw new ArgumentException("La nota crédito debe incluir concepto de corrección con código y descripción.");
            }
            if (notaCredito.Items == null || notaCredito.Items.Count == 0)
            {
                throw new ArgumentException("La nota crédito debe tener al menos una línea.");
            }
            foreach (var item in notaCredito.Items)
            {
                if (item.Cantidad <= 0)
                {
                    throw new ArgumentException("La cantidad de cada línea de la nota crédito debe ser mayor a 0.00.");
                }
                if (item.PrecioUnitario < 0 || item.Descuento < 0 || item.Subtotal < 0 || item.Total < 0)
                {
                    throw new ArgumentException("Los valores monetarios de la nota crédito no pueden ser negativos.");
                }
                ValidarImpuestos(item.Impuestos);
            }
            if (notaCredito.ConfiguracionDian == null ||
                string.IsNullOrWhiteSpace(notaCredito.ConfiguracionDian.SoftwareId) ||
                string.IsNullOrWhiteSpace(notaCredito.ConfiguracionDian.Pin))
            {
                throw new ArgumentException("La configuración DIAN de la nota crédito debe incluir SoftwareID y PIN.");
            }
            if (string.IsNullOrWhiteSpace(notaCredito.ConfiguracionDian.TipoAmbiente))
            {
                notaCredito.ConfiguracionDian.TipoAmbiente = string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase) ||
                                                            string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
            }
        }

        private static void ValidarNotaDebito(NotaDebitoDto notaDebito, string ambiente)
        {
            if (notaDebito == null)
            {
                throw new ArgumentException("La nota débito es requerida.", nameof(notaDebito));
            }
            if (string.IsNullOrWhiteSpace(notaDebito.NumeroDocumento) || notaDebito.NumeroDocumento.Length > 20 || notaDebito.NumeroDocumento.Any(char.IsWhiteSpace))
            {
                throw new ArgumentException("El número de nota débito DIAN debe tener 1 a 20 caracteres y no contener espacios.");
            }
            if (!string.Equals(notaDebito.Moneda, "COP", StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("La nota débito local debe usar moneda COP.");
            }
            if (notaDebito.FacturaReferencia == null ||
                string.IsNullOrWhiteSpace(notaDebito.FacturaReferencia.NumeroDocumento) ||
                string.IsNullOrWhiteSpace(notaDebito.FacturaReferencia.CUFE))
            {
                throw new ArgumentException("La nota débito debe referenciar la factura afectada con número y CUFE.");
            }
            if (notaDebito.ConceptosCorreccion == null || notaDebito.ConceptosCorreccion.Count == 0 ||
                notaDebito.ConceptosCorreccion.Any(c => string.IsNullOrWhiteSpace(c.Codigo) || string.IsNullOrWhiteSpace(c.Descripcion)))
            {
                throw new ArgumentException("La nota débito debe incluir concepto de corrección con código y descripción.");
            }
            if (notaDebito.Items == null || notaDebito.Items.Count == 0)
            {
                throw new ArgumentException("La nota débito debe tener al menos una línea.");
            }
            foreach (var item in notaDebito.Items)
            {
                if (item.Cantidad <= 0)
                {
                    throw new ArgumentException("La cantidad de cada línea de la nota débito debe ser mayor a 0.00.");
                }
                if (item.PrecioUnitario < 0 || item.Descuento < 0 || item.Subtotal < 0 || item.Total < 0)
                {
                    throw new ArgumentException("Los valores monetarios de la nota débito no pueden ser negativos.");
                }
                ValidarImpuestos(item.Impuestos);
            }
            if (notaDebito.ConfiguracionDian == null ||
                string.IsNullOrWhiteSpace(notaDebito.ConfiguracionDian.SoftwareId) ||
                string.IsNullOrWhiteSpace(notaDebito.ConfiguracionDian.Pin))
            {
                throw new ArgumentException("La configuración DIAN de la nota débito debe incluir SoftwareID y PIN.");
            }
            if (string.IsNullOrWhiteSpace(notaDebito.ConfiguracionDian.TipoAmbiente))
            {
                notaDebito.ConfiguracionDian.TipoAmbiente = string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase) ||
                                                            string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
            }
        }

        private static void ValidarImpuestos(System.Collections.Generic.IEnumerable<ImpuestoDto>? impuestos)
        {
            foreach (var impuesto in impuestos ?? Enumerable.Empty<ImpuestoDto>())
            {
                var codigo = string.IsNullOrWhiteSpace(impuesto.Codigo) ? "01" : impuesto.Codigo.Trim().ToUpperInvariant();
                if (!AllowedTaxCodes.Contains(codigo))
                {
                    throw new ArgumentException($"Código de impuesto DIAN no soportado: {codigo}.");
                }
                if (impuesto.Porcentaje < 0 || impuesto.BaseImponible < 0 || impuesto.Valor < 0 || impuesto.PerUnitAmount < 0 || impuesto.BaseUnitMeasure < 0)
                {
                    throw new ArgumentException("Las tarifas, bases y valores de impuestos no pueden ser negativos.");
                }

                var esNominal = NominalTaxCodes.Contains(codigo);
                if (esNominal && impuesto.PerUnitAmount <= 0 && impuesto.Valor <= 0)
                {
                    throw new ArgumentException($"El impuesto DIAN {codigo} requiere PerUnitAmount o Valor para cálculo nominal.");
                }
                if (!esNominal && codigo != "ZZ" && impuesto.Porcentaje <= 0 && impuesto.Valor <= 0)
                {
                    throw new ArgumentException($"El impuesto DIAN {codigo} requiere Porcentaje o Valor.");
                }
            }
        }

        private static string ObtenerApplicationResponseXml(DIAN_NET.DIANreference.DianResponse respuestaDian)
        {
            if (respuestaDian.XmlBytes != null && respuestaDian.XmlBytes.Length > 0)
            {
                var asText = Encoding.UTF8.GetString(respuestaDian.XmlBytes).TrimStart('\uFEFF');
                if (asText.StartsWith("<", StringComparison.Ordinal))
                {
                    return asText;
                }

                try
                {
                    var decoded = Convert.FromBase64String(asText);
                    return Encoding.UTF8.GetString(decoded).TrimStart('\uFEFF');
                }
                catch (FormatException)
                {
                    return asText;
                }
            }

            if (respuestaDian.XmlBase64Bytes != null && respuestaDian.XmlBase64Bytes.Length > 0)
            {
                try
                {
                    return Encoding.UTF8.GetString(respuestaDian.XmlBase64Bytes).TrimStart('\uFEFF');
                }
                catch
                {
                    return string.Empty;
                }
            }

            return string.Empty;
        }

        private static string CalcularCune(NominaDto nomina)
        {
            var cadena = string.Concat(
                nomina.NumeroDocumento,
                nomina.FechaEmision.ToString("yyyy-MM-dd"),
                nomina.FechaEmision.ToString("HH:mm:ss"),
                nomina.Pago?.TotalDevengados.ToString("F2"),
                nomina.Pago?.TotalDeducciones.ToString("F2"),
                nomina.Pago?.TotalComprobante.ToString("F2"),
                nomina.Empleador?.Nit,
                nomina.Trabajador?.NumeroIdentificacion,
                nomina.ConfiguracionDian?.Pin);

            var hash = SHA384.HashData(Encoding.UTF8.GetBytes(cadena));
            return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
        }

        private static DateTimeOffset ExtraerSigningTimeXml(XmlDocument xmlDoc)
        {
            var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
            nsmgr.AddNamespace("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");

            var issueDate = xmlDoc.SelectSingleNode("//*[local-name()='IssueDate']", nsmgr)?.InnerText?.Trim();
            var issueTimeRaw = xmlDoc.SelectSingleNode("//*[local-name()='IssueTime']", nsmgr)?.InnerText?.Trim();
            if (string.IsNullOrWhiteSpace(issueDate))
            {
                return DianColombiaHelper.ToColombia(DateTime.UtcNow);
            }

            var issueTime = string.IsNullOrWhiteSpace(issueTimeRaw) ? "00:00:00-05:00" : issueTimeRaw;
            if (!issueTime.Contains('T', StringComparison.Ordinal) && issueTime.Length <= 8)
            {
                issueTime += "-05:00";
            }

            if (DateTimeOffset.TryParse($"{issueDate}T{issueTime}", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed))
            {
                return parsed.ToOffset(TimeSpan.FromHours(-5));
            }

            if (DateTime.TryParse($"{issueDate} {issueTime}".Replace("-05:00", string.Empty), CultureInfo.InvariantCulture, DateTimeStyles.None, out var local))
            {
                return DianColombiaHelper.ToColombia(local);
            }

            return DianColombiaHelper.ToColombia(DateTime.UtcNow);
        }

        private static string ExtraerIdentificadorXml(XmlDocument xmlDoc)
        {
            var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
            nsmgr.AddNamespace("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");

            var uuid = xmlDoc.SelectSingleNode("//cbc:UUID", nsmgr)?.InnerText;
            if (!string.IsNullOrWhiteSpace(uuid))
            {
                return SanitizeIdentifier(uuid);
            }

            var id = xmlDoc.SelectSingleNode("//*[local-name()='consecutivo' or local-name()='ID']")?.InnerText;
            if (!string.IsNullOrWhiteSpace(id))
            {
                return SanitizeIdentifier(id);
            }

            return Guid.NewGuid().ToString("N");
        }

        private static string SanitizeIdentifier(string value)
        {
            var builder = new StringBuilder();
            foreach (var ch in value.Trim())
            {
                if (char.IsLetterOrDigit(ch) || ch == '-' || ch == '_')
                {
                    builder.Append(ch);
                }
            }

            return builder.Length > 0 ? builder.ToString() : Guid.NewGuid().ToString("N");
        }

        private static EnviarFacturaResponse CrearRespuestaError(string descripcion, Exception ex, string? debugXmlId = null)
        {
            return new EnviarFacturaResponse
            {
                Exitoso = false,
                StatusCode = "ERROR",
                StatusDescription = descripcion,
                StatusMessage = ex.Message,
                IsValid = false,
                DebugXmlId = debugXmlId,
                Errores = new[] { ex.ToString() }
            };
        }

        private static readonly UTF8Encoding Utf8NoBom = new(false);

        private byte[] CrearZip(string xmlContent, string nombreArchivo)
        {
            using (var memoryStream = new MemoryStream())
            {
                using (var archive = new ZipArchive(memoryStream, ZipArchiveMode.Create, true))
                {
                    var entry = archive.CreateEntry(nombreArchivo);
                    using (var entryStream = entry.Open())
                    using (var writer = new StreamWriter(entryStream, Utf8NoBom))
                    {
                        writer.Write(xmlContent);
                    }
                }
                return memoryStream.ToArray();
            }
        }
    }
}
