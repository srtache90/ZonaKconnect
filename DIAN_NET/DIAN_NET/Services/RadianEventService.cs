using System;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Xml.Linq;
using DIAN_NET.DIANreference;
using DIAN_NET.Models;
using Microsoft.Extensions.Configuration;

namespace DIAN_NET.Services
{
    public interface IRadianEventService
    {
        Task<EmitDocumentResponse> EnviarEventoAsync(RadianEventRequest request);
    }

    public class RadianEventRequest
    {
        public string Ambiente { get; set; } = "Habilitacion";
        /// <summary>Código portal 085/086/087/088 o DIAN 030/032/033/031.</summary>
        public string? EventCode { get; set; }
        public string? Cufe { get; set; }
        public string? InvoiceNumber { get; set; }
        public string DocumentTypeCode { get; set; } = "01";
        public string? Motivo { get; set; }
        public string? RecibidoPor { get; set; }
        public string? DocumentoRecibidor { get; set; }
        public string? SenderNit { get; set; }
        public string? SenderName { get; set; }
        public string? SenderDv { get; set; }
        public string? ReceiverNit { get; set; }
        public string? ReceiverName { get; set; }
        public string? ReceiverDv { get; set; }
        public string? SoftwareId { get; set; }
        public string? SoftwarePin { get; set; }
        public string? ProviderNit { get; set; }
        /// <summary>PFX/P12 de la sociedad (Base64). Obligatorio fuera de Mock.</summary>
        public string? CertificatePfxBase64 { get; set; }
        public string? CertificatePassword { get; set; }
        /// <summary>Fecha de emisión de la factura referenciada (yyyy-MM-dd).</summary>
        public string? InvoiceIssueDate { get; set; }
    }

    public class RadianEventService : IRadianEventService
    {
        private static readonly XNamespace Cac = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
        private static readonly XNamespace Cbc = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
        private static readonly XNamespace Ext = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
        private static readonly XNamespace Sts = "dian:gov:co:facturaelectronica:Structures-2-1";
        private static readonly XNamespace Ar = "urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2";

        private readonly ICufeQrService _cufeQrService;
        private readonly IXadesSignService _xadesSignService;
        private readonly IDianService _dianService;
        private readonly IConfiguration _configuration;
        private readonly string _certificatePath;
        private readonly string _certificatePassword;

        public RadianEventService(
            ICufeQrService cufeQrService,
            IXadesSignService xadesSignService,
            IDianService dianService,
            IConfiguration configuration,
            string certificatePath,
            string certificatePassword)
        {
            _cufeQrService = cufeQrService;
            _xadesSignService = xadesSignService;
            _dianService = dianService;
            _configuration = configuration;
            _certificatePath = certificatePath;
            _certificatePassword = certificatePassword;
        }

        public async Task<EmitDocumentResponse> EnviarEventoAsync(RadianEventRequest request)
        {
            try
            {
                var mapped = MapEvent(request.EventCode);
                if (mapped == null)
                {
                    return Fail("EventCode inválido. Use 085/086/087/088 o 030/032/033/031.");
                }

                if (string.IsNullOrWhiteSpace(request.Cufe) || string.IsNullOrWhiteSpace(request.InvoiceNumber))
                {
                    return Fail("CUFE e InvoiceNumber son obligatorios.");
                }

                var ambiente = string.IsNullOrWhiteSpace(request.Ambiente)
                    ? (_configuration["DianConfig:Ambiente"] ?? "Habilitacion")
                    : request.Ambiente.Trim();
                var softwareId = First(request.SoftwareId, _configuration["DianConfig:Software:Id"]);
                var softwarePin = First(request.SoftwarePin, _configuration["DianConfig:Software:Pin"]);
                var providerNit = Digits(First(request.ProviderNit, _configuration["DianConfig:Software:NitEmisor"], request.SenderNit));
                var senderNit = Digits(First(request.SenderNit, _configuration["DianConfig:Software:NitEmisor"]));
                var senderName = First(request.SenderName, "Adquiriente");
                var receiverNit = Digits(request.ReceiverNit);
                var receiverName = First(request.ReceiverName, "Emisor");
                if (string.IsNullOrWhiteSpace(senderNit) || string.IsNullOrWhiteSpace(receiverNit))
                {
                    return Fail("SenderNit y ReceiverNit son obligatorios.");
                }
                if (string.IsNullOrWhiteSpace(softwareId) || string.IsNullOrWhiteSpace(softwarePin))
                {
                    return Fail("SoftwareId y SoftwarePin no están configurados.");
                }

                var colombia = TimeZoneInfo.FindSystemTimeZoneById(
                    OperatingSystem.IsWindows() ? "SA Pacific Standard Time" : "America/Bogota");
                var localNow = TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, colombia);
                // IssueDate/IssueTime y SigningTime deben coincidir (AAD09e / DC24).
                var signingTime = localNow;
                var eventId = BuildEventId(mapped.Value, signingTime);
                var cude = _cufeQrService.CalcularCUDEEvento(
                    eventId,
                    signingTime,
                    senderNit,
                    receiverNit,
                    mapped.Value.Code,
                    request.InvoiceNumber.Trim(),
                    string.IsNullOrWhiteSpace(request.DocumentTypeCode) ? "01" : request.DocumentTypeCode.Trim(),
                    softwarePin);
                var securityCode = _cufeQrService.CalcularSoftwareSecurityCode(softwareId, softwarePin, eventId);
                var profileExecution = IsProduccion(ambiente) ? "1" : "2";
                // AAB36: QR del evento usa el CUFE del documento referenciado (no el CUDE del evento).
                var qrUrl = IsProduccion(ambiente)
                    ? $"https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey={request.Cufe.Trim()}"
                    : $"https://catalogo-vpfe-hab.dian.gov.co/document/searchqr?documentkey={request.Cufe.Trim()}";

                var xmlSinFirma = BuildXml(
                    eventId,
                    cude,
                    signingTime,
                    mapped.Value,
                    request,
                    senderNit,
                    senderName,
                    First(request.SenderDv, Dv(senderNit)),
                    receiverNit,
                    receiverName,
                    First(request.ReceiverDv, Dv(receiverNit)),
                    providerNit,
                    softwareId,
                    softwarePin,
                    securityCode,
                    profileExecution,
                    qrUrl);

                var certificate = LoadCertificate(request, ambiente);
                var xmlFirmado = _xadesSignService.FirmarXml(xmlSinFirma, certificate, cude, signingTime);
                var zipName = $"ar{senderNit.PadLeft(10, '0')}000{signingTime:yy}{signingTime.Ticks % 0xFFFFFFF:x8}.xml";
                var zipBytes = CrearZip(xmlFirmado, zipName);

                DianResponse dianResponse;
                if (AmbienteRoutingDianService.IsMock(ambiente))
                {
                    dianResponse = await Task.Run(() => _dianService.EnviarEvento(zipBytes, ambiente));
                }
                else
                {
                    // Cliente WCF debe autenticarse con el mismo certificado de la sociedad.
                    using var scopedDian = new DianManager(certificate);
                    dianResponse = await Task.Run(() => scopedDian.EnviarEvento(zipBytes, ambiente));
                }
                var appResponseXml = dianResponse.XmlBytes != null && dianResponse.XmlBytes.Length > 0
                    ? Encoding.UTF8.GetString(dianResponse.XmlBytes)
                    : null;
                var exitoso = dianResponse.IsValid
                    || string.Equals(dianResponse.StatusCode, "00", StringComparison.OrdinalIgnoreCase);

                return new EmitDocumentResponse
                {
                    Status = exitoso ? "Evento RADIAN aceptado por DIAN" : "Evento RADIAN rechazado por DIAN",
                    Exitoso = exitoso,
                    CufeCune = cude,
                    CUFE = request.Cufe,
                    CUNE = cude,
                    TrackID = dianResponse.XmlDocumentKey ?? dianResponse.XmlFileName,
                    UUID = cude,
                    StatusCode = dianResponse.StatusCode,
                    StatusDescription = dianResponse.StatusDescription,
                    StatusMessage = dianResponse.StatusMessage,
                    SignedXmlBase64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(xmlFirmado)),
                    ApplicationResponseXml = appResponseXml,
                    ApplicationResponseXmlBase64 = appResponseXml == null
                        ? null
                        : Convert.ToBase64String(Encoding.UTF8.GetBytes(appResponseXml)),
                    ZipBase64 = Convert.ToBase64String(zipBytes),
                    Errores = dianResponse.ErrorMessage ?? Array.Empty<string>()
                };
            }
            catch (Exception ex)
            {
                return Fail(HumanizeDianError(ex));
            }
        }

        private X509Certificate2 LoadCertificate(RadianEventRequest request, string ambiente)
        {
            if (!string.IsNullOrWhiteSpace(request.CertificatePfxBase64))
            {
                try
                {
                    var bytes = Convert.FromBase64String(request.CertificatePfxBase64.Trim());
                    return new X509Certificate2(
                        bytes,
                        request.CertificatePassword ?? string.Empty,
                        X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
                }
                catch (Exception ex)
                {
                    throw new InvalidOperationException(
                        "No fue posible cargar el certificado PFX/P12 de la sociedad: " + ex.Message, ex);
                }
            }

            if (!AmbienteRoutingDianService.IsMock(ambiente)
                && (string.IsNullOrWhiteSpace(_certificatePath) || !File.Exists(_certificatePath)))
            {
                throw new InvalidOperationException(
                    "Ambiente " + ambiente + " requiere CertificatePfxBase64 de la sociedad (certificado digital activo).");
            }

            return new X509Certificate2(
                _certificatePath,
                _certificatePassword,
                X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
        }

        private static string HumanizeDianError(Exception ex)
        {
            var message = ex.Message ?? string.Empty;
            if (message.Contains("unsecured or incorrectly secured", StringComparison.OrdinalIgnoreCase)
                || message.Contains("FaultException", StringComparison.OrdinalIgnoreCase)
                || message.Contains("401", StringComparison.OrdinalIgnoreCase))
            {
                return "DIAN rechazó la autenticación del certificado (WS-Security). "
                    + "Verifique que el .p12 de la sociedad sea el vigente ante DIAN y la contraseña correcta. "
                    + "Detalle: " + message;
            }
            return "Error al enviar evento RADIAN a la DIAN: " + message;
        }

        private static EmitDocumentResponse Fail(string message) => new()
        {
            Status = "Fallido",
            Exitoso = false,
            Errores = new[] { message }
        };

        private static (string Code, string Description)? MapEvent(string? code)
        {
            var value = code?.Trim().ToUpperInvariant();
            return value switch
            {
                "085" or "030" => ("030", "Acuse de recibo de Factura Electrónica de Venta"),
                "086" or "032" => ("032", "Recibo del bien y/o prestación del servicio"),
                "087" or "033" => ("033", "Aceptación expresa"),
                "088" or "031" => ("031", "Reclamo de la Factura Electrónica de Venta"),
                _ => null
            };
        }

        private static string BuildEventId((string Code, string Description) mapped, DateTimeOffset now)
        {
            var prefix = mapped.Code switch
            {
                "030" => "ACR",
                "032" => "RBS",
                "033" => "AEP",
                "031" => "REC",
                _ => "EVT"
            };
            return $"{prefix}{now:yyMMddHHmmss}";
        }

        private static string BuildXml(
            string eventId,
            string cude,
            DateTimeOffset now,
            (string Code, string Description) mapped,
            RadianEventRequest request,
            string senderNit,
            string senderName,
            string senderDv,
            string receiverNit,
            string receiverName,
            string receiverDv,
            string providerNit,
            string softwareId,
            string softwarePin,
            string securityCode,
            string profileExecution,
            string qrUrl)
        {
            var colombia = TimeZoneInfo.FindSystemTimeZoneById(
                OperatingSystem.IsWindows() ? "SA Pacific Standard Time" : "America/Bogota");
            var local = TimeZoneInfo.ConvertTime(now, colombia);
            var issueDate = local.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            var issueTime = local.ToString("HH:mm:ss", CultureInfo.InvariantCulture) + "-05:00";
            var invoiceIssueDate = NormalizeInvoiceIssueDate(request.InvoiceIssueDate, issueDate);
            var cudeNote = string.Concat(
                eventId, issueDate, issueTime, senderNit, receiverNit,
                mapped.Code, request.InvoiceNumber?.Trim(),
                string.IsNullOrWhiteSpace(request.DocumentTypeCode) ? "01" : request.DocumentTypeCode.Trim(),
                softwarePin);

            var doc = new XDocument(
                new XDeclaration("1.0", "UTF-8", "no"),
                new XElement(Ar + "ApplicationResponse",
                    new XAttribute(XNamespace.Xmlns + "cac", Cac),
                    new XAttribute(XNamespace.Xmlns + "cbc", Cbc),
                    new XAttribute(XNamespace.Xmlns + "ext", Ext),
                    new XAttribute(XNamespace.Xmlns + "sts", Sts),
                    new XAttribute(XNamespace.Xmlns + "ds", "http://www.w3.org/2000/09/xmldsig#"),
                    new XAttribute(XNamespace.Xmlns + "xades", "http://uri.etsi.org/01903/v1.3.2#"),
                    new XAttribute(XNamespace.Xmlns + "xades141", "http://uri.etsi.org/01903/v1.4.1#"),
                    new XElement(Ext + "UBLExtensions",
                        new XElement(Ext + "UBLExtension",
                            new XElement(Ext + "ExtensionContent",
                                new XElement(Sts + "DianExtensions",
                                    new XElement(Sts + "InvoiceSource",
                                        new XElement(Cbc + "IdentificationCode",
                                            new XAttribute("listAgencyID", "6"),
                                            new XAttribute("listAgencyName", "United Nations Economic Commission for Europe"),
                                            new XAttribute("listSchemeURI", "urn:oasis:names:specification:ubl:codelist:gc:CountryIdentificationCode-2.1"),
                                            "CO")),
                                    new XElement(Sts + "SoftwareProvider",
                                        new XElement(Sts + "ProviderID",
                                            new XAttribute("schemeID", Dv(providerNit)),
                                            new XAttribute("schemeName", "31"),
                                            new XAttribute("schemeAgencyID", "195"),
                                            new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                                            providerNit),
                                        new XElement(Sts + "SoftwareID",
                                            new XAttribute("schemeAgencyID", "195"),
                                            new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                                            softwareId)),
                                    new XElement(Sts + "SoftwareSecurityCode",
                                        new XAttribute("schemeAgencyID", "195"),
                                        new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                                        securityCode),
                                    new XElement(Sts + "AuthorizationProvider",
                                        new XElement(Sts + "AuthorizationProviderID",
                                            new XAttribute("schemeID", "4"),
                                            new XAttribute("schemeName", "31"),
                                            new XAttribute("schemeAgencyID", "195"),
                                            new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                                            "800197268")),
                                    new XElement(Sts + "QRCode", qrUrl)))),
                        new XElement(Ext + "UBLExtension",
                            new XElement(Ext + "ExtensionContent"))),
                    new XElement(Cbc + "UBLVersionID", "UBL 2.1"),
                    new XElement(Cbc + "CustomizationID", "1"),
                    new XElement(Cbc + "ProfileID", "DIAN 2.1: ApplicationResponse de la Factura Electrónica de Venta"),
                    new XElement(Cbc + "ProfileExecutionID", profileExecution),
                    new XElement(Cbc + "ID", eventId),
                    new XElement(Cbc + "UUID",
                        new XAttribute("schemeID", profileExecution),
                        new XAttribute("schemeName", "CUDE-SHA384"),
                        cude),
                    new XElement(Cbc + "IssueDate", issueDate),
                    new XElement(Cbc + "IssueTime", issueTime),
                    new XElement(Cbc + "Note", cudeNote),
                    Party("SenderParty", senderName, senderNit, senderDv),
                    Party("ReceiverParty", receiverName, receiverNit, receiverDv),
                    new XElement(Cac + "DocumentResponse",
                        new XElement(Cac + "Response",
                            new XElement(Cbc + "ResponseCode", mapped.Code),
                            new XElement(Cbc + "Description",
                                mapped.Code == "031" && !string.IsNullOrWhiteSpace(request.Motivo)
                                    ? request.Motivo.Trim()
                                    : mapped.Description)),
                        new XElement(Cac + "DocumentReference",
                            new XElement(Cbc + "ID", request.InvoiceNumber!.Trim()),
                            new XElement(Cbc + "UUID",
                                new XAttribute("schemeName", "CUFE-SHA384"),
                                request.Cufe!.Trim()),
                            new XElement(Cbc + "IssueDate", invoiceIssueDate),
                            new XElement(Cbc + "DocumentTypeCode",
                                string.IsNullOrWhiteSpace(request.DocumentTypeCode) ? "01" : request.DocumentTypeCode.Trim())),
                        IssuerPerson(mapped.Code, request))));

            return doc.Declaration + Environment.NewLine + doc.ToString(SaveOptions.DisableFormatting);
        }

        private static XElement Party(string localName, string name, string nit, string dv) =>
            new XElement(Cac + localName,
                new XElement(Cac + "PartyTaxScheme",
                    new XElement(Cbc + "RegistrationName", XmlEscape(name)),
                    new XElement(Cbc + "CompanyID",
                        new XAttribute("schemeAgencyID", "195"),
                        new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                        new XAttribute("schemeID", dv),
                        new XAttribute("schemeName", "31"),
                        new XAttribute("schemeVersionID", "1"),
                        nit),
                    new XElement(Cac + "TaxScheme",
                        new XElement(Cbc + "ID", "01"),
                        new XElement(Cbc + "Name", "IVA"))));

        private static XElement IssuerPerson(string code, RadianEventRequest request)
        {
            // AAH11–AAH18: DocumentResponse/IssuerParty/Person (quien recibió la FEV).
            var fullName = First(request.RecibidoPor, "Operador Zona K");
            var parts = fullName.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            var first = parts.Length > 0 ? parts[0] : "Operador";
            var family = parts.Length > 1
                ? string.Join(' ', parts.Skip(1))
                : "ZonaK";
            var docType = "13"; // Cédula de ciudadanía por defecto
            var doc = Digits(First(request.DocumentoRecibidor, "222222222222"));
            if (doc.Length < 5)
            {
                doc = "222222222222";
            }

            return new XElement(Cac + "IssuerParty",
                new XElement(Cac + "Person",
                    new XElement(Cbc + "ID",
                        new XAttribute("schemeAgencyID", "195"),
                        new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                        new XAttribute("schemeName", docType),
                        doc),
                    new XElement(Cbc + "FirstName", first),
                    new XElement(Cbc + "FamilyName", family),
                    new XElement(Cbc + "JobTitle", code == "032" ? "Recibidor" : "Autorizado"),
                    new XElement(Cbc + "OrganizationDepartment", "Recepcion")));
        }

        private static byte[] CrearZip(string xmlContent, string nombreArchivo)
        {
            using var memoryStream = new MemoryStream();
            using (var archive = new ZipArchive(memoryStream, ZipArchiveMode.Create, true))
            {
                var entry = archive.CreateEntry(nombreArchivo);
                using var entryStream = entry.Open();
                using var writer = new StreamWriter(entryStream, new UTF8Encoding(false));
                writer.Write(xmlContent);
            }
            return memoryStream.ToArray();
        }

        private static string NormalizeInvoiceIssueDate(string? invoiceIssueDate, string fallbackEventDate)
        {
            if (string.IsNullOrWhiteSpace(invoiceIssueDate))
            {
                return fallbackEventDate;
            }

            var raw = invoiceIssueDate.Trim();
            if (DateTime.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var parsed)
                || DateTime.TryParse(raw, out parsed))
            {
                return parsed.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            }

            if (raw.Length >= 10 && raw[4] == '-' && raw[7] == '-')
            {
                return raw[..10];
            }

            return fallbackEventDate;
        }

        private static bool IsProduccion(string ambiente) =>
            ambiente.Equals("Produccion", StringComparison.OrdinalIgnoreCase)
            || ambiente.Equals("Producción", StringComparison.OrdinalIgnoreCase);

        private static string Digits(string? value) =>
            string.IsNullOrWhiteSpace(value) ? string.Empty : new string(value.Where(char.IsDigit).ToArray());

        private static string First(params string?[] values) =>
            values.FirstOrDefault(v => !string.IsNullOrWhiteSpace(v))?.Trim() ?? string.Empty;

        private static string XmlEscape(string value) =>
            System.Security.SecurityElement.Escape(value) ?? value;

        private static string Dv(string nit)
        {
            var digits = Digits(nit);
            if (string.IsNullOrEmpty(digits))
            {
                return "0";
            }

            int[] primes = { 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71 };
            var sum = 0;
            var p = 0;
            for (var i = digits.Length - 1; i >= 0; i--)
            {
                sum += (digits[i] - '0') * primes[p++];
            }
            var mod = sum % 11;
            return mod > 1 ? (11 - mod).ToString(CultureInfo.InvariantCulture) : mod.ToString(CultureInfo.InvariantCulture);
        }
    }
}
