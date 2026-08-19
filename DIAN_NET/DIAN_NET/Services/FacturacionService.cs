using System;
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
        private readonly string _certificatePath;
        private readonly string _certificatePassword;
        private string? _lastDebugXmlId;

        public FacturacionService(
            IXmlTransformService xmlTransformService,
            ICufeQrService cufeQrService,
            IXadesSignService xadesSignService,
            IDianService dianService,
            IDianXmlDebugStore xmlDebugStore,
            string certificatePath,
            string certificatePassword)
        {
            _xmlTransformService = xmlTransformService ?? throw new ArgumentNullException(nameof(xmlTransformService));
            _cufeQrService = cufeQrService ?? throw new ArgumentNullException(nameof(cufeQrService));
            _xadesSignService = xadesSignService ?? throw new ArgumentNullException(nameof(xadesSignService));
            _dianService = dianService ?? throw new ArgumentNullException(nameof(dianService));
            _xmlDebugStore = xmlDebugStore ?? throw new ArgumentNullException(nameof(xmlDebugStore));
            _certificatePath = certificatePath ?? throw new ArgumentNullException(nameof(certificatePath));
            _certificatePassword = certificatePassword ?? throw new ArgumentNullException(nameof(certificatePassword));
        }

        public async Task<EnviarFacturaResponse> EnviarFacturaAsync(EnviarFacturaRequest request)
        {
            try
            {
                ValidarFacturaElectronica(request.Factura, request.Ambiente);
                var xmlSinFirma = _xmlTransformService.GenerarXmlFactura(request.Factura);
                var cufe = _cufeQrService.CalcularCUFE(request.Factura, request.Ambiente);
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
                    nombreArchivo,
                    request.Ambiente,
                    "Factura",
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
                    nombreArchivo,
                    ambiente,
                    "XMLImportado",
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
                    nombreArchivo,
                    request.Ambiente,
                    "NotaCredito",
                    (zipData, zipName, ambiente) => _dianService.EnviarFactura(zipData, zipName, ambiente));
            }
            catch (Exception ex)
            {
                return CrearRespuestaError("Error al procesar la nota crédito", ex, _lastDebugXmlId);
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
                    nombreArchivo,
                    request.Ambiente,
                    "DocumentoSoporte",
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
                    nombreArchivo,
                    request.Ambiente,
                    "Nomina",
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
            string nombreArchivo,
            string ambiente,
            string documentKind,
            Func<byte[], string, string, DIAN_NET.DIANreference.DianResponse> enviar)
        {
            var xmlConIdentificador = AgregarIdentificadorXml(xmlSinFirma, identificador, schemeName, qrCode);
            var debugSnapshot = _xmlDebugStore.SaveBeforeSign(
                documentKind,
                ambiente,
                identificador,
                schemeName,
                nombreArchivo,
                xmlSinFirma,
                xmlConIdentificador);
            _lastDebugXmlId = debugSnapshot.Id;

            var certificate = new X509Certificate2(
                _certificatePath,
                _certificatePassword,
                X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
            var xmlFirmado = _xadesSignService.FirmarXml(xmlConIdentificador, certificate, identificador);
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

        private static string AgregarIdentificadorXml(string xmlSinFirma, string identificador, string schemeName, string? qrCode)
        {
            var xmlDoc = new XmlDocument();
            xmlDoc.LoadXml(xmlSinFirma);
            var nsmgr = new XmlNamespaceManager(xmlDoc.NameTable);
            nsmgr.AddNamespace("cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");
            nsmgr.AddNamespace("sts", "dian:gov:co:facturaelectronica:Structures-2-1");
            nsmgr.AddNamespace("nom", "dian:gov:co:facturaelectronica:NominaIndividual");

            var uuidNode = xmlDoc.SelectSingleNode("//cbc:UUID", nsmgr);
            var cuneNode = xmlDoc.SelectSingleNode("//nom:CUNE", nsmgr);
            var profileExecutionID = xmlDoc.SelectSingleNode("/*[local-name()='Invoice']/*[local-name()='ProfileExecutionID']")?.InnerText;
            if (string.IsNullOrWhiteSpace(profileExecutionID))
            {
                profileExecutionID = "2";
            }

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
                factura.ConfiguracionDian.TipoAmbiente = string.Equals(ambiente, "Produccion", StringComparison.OrdinalIgnoreCase) ||
                                                        string.Equals(ambiente, "Producción", StringComparison.OrdinalIgnoreCase)
                    ? "1"
                    : "2";
            }
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
                return Encoding.UTF8.GetString(respuestaDian.XmlBytes);
            }

            return respuestaDian.XmlBase64Bytes != null && respuestaDian.XmlBase64Bytes.Length > 0
                ? Encoding.UTF8.GetString(respuestaDian.XmlBase64Bytes)
                : string.Empty;
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

        private byte[] CrearZip(string xmlContent, string nombreArchivo)
        {
            using (var memoryStream = new MemoryStream())
            {
                using (var archive = new ZipArchive(memoryStream, ZipArchiveMode.Create, true))
                {
                    var entry = archive.CreateEntry(nombreArchivo);
                    using (var entryStream = entry.Open())
                    using (var writer = new StreamWriter(entryStream, Encoding.UTF8))
                    {
                        writer.Write(xmlContent);
                    }
                }
                return memoryStream.ToArray();
            }
        }
    }
}
