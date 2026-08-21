using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Servicio para transformar DTOs a XML UBL 2.1 con extensiones DIAN
    /// </summary>
    public class XmlTransformService : IXmlTransformService
    {
        private const string UBL_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
        private const string CAC_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
        private const string CBC_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
        private const string EXT_NAMESPACE = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
        private const string DIAN_NAMESPACE = "dian:gov:co:facturaelectronica:Structures-2-1";
        private const string XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
        private const string XADES_NAMESPACE = "http://uri.etsi.org/01903/v1.3.2#";
        private const string DS_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#";
        private static readonly Dictionary<string, DianTaxDefinition> DianTaxes = new(StringComparer.OrdinalIgnoreCase)
        {
            ["01"] = new("IVA", false, false),
            ["02"] = new("IC", false, false),
            ["03"] = new("ICA", false, false),
            ["04"] = new("INC", false, false),
            ["05"] = new("ReteIVA", true, false),
            ["06"] = new("ReteFuente", true, false),
            ["07"] = new("ReteICA", true, false),
            ["20"] = new("FtoHorticultura", false, false),
            ["21"] = new("Timbre", false, false),
            ["22"] = new("INC Bolsas", false, true),
            ["23"] = new("INCarbono", false, true),
            ["24"] = new("INCombustibles", false, true),
            ["25"] = new("Sobretasa Combustibles", false, true),
            ["26"] = new("Sordicom", false, true),
            ["30"] = new("IC Datos", false, false),
            ["32"] = new("INPP", false, true),
            ["33"] = new("IBUA", false, true),
            ["34"] = new("ICUI", false, false),
            ["35"] = new("ICL", false, false),
            ["36"] = new("ADV", false, false),
            ["ZZ"] = new("No Aplica", false, false)
        };

        public string GenerarXmlFactura(FacturaDto factura)
        {
            NormalizarFactura(factura);
            var xml = new StringBuilder();
            xml.AppendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            
            var invoice = new XElement(XName.Get("Invoice", UBL_NAMESPACE),
                new XAttribute(XName.Get("schemaLocation", XSI_NAMESPACE), 
                    "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2 http://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/maindoc/UBL-Invoice-2.1.xsd"),
                new XAttribute(XNamespace.Xmlns + "cac", CAC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "cbc", CBC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "sts", DIAN_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xsi", XSI_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ext", EXT_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xades", XADES_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ds", DS_NAMESPACE),
                new XAttribute("xmlns", UBL_NAMESPACE)
            );

            // UBLExtensions con DianExtensions
            invoice.Add(CrearUBLExtensions(factura));

            // Información básica del documento
            invoice.Add(new XElement(XName.Get("UBLVersionID", CBC_NAMESPACE), "UBL 2.1"));
            invoice.Add(new XElement(XName.Get("CustomizationID", CBC_NAMESPACE), "10"));
            invoice.Add(new XElement(XName.Get("ProfileID", CBC_NAMESPACE), "DIAN 2.1: Factura Electrónica de Venta"));
            invoice.Add(new XElement(XName.Get("ProfileExecutionID", CBC_NAMESPACE), factura.ConfiguracionDian.TipoAmbiente));
            invoice.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), factura.NumeroDocumento));
            invoice.Add(new XElement(XName.Get("IssueDate", CBC_NAMESPACE), factura.FechaEmision.ToString("yyyy-MM-dd")));
            invoice.Add(new XElement(XName.Get("IssueTime", CBC_NAMESPACE), factura.FechaEmision.ToString("HH:mm:ss") + "-05:00"));
            invoice.Add(new XElement(XName.Get("DueDate", CBC_NAMESPACE), factura.FechaVencimiento.ToString("yyyy-MM-dd")));
            invoice.Add(new XElement(XName.Get("InvoiceTypeCode", CBC_NAMESPACE), factura.InvoiceTypeCode));
            invoice.Add(new XElement(XName.Get("DocumentCurrencyCode", CBC_NAMESPACE), factura.Moneda));

            // Notas
            if (factura.Notas != null && factura.Notas.Count > 0)
            {
                foreach (var nota in factura.Notas)
                {
                    invoice.Add(new XElement(XName.Get("Note", CBC_NAMESPACE), nota));
                }
            }

            // AccountingSupplierParty (Emisor)
            invoice.Add(CrearAccountingSupplierParty(factura.Emisor));

            // AccountingCustomerParty (Cliente)
            invoice.Add(CrearAccountingCustomerParty(factura.Cliente));

            // TaxTotal
            invoice.Add(CrearTaxTotals(factura.Items));
            invoice.Add(CrearWithholdingTaxTotals(factura.Items));

            // LegalMonetaryTotal
            invoice.Add(CrearLegalMonetaryTotal(factura.Totales));

            // InvoiceLines
            foreach (var item in factura.Items)
            {
                invoice.Add(CrearInvoiceLine(item));
            }

            invoice.Add(new XElement(XName.Get("LineCountNumeric", CBC_NAMESPACE), factura.Items.Count));

            return invoice.ToString();
        }

        private XElement CrearUBLExtensions(FacturaDto factura)
        {
            var extensions = new XElement(XName.Get("UBLExtensions", EXT_NAMESPACE));
            
            // Primera extensión: DianExtensions
            var extension1 = new XElement(XName.Get("UBLExtension", EXT_NAMESPACE));
            var extensionContent1 = new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE));
            var dianExtensions = new XElement(XName.Get("DianExtensions", DIAN_NAMESPACE));

            // InvoiceControl
            var invoiceControl = new XElement(XName.Get("InvoiceControl", DIAN_NAMESPACE));
            invoiceControl.Add(new XElement(XName.Get("InvoiceAuthorization", DIAN_NAMESPACE), 
                factura.ConfiguracionDian.NumeroResolucion));
            
            var authPeriod = new XElement(XName.Get("AuthorizationPeriod", DIAN_NAMESPACE));
            authPeriod.Add(new XElement(XName.Get("StartDate", CBC_NAMESPACE), 
                factura.ConfiguracionDian.FechaInicio.ToString("yyyy-MM-dd")));
            authPeriod.Add(new XElement(XName.Get("EndDate", CBC_NAMESPACE), 
                factura.ConfiguracionDian.FechaFin.ToString("yyyy-MM-dd")));
            invoiceControl.Add(authPeriod);

            var authorizedInvoices = new XElement(XName.Get("AuthorizedInvoices", DIAN_NAMESPACE));
            authorizedInvoices.Add(new XElement(XName.Get("Prefix", DIAN_NAMESPACE), factura.ConfiguracionDian.Prefijo));
            authorizedInvoices.Add(new XElement(XName.Get("From", DIAN_NAMESPACE), factura.ConfiguracionDian.RangoInicio));
            authorizedInvoices.Add(new XElement(XName.Get("To", DIAN_NAMESPACE), factura.ConfiguracionDian.RangoFin));
            invoiceControl.Add(authorizedInvoices);
            dianExtensions.Add(invoiceControl);

            // InvoiceSource
            var invoiceSource = new XElement(XName.Get("InvoiceSource", DIAN_NAMESPACE));
            invoiceSource.Add(new XElement(XName.Get("IdentificationCode", CBC_NAMESPACE),
                new XAttribute("listAgencyID", "6"),
                new XAttribute("listAgencyName", "United Nations Economic Commission for Europe"),
                new XAttribute("listSchemeURI", "urn:oasis:names:specification:ubl:codelist:gc:CountryIdentificationCode-2.1"),
                "CO"));
            dianExtensions.Add(invoiceSource);

            // SoftwareProvider
            var softwareProvider = new XElement(XName.Get("SoftwareProvider", DIAN_NAMESPACE));
            softwareProvider.Add(new XElement(XName.Get("ProviderID", DIAN_NAMESPACE),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                new XAttribute("schemeID", factura.Emisor.Dv),
                new XAttribute("schemeName", factura.Emisor.TipoIdentificacion),
                factura.Emisor.Nit));
            softwareProvider.Add(new XElement(XName.Get("SoftwareID", DIAN_NAMESPACE),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                factura.ConfiguracionDian.SoftwareId));
            dianExtensions.Add(softwareProvider);

            var softwareSecurityCode = new XElement(XName.Get("SoftwareSecurityCode", DIAN_NAMESPACE),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                CalcularSoftwareSecurityCode(factura.ConfiguracionDian.SoftwareId, factura.ConfiguracionDian.Pin, factura.NumeroDocumento));
            dianExtensions.Add(softwareSecurityCode);

            var authorizationProvider = new XElement(XName.Get("AuthorizationProvider", DIAN_NAMESPACE),
                new XElement(XName.Get("AuthorizationProviderID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    new XAttribute("schemeID", "4"),
                    new XAttribute("schemeName", "31"),
                    "800197268"));
            dianExtensions.Add(authorizationProvider);

            dianExtensions.Add(new XElement(XName.Get("QRCode", DIAN_NAMESPACE), string.Empty));

            extensionContent1.Add(dianExtensions);
            extension1.Add(extensionContent1);
            extensions.Add(extension1);

            // Segunda extensión: Signature (se agregará después de la firma)
            var extension2 = new XElement(XName.Get("UBLExtension", EXT_NAMESPACE));
            var extensionContent2 = new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE));
            // La firma se agregará aquí después
            extension2.Add(extensionContent2);
            extensions.Add(extension2);

            return extensions;
        }

        private XElement CrearAccountingSupplierParty(EmisorDto emisor)
        {
            var party = new XElement(XName.Get("AccountingSupplierParty", CAC_NAMESPACE));
            party.Add(new XElement(XName.Get("AdditionalAccountID", CBC_NAMESPACE), "1"));
            
            var partyElement = new XElement(XName.Get("Party", CAC_NAMESPACE));
            partyElement.Add(new XElement(XName.Get("PartyName", CAC_NAMESPACE),
                new XElement(XName.Get("Name", CBC_NAMESPACE), emisor.RazonSocial)));

            // PhysicalLocation
            var physicalLocation = new XElement(XName.Get("PhysicalLocation", CAC_NAMESPACE));
            physicalLocation.Add(CrearAddress(emisor.Direccion));
            partyElement.Add(physicalLocation);

            // PartyTaxScheme
            var partyTaxScheme = new XElement(XName.Get("PartyTaxScheme", CAC_NAMESPACE));
            partyTaxScheme.Add(new XElement(XName.Get("RegistrationName", CBC_NAMESPACE), emisor.RazonSocial));
            partyTaxScheme.Add(new XElement(XName.Get("CompanyID", CBC_NAMESPACE),
                new XAttribute("schemeID", emisor.Dv),
                new XAttribute("schemeName", emisor.TipoIdentificacion),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                emisor.Nit));
            partyTaxScheme.Add(new XElement(XName.Get("TaxLevelCode", CBC_NAMESPACE),
                new XAttribute("listName", "No Aplica"),
                emisor.RegimenFiscal));
            partyTaxScheme.Add(CrearTaxSchemeAddress(emisor.Direccion));
            partyTaxScheme.Add(new XElement(XName.Get("TaxScheme", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), emisor.TributoId),
                new XElement(XName.Get("Name", CBC_NAMESPACE), emisor.TributoNombre)));
            partyElement.Add(partyTaxScheme);

            // PartyLegalEntity
            var partyLegalEntity = new XElement(XName.Get("PartyLegalEntity", CAC_NAMESPACE));
            partyLegalEntity.Add(new XElement(XName.Get("RegistrationName", CBC_NAMESPACE), emisor.RazonSocial));
            partyLegalEntity.Add(new XElement(XName.Get("CompanyID", CBC_NAMESPACE),
                new XAttribute("schemeID", emisor.Dv),
                new XAttribute("schemeName", emisor.TipoIdentificacion),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                emisor.Nit));
            partyElement.Add(partyLegalEntity);

            // Contact
            if (!string.IsNullOrEmpty(emisor.Telefono) || !string.IsNullOrEmpty(emisor.Email))
            {
                var contact = new XElement(XName.Get("Contact", CAC_NAMESPACE));
                if (!string.IsNullOrEmpty(emisor.Telefono))
                    contact.Add(new XElement(XName.Get("Telephone", CBC_NAMESPACE), emisor.Telefono));
                if (!string.IsNullOrEmpty(emisor.Email))
                    contact.Add(new XElement(XName.Get("ElectronicMail", CBC_NAMESPACE), emisor.Email));
                partyElement.Add(contact);
            }

            party.Add(partyElement);
            return party;
        }

        private XElement CrearAccountingCustomerParty(ClienteDto cliente)
        {
            var party = new XElement(XName.Get("AccountingCustomerParty", CAC_NAMESPACE));
            party.Add(new XElement(XName.Get("AdditionalAccountID", CBC_NAMESPACE), "2"));

            var partyElement = new XElement(XName.Get("Party", CAC_NAMESPACE));
            
            // PartyIdentification
            partyElement.Add(new XElement(XName.Get("PartyIdentification", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE),
                    new XAttribute("schemeID", cliente.Dv),
                    new XAttribute("schemeName", cliente.TipoIdentificacion),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    cliente.NumeroIdentificacion)));

            partyElement.Add(new XElement(XName.Get("PartyName", CAC_NAMESPACE),
                new XElement(XName.Get("Name", CBC_NAMESPACE), cliente.RazonSocial ?? "Consumidor final")));

            // PhysicalLocation
            if (cliente.Direccion != null)
            {
                var physicalLocation = new XElement(XName.Get("PhysicalLocation", CAC_NAMESPACE));
                physicalLocation.Add(CrearAddress(cliente.Direccion));
                partyElement.Add(physicalLocation);
            }

            // PartyTaxScheme
            var partyTaxScheme = new XElement(XName.Get("PartyTaxScheme", CAC_NAMESPACE));
            partyTaxScheme.Add(new XElement(XName.Get("RegistrationName", CBC_NAMESPACE), cliente.RazonSocial ?? "Consumidor final"));
            partyTaxScheme.Add(new XElement(XName.Get("CompanyID", CBC_NAMESPACE),
                new XAttribute("schemeID", cliente.Dv),
                new XAttribute("schemeName", cliente.TipoIdentificacion),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                cliente.NumeroIdentificacion));
            partyTaxScheme.Add(new XElement(XName.Get("TaxLevelCode", CBC_NAMESPACE),
                new XAttribute("listName", "No Aplica"),
                cliente.RegimenFiscal ?? "R-99-PN"));
            partyTaxScheme.Add(new XElement(XName.Get("TaxScheme", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), cliente.TributoId),
                new XElement(XName.Get("Name", CBC_NAMESPACE), cliente.TributoNombre)));
            partyElement.Add(partyTaxScheme);

            // PartyLegalEntity
            var partyLegalEntity = new XElement(XName.Get("PartyLegalEntity", CAC_NAMESPACE));
            partyLegalEntity.Add(new XElement(XName.Get("RegistrationName", CBC_NAMESPACE), cliente.RazonSocial ?? "Consumidor final"));
            partyLegalEntity.Add(new XElement(XName.Get("CompanyID", CBC_NAMESPACE),
                new XAttribute("schemeID", cliente.Dv),
                new XAttribute("schemeName", cliente.TipoIdentificacion),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                cliente.NumeroIdentificacion));
            partyElement.Add(partyLegalEntity);

            party.Add(partyElement);
            return party;
        }

        private XElement CrearAddress(DireccionDto direccion)
        {
            var address = new XElement(XName.Get("Address", CAC_NAMESPACE));
            if (!string.IsNullOrEmpty(direccion.CodigoMunicipio))
                address.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), direccion.CodigoMunicipio));
            if (!string.IsNullOrEmpty(direccion.Municipio))
                address.Add(new XElement(XName.Get("CityName", CBC_NAMESPACE), direccion.Municipio));
            if (!string.IsNullOrEmpty(direccion.CodigoPostal))
                address.Add(new XElement(XName.Get("PostalZone", CBC_NAMESPACE), direccion.CodigoPostal));
            if (!string.IsNullOrEmpty(direccion.Departamento))
                address.Add(new XElement(XName.Get("CountrySubentity", CBC_NAMESPACE), direccion.Departamento));
            if (!string.IsNullOrEmpty(direccion.CodigoDepartamento))
                address.Add(new XElement(XName.Get("CountrySubentityCode", CBC_NAMESPACE), direccion.CodigoDepartamento));
            if (!string.IsNullOrEmpty(direccion.DireccionCompleta))
                address.Add(new XElement(XName.Get("AddressLine", CAC_NAMESPACE),
                    new XElement(XName.Get("Line", CBC_NAMESPACE), direccion.DireccionCompleta)));
            address.Add(new XElement(XName.Get("Country", CAC_NAMESPACE),
                new XElement(XName.Get("IdentificationCode", CBC_NAMESPACE), direccion.Pais ?? "CO"),
                new XElement(XName.Get("Name", CBC_NAMESPACE),
                    new XAttribute("languageID", "es"),
                    "Colombia")));
            return address;
        }

        private XElement CrearTaxSchemeAddress(DireccionDto direccion)
        {
            var address = new XElement(XName.Get("RegistrationAddress", CAC_NAMESPACE));
            if (!string.IsNullOrEmpty(direccion.CodigoMunicipio))
                address.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), direccion.CodigoMunicipio));
            if (!string.IsNullOrEmpty(direccion.Municipio))
                address.Add(new XElement(XName.Get("CityName", CBC_NAMESPACE), direccion.Municipio));
            if (!string.IsNullOrEmpty(direccion.CodigoPostal))
                address.Add(new XElement(XName.Get("PostalZone", CBC_NAMESPACE), direccion.CodigoPostal));
            if (!string.IsNullOrEmpty(direccion.Departamento))
                address.Add(new XElement(XName.Get("CountrySubentity", CBC_NAMESPACE), direccion.Departamento));
            if (!string.IsNullOrEmpty(direccion.CodigoDepartamento))
                address.Add(new XElement(XName.Get("CountrySubentityCode", CBC_NAMESPACE), direccion.CodigoDepartamento));
            if (!string.IsNullOrEmpty(direccion.DireccionCompleta))
                address.Add(new XElement(XName.Get("AddressLine", CAC_NAMESPACE),
                    new XElement(XName.Get("Line", CBC_NAMESPACE), direccion.DireccionCompleta)));
            address.Add(new XElement(XName.Get("Country", CAC_NAMESPACE),
                new XElement(XName.Get("IdentificationCode", CBC_NAMESPACE), direccion.Pais ?? "CO"),
                new XElement(XName.Get("Name", CBC_NAMESPACE),
                    new XAttribute("languageID", "es"),
                    "Colombia")));
            return address;
        }

        private List<XElement> CrearTaxTotals(List<ItemDto> items)
        {
            var impuestos = (items ?? new List<ItemDto>())
                .SelectMany(i => i.Impuestos ?? new List<ImpuestoDto>())
                .Where(i => !i.EsRetencion)
                .ToList();

            return impuestos
                .GroupBy(i => NormalizarCodigoTributo(i.Codigo))
                .Select(grupoTributo =>
                {
                    var taxTotal = new XElement(XName.Get("TaxTotal", CAC_NAMESPACE));
                    taxTotal.Add(new XElement(XName.Get("TaxAmount", CBC_NAMESPACE),
                        new XAttribute("currencyID", "COP"),
                        grupoTributo.Sum(i => i.Valor).ToString("F2", CultureInfo.InvariantCulture)));

                    foreach (var grupoTarifa in grupoTributo.GroupBy(i => new
                    {
                        Codigo = NormalizarCodigoTributo(i.Codigo),
                        i.Porcentaje,
                        i.PerUnitAmount,
                        i.UnitCode
                    }))
                    {
                        taxTotal.Add(CrearTaxSubtotal(grupoTarifa.ToList()));
                    }

                    return taxTotal;
                })
                .ToList();
        }

        private List<XElement> CrearWithholdingTaxTotals(List<ItemDto> items)
        {
            var retenciones = (items ?? new List<ItemDto>())
                .SelectMany(i => i.Impuestos ?? new List<ImpuestoDto>())
                .Where(i => i.EsRetencion)
                .ToList();

            return retenciones
                .GroupBy(i => NormalizarCodigoTributo(i.Codigo))
                .Select(grupoTributo =>
                {
                    var withholdingTaxTotal = new XElement(XName.Get("WithholdingTaxTotal", CAC_NAMESPACE));
                    withholdingTaxTotal.Add(new XElement(XName.Get("TaxAmount", CBC_NAMESPACE),
                        new XAttribute("currencyID", "COP"),
                        grupoTributo.Sum(i => i.Valor).ToString("F2", CultureInfo.InvariantCulture)));

                    foreach (var grupoTarifa in grupoTributo.GroupBy(i => new
                    {
                        Codigo = NormalizarCodigoTributo(i.Codigo),
                        i.Porcentaje
                    }))
                    {
                        withholdingTaxTotal.Add(CrearTaxSubtotal(grupoTarifa.ToList()));
                    }

                    return withholdingTaxTotal;
                })
                .ToList();
        }

        private XElement CrearTaxSubtotal(List<ImpuestoDto> impuestos)
        {
            var first = impuestos.First();
            var codigo = NormalizarCodigoTributo(first.Codigo);
            var definition = ObtenerDefinicionTributo(codigo);
            var baseImponible = impuestos.Sum(i => i.BaseImponible);
            var valor = impuestos.Sum(i => i.Valor);
            var taxSubtotal = new XElement(XName.Get("TaxSubtotal", CAC_NAMESPACE));

            taxSubtotal.Add(new XElement(XName.Get("TaxableAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                baseImponible.ToString("F2", CultureInfo.InvariantCulture)));
            taxSubtotal.Add(new XElement(XName.Get("TaxAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                valor.ToString("F2", CultureInfo.InvariantCulture)));

            if (definition.EsNominal || first.PerUnitAmount > 0)
            {
                taxSubtotal.Add(new XElement(XName.Get("BaseUnitMeasure", CBC_NAMESPACE),
                    new XAttribute("unitCode", Default(first.UnitCode, "94")),
                    (first.BaseUnitMeasure > 0 ? first.BaseUnitMeasure : baseImponible).ToString("F2", CultureInfo.InvariantCulture)));
                taxSubtotal.Add(new XElement(XName.Get("PerUnitAmount", CBC_NAMESPACE),
                    new XAttribute("currencyID", "COP"),
                    first.PerUnitAmount.ToString("F2", CultureInfo.InvariantCulture)));
            }

            var taxCategory = new XElement(XName.Get("TaxCategory", CAC_NAMESPACE));
            if (!definition.EsNominal || first.Porcentaje > 0)
            {
                taxCategory.Add(new XElement(XName.Get("Percent", CBC_NAMESPACE), first.Porcentaje.ToString("F2", CultureInfo.InvariantCulture)));
            }
            taxCategory.Add(new XElement(XName.Get("TaxScheme", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), codigo),
                new XElement(XName.Get("Name", CBC_NAMESPACE), definition.Nombre)));
            taxSubtotal.Add(taxCategory);

            return taxSubtotal;
        }

        private XElement CrearLegalMonetaryTotal(TotalesDto totales)
        {
            var monetaryTotal = new XElement(XName.Get("LegalMonetaryTotal", CAC_NAMESPACE));
            monetaryTotal.Add(new XElement(XName.Get("LineExtensionAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                totales.Subtotal.ToString("F2", CultureInfo.InvariantCulture)));
            monetaryTotal.Add(new XElement(XName.Get("TaxExclusiveAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                totales.Subtotal.ToString("F2", CultureInfo.InvariantCulture)));
            monetaryTotal.Add(new XElement(XName.Get("TaxInclusiveAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                totales.Total.ToString("F2", CultureInfo.InvariantCulture)));
            monetaryTotal.Add(new XElement(XName.Get("PayableAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                totales.Total.ToString("F2", CultureInfo.InvariantCulture)));
            return monetaryTotal;
        }

        private XElement CrearInvoiceLine(ItemDto item)
        {
            var invoiceLine = new XElement(XName.Get("InvoiceLine", CAC_NAMESPACE));
            invoiceLine.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), item.NumeroLinea));
            invoiceLine.Add(new XElement(XName.Get("InvoicedQuantity", CBC_NAMESPACE),
                new XAttribute("unitCode", item.UnidadMedida),
                item.Cantidad.ToString("F2", CultureInfo.InvariantCulture)));
            invoiceLine.Add(new XElement(XName.Get("LineExtensionAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                item.Subtotal.ToString("F2", CultureInfo.InvariantCulture)));

            // TaxTotal para la línea
            if (item.Impuestos != null && item.Impuestos.Count > 0)
            {
                invoiceLine.Add(CrearTaxTotals(new List<ItemDto> { item }));
                invoiceLine.Add(CrearWithholdingTaxTotals(new List<ItemDto> { item }));
            }

            // Item
            var itemElement = new XElement(XName.Get("Item", CAC_NAMESPACE));
            itemElement.Add(new XElement(XName.Get("Description", CBC_NAMESPACE), item.Descripcion));
            itemElement.Add(new XElement(XName.Get("SellersItemIdentification", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), item.Codigo),
                new XElement(XName.Get("ExtendedID", CBC_NAMESPACE), item.Codigo)));
            itemElement.Add(new XElement(XName.Get("StandardItemIdentification", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE),
                    new XAttribute("schemeID", "999"),
                    new XAttribute("schemeName", "Estándar de adopción del contribuyente"),
                    item.Codigo)));
            invoiceLine.Add(itemElement);

            // Price
            invoiceLine.Add(new XElement(XName.Get("Price", CAC_NAMESPACE),
                new XElement(XName.Get("PriceAmount", CBC_NAMESPACE),
                    new XAttribute("currencyID", "COP"),
                    item.PrecioUnitario.ToString("F2", CultureInfo.InvariantCulture)),
                new XElement(XName.Get("BaseQuantity", CBC_NAMESPACE),
                    new XAttribute("unitCode", item.UnidadMedida),
                    "1.00")));

            return invoiceLine;
        }

        private static void NormalizarFactura(FacturaDto factura)
        {
            if (factura == null)
            {
                throw new ArgumentNullException(nameof(factura));
            }

            factura.InvoiceTypeCode = Default(factura.InvoiceTypeCode, "01");
            factura.Moneda = Default(factura.Moneda, "COP");
            factura.ConfiguracionDian ??= new ConfiguracionDianDto();
            factura.ConfiguracionDian.TipoAmbiente = Default(factura.ConfiguracionDian.TipoAmbiente, "2");

            factura.Emisor ??= new EmisorDto();
            factura.Emisor.TipoIdentificacion = Default(factura.Emisor.TipoIdentificacion, "31");
            factura.Emisor.TipoPersona = Default(factura.Emisor.TipoPersona, "1");
            factura.Emisor.Dv = Default(factura.Emisor.Dv, "0");
            factura.Emisor.RegimenFiscal = Default(factura.Emisor.RegimenFiscal, "R-99-PN");
            factura.Emisor.TributoId = Default(factura.Emisor.TributoId, "01");
            factura.Emisor.TributoNombre = Default(factura.Emisor.TributoNombre, NombreTributo(factura.Emisor.TributoId));
            factura.Emisor.Direccion = NormalizarDireccion(factura.Emisor.Direccion);

            factura.Cliente ??= new ClienteDto();
            factura.Cliente.TipoIdentificacion = Default(factura.Cliente.TipoIdentificacion, "31");
            factura.Cliente.TipoPersona = Default(factura.Cliente.TipoPersona, "1");
            factura.Cliente.Dv = Default(factura.Cliente.Dv, "0");
            factura.Cliente.RegimenFiscal = Default(factura.Cliente.RegimenFiscal, "R-99-PN");
            factura.Cliente.TributoId = Default(factura.Cliente.TributoId, "ZZ");
            factura.Cliente.TributoNombre = Default(factura.Cliente.TributoNombre, NombreTributo(factura.Cliente.TributoId));
            factura.Cliente.Direccion = NormalizarDireccion(factura.Cliente.Direccion);

            foreach (var item in factura.Items ?? Enumerable.Empty<ItemDto>())
            {
                item.UnidadMedida = Default(item.UnidadMedida, "94");
                foreach (var impuesto in item.Impuestos ?? Enumerable.Empty<ImpuestoDto>())
                {
                    NormalizarImpuesto(impuesto, item);
                }
            }
        }

        private static DireccionDto NormalizarDireccion(DireccionDto direccion)
        {
            direccion ??= new DireccionDto();
            direccion.CodigoMunicipio = Default(direccion.CodigoMunicipio, "11001");
            direccion.Municipio = Default(direccion.Municipio, "Bogotá");
            direccion.CodigoPostal = Default(direccion.CodigoPostal, "110111");
            direccion.Departamento = Default(direccion.Departamento, "Bogotá D.C.");
            direccion.CodigoDepartamento = Default(direccion.CodigoDepartamento, "11");
            direccion.DireccionCompleta = Default(direccion.DireccionCompleta, "Dirección no informada");
            direccion.Pais = Default(direccion.Pais, "CO");
            return direccion;
        }

        private static string CalcularSoftwareSecurityCode(string softwareId, string pin, string numeroDocumento)
        {
            using var sha384 = SHA384.Create();
            var value = string.Concat(softwareId ?? string.Empty, pin ?? string.Empty, numeroDocumento ?? string.Empty);
            var hashBytes = sha384.ComputeHash(Encoding.UTF8.GetBytes(value));
            return BitConverter.ToString(hashBytes).Replace("-", "").ToLowerInvariant();
        }

        private static string NombreTributo(string codigo)
        {
            return ObtenerDefinicionTributo(codigo).Nombre;
        }

        private static void NormalizarImpuesto(ImpuestoDto impuesto, ItemDto item)
        {
            impuesto.Codigo = NormalizarCodigoTributo(impuesto.Codigo);
            var definition = ObtenerDefinicionTributo(impuesto.Codigo);
            impuesto.Nombre = Default(impuesto.Nombre, definition.Nombre);
            impuesto.Tipo = definition.EsNominal ? "Nominal" : "Porcentual";
            impuesto.EsRetencion = impuesto.EsRetencion || definition.EsRetencion;

            if (impuesto.BaseImponible <= 0)
            {
                impuesto.BaseImponible = item.Subtotal > 0
                    ? item.Subtotal
                    : Math.Max(0, (item.Cantidad * item.PrecioUnitario) - item.Descuento);
            }

            if (definition.EsNominal)
            {
                impuesto.UnitCode = Default(impuesto.UnitCode, item.UnidadMedida);
                if (impuesto.BaseUnitMeasure <= 0)
                {
                    impuesto.BaseUnitMeasure = item.Cantidad > 0 ? item.Cantidad : impuesto.BaseImponible;
                }
                if (impuesto.Valor <= 0 && impuesto.PerUnitAmount > 0)
                {
                    impuesto.Valor = impuesto.BaseUnitMeasure * impuesto.PerUnitAmount;
                }
            }
            else if (impuesto.Valor <= 0 && impuesto.Porcentaje > 0)
            {
                impuesto.Valor = Math.Round(impuesto.BaseImponible * impuesto.Porcentaje / 100m, 2, MidpointRounding.AwayFromZero);
            }
        }

        private static string NormalizarCodigoTributo(string codigo)
        {
            codigo = Default(codigo, "01").Trim().ToUpperInvariant();
            return DianTaxes.ContainsKey(codigo) ? codigo : "ZZ";
        }

        private static DianTaxDefinition ObtenerDefinicionTributo(string codigo)
        {
            codigo = NormalizarCodigoTributo(codigo);
            return DianTaxes[codigo];
        }

        private static string Default(string value, string fallback)
        {
            return string.IsNullOrWhiteSpace(value) ? fallback : value;
        }

        private sealed record DianTaxDefinition(string Nombre, bool EsRetencion, bool EsNominal);

        private static void NormalizarNotaCredito(NotaCreditoDto notaCredito)
        {
            if (notaCredito == null)
            {
                throw new ArgumentNullException(nameof(notaCredito));
            }

            notaCredito.CustomizationID = Default(notaCredito.CustomizationID, "20");
            notaCredito.CreditNoteTypeCode = Default(notaCredito.CreditNoteTypeCode, "91");
            notaCredito.Moneda = Default(notaCredito.Moneda, "COP");
            notaCredito.ConfiguracionDian ??= new ConfiguracionDianDto();
            notaCredito.ConfiguracionDian.TipoAmbiente = Default(notaCredito.ConfiguracionDian.TipoAmbiente, "2");

            notaCredito.Emisor ??= new EmisorDto();
            notaCredito.Emisor.TipoIdentificacion = Default(notaCredito.Emisor.TipoIdentificacion, "31");
            notaCredito.Emisor.TipoPersona = Default(notaCredito.Emisor.TipoPersona, "1");
            notaCredito.Emisor.Dv = Default(notaCredito.Emisor.Dv, "0");
            notaCredito.Emisor.RegimenFiscal = Default(notaCredito.Emisor.RegimenFiscal, "R-99-PN");
            notaCredito.Emisor.TributoId = Default(notaCredito.Emisor.TributoId, "01");
            notaCredito.Emisor.TributoNombre = Default(notaCredito.Emisor.TributoNombre, NombreTributo(notaCredito.Emisor.TributoId));
            notaCredito.Emisor.Direccion = NormalizarDireccion(notaCredito.Emisor.Direccion);

            notaCredito.Cliente ??= new ClienteDto();
            notaCredito.Cliente.TipoIdentificacion = Default(notaCredito.Cliente.TipoIdentificacion, "31");
            notaCredito.Cliente.TipoPersona = Default(notaCredito.Cliente.TipoPersona, "1");
            notaCredito.Cliente.Dv = Default(notaCredito.Cliente.Dv, "0");
            notaCredito.Cliente.RegimenFiscal = Default(notaCredito.Cliente.RegimenFiscal, "R-99-PN");
            notaCredito.Cliente.TributoId = Default(notaCredito.Cliente.TributoId, "ZZ");
            notaCredito.Cliente.TributoNombre = Default(notaCredito.Cliente.TributoNombre, NombreTributo(notaCredito.Cliente.TributoId));
            notaCredito.Cliente.Direccion = NormalizarDireccion(notaCredito.Cliente.Direccion);

            notaCredito.FacturaReferencia ??= new ReferenciaDocumentoDto();
            notaCredito.FacturaReferencia.SchemeName = Default(notaCredito.FacturaReferencia.SchemeName, "CUFE-SHA384");

            foreach (var concepto in notaCredito.ConceptosCorreccion ?? Enumerable.Empty<ConceptoCorreccionDto>())
            {
                concepto.ReferenceID = Default(concepto.ReferenceID, notaCredito.FacturaReferencia.NumeroDocumento);
                concepto.Codigo = Default(concepto.Codigo, "1");
                concepto.Descripcion = Default(concepto.Descripcion, "Devolución parcial de los bienes y/o no aceptación parcial del servicio");
            }

            foreach (var item in notaCredito.Items ?? Enumerable.Empty<ItemDto>())
            {
                item.UnidadMedida = Default(item.UnidadMedida, "94");
                foreach (var impuesto in item.Impuestos ?? Enumerable.Empty<ImpuestoDto>())
                {
                    NormalizarImpuesto(impuesto, item);
                }
            }
        }

        public string GenerarXmlNotaCredito(NotaCreditoDto notaCredito)
        {
            NormalizarNotaCredito(notaCredito);

            var creditNote = new XElement(XName.Get("CreditNote", "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"),
                new XAttribute(XName.Get("schemaLocation", XSI_NAMESPACE),
                    "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2 http://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/maindoc/UBL-CreditNote-2.1.xsd"),
                new XAttribute(XNamespace.Xmlns + "cac", CAC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "cbc", CBC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "sts", DIAN_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xsi", XSI_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ext", EXT_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xades", XADES_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ds", DS_NAMESPACE),
                new XAttribute("xmlns", "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"));

            creditNote.Add(CrearUBLExtensionsNotaCredito(notaCredito));
            creditNote.Add(new XElement(XName.Get("UBLVersionID", CBC_NAMESPACE), "UBL 2.1"));
            creditNote.Add(new XElement(XName.Get("CustomizationID", CBC_NAMESPACE), notaCredito.CustomizationID));
            creditNote.Add(new XElement(XName.Get("ProfileID", CBC_NAMESPACE),
                notaCredito.CustomizationID == "24"
                    ? "DIAN 2.1: Nota de Ajuste para Factura Electrónica de Venta Aceptada"
                    : "DIAN 2.1: Nota Crédito de Factura Electrónica de Venta"));
            creditNote.Add(new XElement(XName.Get("ProfileExecutionID", CBC_NAMESPACE), notaCredito.ConfiguracionDian.TipoAmbiente));
            creditNote.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), notaCredito.NumeroDocumento));
            creditNote.Add(new XElement(XName.Get("UUID", CBC_NAMESPACE),
                new XAttribute("schemeID", notaCredito.ConfiguracionDian.TipoAmbiente),
                new XAttribute("schemeName", "CUDE-SHA384"),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                string.Empty));
            creditNote.Add(new XElement(XName.Get("IssueDate", CBC_NAMESPACE), notaCredito.FechaEmision.ToString("yyyy-MM-dd")));
            creditNote.Add(new XElement(XName.Get("IssueTime", CBC_NAMESPACE), notaCredito.FechaEmision.ToString("HH:mm:ss") + "-05:00"));
            creditNote.Add(new XElement(XName.Get("CreditNoteTypeCode", CBC_NAMESPACE), notaCredito.CreditNoteTypeCode));

            foreach (var nota in notaCredito.Notas ?? Enumerable.Empty<string>())
            {
                creditNote.Add(new XElement(XName.Get("Note", CBC_NAMESPACE), nota));
            }

            creditNote.Add(new XElement(XName.Get("DocumentCurrencyCode", CBC_NAMESPACE), notaCredito.Moneda));
            creditNote.Add(new XElement(XName.Get("LineCountNumeric", CBC_NAMESPACE), notaCredito.Items.Count));
            creditNote.Add(CrearDiscrepancyResponse(notaCredito));
            creditNote.Add(CrearBillingReferenceNotaCredito(notaCredito.FacturaReferencia));
            creditNote.Add(CrearAccountingSupplierParty(notaCredito.Emisor));
            creditNote.Add(CrearAccountingCustomerParty(notaCredito.Cliente));
            creditNote.Add(CrearTaxTotals(notaCredito.Items));
            creditNote.Add(CrearWithholdingTaxTotals(notaCredito.Items));
            creditNote.Add(CrearLegalMonetaryTotal(notaCredito.Totales));

            foreach (var item in notaCredito.Items)
            {
                creditNote.Add(CrearCreditNoteLine(item));
            }

            return creditNote.ToString();
        }

        private static void NormalizarNotaDebito(NotaDebitoDto notaDebito)
        {
            if (notaDebito == null)
            {
                throw new ArgumentNullException(nameof(notaDebito));
            }

            notaDebito.CustomizationID = Default(notaDebito.CustomizationID, "30");
            notaDebito.DebitNoteTypeCode = Default(notaDebito.DebitNoteTypeCode, "92");
            notaDebito.Moneda = Default(notaDebito.Moneda, "COP");
            notaDebito.ConfiguracionDian ??= new ConfiguracionDianDto();
            notaDebito.ConfiguracionDian.TipoAmbiente = Default(notaDebito.ConfiguracionDian.TipoAmbiente, "2");

            notaDebito.Emisor ??= new EmisorDto();
            notaDebito.Emisor.TipoIdentificacion = Default(notaDebito.Emisor.TipoIdentificacion, "31");
            notaDebito.Emisor.TipoPersona = Default(notaDebito.Emisor.TipoPersona, "1");
            notaDebito.Emisor.Dv = Default(notaDebito.Emisor.Dv, "0");
            notaDebito.Emisor.RegimenFiscal = Default(notaDebito.Emisor.RegimenFiscal, "R-99-PN");
            notaDebito.Emisor.TributoId = Default(notaDebito.Emisor.TributoId, "01");
            notaDebito.Emisor.TributoNombre = Default(notaDebito.Emisor.TributoNombre, NombreTributo(notaDebito.Emisor.TributoId));
            notaDebito.Emisor.Direccion = NormalizarDireccion(notaDebito.Emisor.Direccion);

            notaDebito.Cliente ??= new ClienteDto();
            notaDebito.Cliente.TipoIdentificacion = Default(notaDebito.Cliente.TipoIdentificacion, "31");
            notaDebito.Cliente.TipoPersona = Default(notaDebito.Cliente.TipoPersona, "1");
            notaDebito.Cliente.Dv = Default(notaDebito.Cliente.Dv, "0");
            notaDebito.Cliente.RegimenFiscal = Default(notaDebito.Cliente.RegimenFiscal, "R-99-PN");
            notaDebito.Cliente.TributoId = Default(notaDebito.Cliente.TributoId, "ZZ");
            notaDebito.Cliente.TributoNombre = Default(notaDebito.Cliente.TributoNombre, NombreTributo(notaDebito.Cliente.TributoId));
            notaDebito.Cliente.Direccion = NormalizarDireccion(notaDebito.Cliente.Direccion);

            notaDebito.FacturaReferencia ??= new ReferenciaDocumentoDto();
            notaDebito.FacturaReferencia.SchemeName = Default(notaDebito.FacturaReferencia.SchemeName, "CUFE-SHA384");

            foreach (var concepto in notaDebito.ConceptosCorreccion ?? Enumerable.Empty<ConceptoCorreccionDto>())
            {
                concepto.ReferenceID = Default(concepto.ReferenceID, notaDebito.FacturaReferencia.NumeroDocumento);
                concepto.Codigo = Default(concepto.Codigo, "1");
                concepto.Descripcion = Default(concepto.Descripcion, "Intereses");
            }

            foreach (var item in notaDebito.Items ?? Enumerable.Empty<ItemDto>())
            {
                item.UnidadMedida = Default(item.UnidadMedida, "94");
                foreach (var impuesto in item.Impuestos ?? Enumerable.Empty<ImpuestoDto>())
                {
                    NormalizarImpuesto(impuesto, item);
                }
            }
        }

        public string GenerarXmlNotaDebito(NotaDebitoDto notaDebito)
        {
            NormalizarNotaDebito(notaDebito);

            var debitNote = new XElement(XName.Get("DebitNote", "urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2"),
                new XAttribute(XName.Get("schemaLocation", XSI_NAMESPACE),
                    "urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2 http://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/maindoc/UBL-DebitNote-2.1.xsd"),
                new XAttribute(XNamespace.Xmlns + "cac", CAC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "cbc", CBC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "sts", DIAN_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xsi", XSI_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ext", EXT_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xades", XADES_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ds", DS_NAMESPACE),
                new XAttribute("xmlns", "urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2"));

            debitNote.Add(CrearUBLExtensionsNotaDebito(notaDebito));
            debitNote.Add(new XElement(XName.Get("UBLVersionID", CBC_NAMESPACE), "UBL 2.1"));
            debitNote.Add(new XElement(XName.Get("CustomizationID", CBC_NAMESPACE), notaDebito.CustomizationID));
            debitNote.Add(new XElement(XName.Get("ProfileID", CBC_NAMESPACE), "DIAN 2.1: Nota Débito de Factura Electrónica de Venta"));
            debitNote.Add(new XElement(XName.Get("ProfileExecutionID", CBC_NAMESPACE), notaDebito.ConfiguracionDian.TipoAmbiente));
            debitNote.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), notaDebito.NumeroDocumento));
            debitNote.Add(new XElement(XName.Get("UUID", CBC_NAMESPACE),
                new XAttribute("schemeID", notaDebito.ConfiguracionDian.TipoAmbiente),
                new XAttribute("schemeName", "CUDE-SHA384"),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                string.Empty));
            debitNote.Add(new XElement(XName.Get("IssueDate", CBC_NAMESPACE), notaDebito.FechaEmision.ToString("yyyy-MM-dd")));
            debitNote.Add(new XElement(XName.Get("IssueTime", CBC_NAMESPACE), notaDebito.FechaEmision.ToString("HH:mm:ss") + "-05:00"));
            debitNote.Add(new XElement(XName.Get("DebitNoteTypeCode", CBC_NAMESPACE), notaDebito.DebitNoteTypeCode));

            foreach (var nota in notaDebito.Notas ?? Enumerable.Empty<string>())
            {
                debitNote.Add(new XElement(XName.Get("Note", CBC_NAMESPACE), nota));
            }

            debitNote.Add(new XElement(XName.Get("DocumentCurrencyCode", CBC_NAMESPACE), notaDebito.Moneda));
            debitNote.Add(new XElement(XName.Get("LineCountNumeric", CBC_NAMESPACE), notaDebito.Items.Count));
            debitNote.Add(CrearDiscrepancyResponseNotaDebito(notaDebito));
            debitNote.Add(CrearBillingReferenceNotaCredito(notaDebito.FacturaReferencia));
            debitNote.Add(CrearAccountingSupplierParty(notaDebito.Emisor));
            debitNote.Add(CrearAccountingCustomerParty(notaDebito.Cliente));
            debitNote.Add(CrearTaxTotals(notaDebito.Items));
            debitNote.Add(CrearWithholdingTaxTotals(notaDebito.Items));
            debitNote.Add(CrearLegalMonetaryTotal(notaDebito.Totales));

            foreach (var item in notaDebito.Items)
            {
                debitNote.Add(CrearDebitNoteLine(item));
            }

            return debitNote.ToString();
        }

        private XElement CrearUBLExtensionsNotaDebito(NotaDebitoDto notaDebito)
        {
            var extensions = new XElement(XName.Get("UBLExtensions", EXT_NAMESPACE));
            var extension1 = new XElement(XName.Get("UBLExtension", EXT_NAMESPACE));
            var extensionContent1 = new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE));
            var dianExtensions = new XElement(XName.Get("DianExtensions", DIAN_NAMESPACE));

            dianExtensions.Add(new XElement(XName.Get("InvoiceSource", DIAN_NAMESPACE),
                new XElement(XName.Get("IdentificationCode", CBC_NAMESPACE),
                    new XAttribute("listAgencyID", "6"),
                    new XAttribute("listAgencyName", "United Nations Economic Commission for Europe"),
                    new XAttribute("listSchemeURI", "urn:oasis:names:specification:ubl:codelist:gc:CountryIdentificationCode-2.1"),
                    "CO")));

            dianExtensions.Add(new XElement(XName.Get("SoftwareProvider", DIAN_NAMESPACE),
                new XElement(XName.Get("ProviderID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    new XAttribute("schemeID", notaDebito.Emisor.Dv),
                    new XAttribute("schemeName", notaDebito.Emisor.TipoIdentificacion),
                    notaDebito.Emisor.Nit),
                new XElement(XName.Get("SoftwareID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    notaDebito.ConfiguracionDian.SoftwareId)));

            dianExtensions.Add(new XElement(XName.Get("SoftwareSecurityCode", DIAN_NAMESPACE),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                CalcularSoftwareSecurityCode(notaDebito.ConfiguracionDian.SoftwareId, notaDebito.ConfiguracionDian.Pin, notaDebito.NumeroDocumento)));

            dianExtensions.Add(new XElement(XName.Get("AuthorizationProvider", DIAN_NAMESPACE),
                new XElement(XName.Get("AuthorizationProviderID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    new XAttribute("schemeID", "4"),
                    new XAttribute("schemeName", "31"),
                    "800197268")));

            dianExtensions.Add(new XElement(XName.Get("QRCode", DIAN_NAMESPACE), string.Empty));

            extensionContent1.Add(dianExtensions);
            extension1.Add(extensionContent1);
            extensions.Add(extension1);
            extensions.Add(new XElement(XName.Get("UBLExtension", EXT_NAMESPACE),
                new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE))));

            return extensions;
        }

        private XElement CrearDiscrepancyResponseNotaDebito(NotaDebitoDto notaDebito)
        {
            var concepto = notaDebito.ConceptosCorreccion.First();
            var discrepancy = new XElement(XName.Get("DiscrepancyResponse", CAC_NAMESPACE));
            if (!string.IsNullOrWhiteSpace(concepto.ReferenceID))
            {
                discrepancy.Add(new XElement(XName.Get("ReferenceID", CBC_NAMESPACE), concepto.ReferenceID));
            }
            discrepancy.Add(new XElement(XName.Get("ResponseCode", CBC_NAMESPACE), concepto.Codigo));
            discrepancy.Add(new XElement(XName.Get("Description", CBC_NAMESPACE), concepto.Descripcion));
            return discrepancy;
        }

        private XElement CrearDebitNoteLine(ItemDto item)
        {
            var line = new XElement(XName.Get("DebitNoteLine", CAC_NAMESPACE));
            line.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), item.NumeroLinea));
            line.Add(new XElement(XName.Get("DebitedQuantity", CBC_NAMESPACE),
                new XAttribute("unitCode", item.UnidadMedida),
                item.Cantidad.ToString("F2", CultureInfo.InvariantCulture)));
            line.Add(new XElement(XName.Get("LineExtensionAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                item.Subtotal.ToString("F2", CultureInfo.InvariantCulture)));

            if (item.Impuestos != null && item.Impuestos.Count > 0)
            {
                line.Add(CrearTaxTotals(new List<ItemDto> { item }));
                line.Add(CrearWithholdingTaxTotals(new List<ItemDto> { item }));
            }

            var itemElement = new XElement(XName.Get("Item", CAC_NAMESPACE));
            itemElement.Add(new XElement(XName.Get("Description", CBC_NAMESPACE), item.Descripcion));
            itemElement.Add(new XElement(XName.Get("SellersItemIdentification", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), item.Codigo)));
            line.Add(itemElement);

            line.Add(new XElement(XName.Get("Price", CAC_NAMESPACE),
                new XElement(XName.Get("PriceAmount", CBC_NAMESPACE),
                    new XAttribute("currencyID", "COP"),
                    item.PrecioUnitario.ToString("F2", CultureInfo.InvariantCulture)),
                new XElement(XName.Get("BaseQuantity", CBC_NAMESPACE),
                    new XAttribute("unitCode", item.UnidadMedida),
                    "1.00")));

            return line;
        }

        private XElement CrearUBLExtensionsNotaCredito(NotaCreditoDto notaCredito)
        {
            var extensions = new XElement(XName.Get("UBLExtensions", EXT_NAMESPACE));
            var extension1 = new XElement(XName.Get("UBLExtension", EXT_NAMESPACE));
            var extensionContent1 = new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE));
            var dianExtensions = new XElement(XName.Get("DianExtensions", DIAN_NAMESPACE));

            dianExtensions.Add(new XElement(XName.Get("InvoiceSource", DIAN_NAMESPACE),
                new XElement(XName.Get("IdentificationCode", CBC_NAMESPACE),
                    new XAttribute("listAgencyID", "6"),
                    new XAttribute("listAgencyName", "United Nations Economic Commission for Europe"),
                    new XAttribute("listSchemeURI", "urn:oasis:names:specification:ubl:codelist:gc:CountryIdentificationCode-2.1"),
                    "CO")));

            dianExtensions.Add(new XElement(XName.Get("SoftwareProvider", DIAN_NAMESPACE),
                new XElement(XName.Get("ProviderID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    new XAttribute("schemeID", notaCredito.Emisor.Dv),
                    new XAttribute("schemeName", notaCredito.Emisor.TipoIdentificacion),
                    notaCredito.Emisor.Nit),
                new XElement(XName.Get("SoftwareID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    notaCredito.ConfiguracionDian.SoftwareId)));

            dianExtensions.Add(new XElement(XName.Get("SoftwareSecurityCode", DIAN_NAMESPACE),
                new XAttribute("schemeAgencyID", "195"),
                new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                CalcularSoftwareSecurityCode(notaCredito.ConfiguracionDian.SoftwareId, notaCredito.ConfiguracionDian.Pin, notaCredito.NumeroDocumento)));

            dianExtensions.Add(new XElement(XName.Get("AuthorizationProvider", DIAN_NAMESPACE),
                new XElement(XName.Get("AuthorizationProviderID", DIAN_NAMESPACE),
                    new XAttribute("schemeAgencyID", "195"),
                    new XAttribute("schemeAgencyName", "CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)"),
                    new XAttribute("schemeID", "4"),
                    new XAttribute("schemeName", "31"),
                    "800197268")));

            dianExtensions.Add(new XElement(XName.Get("QRCode", DIAN_NAMESPACE), string.Empty));

            extensionContent1.Add(dianExtensions);
            extension1.Add(extensionContent1);
            extensions.Add(extension1);
            extensions.Add(new XElement(XName.Get("UBLExtension", EXT_NAMESPACE),
                new XElement(XName.Get("ExtensionContent", EXT_NAMESPACE))));

            return extensions;
        }

        private XElement CrearDiscrepancyResponse(NotaCreditoDto notaCredito)
        {
            var concepto = notaCredito.ConceptosCorreccion.First();
            var discrepancy = new XElement(XName.Get("DiscrepancyResponse", CAC_NAMESPACE));
            if (!string.IsNullOrWhiteSpace(concepto.ReferenceID))
            {
                discrepancy.Add(new XElement(XName.Get("ReferenceID", CBC_NAMESPACE), concepto.ReferenceID));
            }
            discrepancy.Add(new XElement(XName.Get("ResponseCode", CBC_NAMESPACE), concepto.Codigo));
            discrepancy.Add(new XElement(XName.Get("Description", CBC_NAMESPACE), concepto.Descripcion));
            return discrepancy;
        }

        private XElement CrearBillingReferenceNotaCredito(ReferenciaDocumentoDto referencia)
        {
            return new XElement(XName.Get("BillingReference", CAC_NAMESPACE),
                new XElement(XName.Get("InvoiceDocumentReference", CAC_NAMESPACE),
                    new XElement(XName.Get("ID", CBC_NAMESPACE), referencia.NumeroDocumento),
                    new XElement(XName.Get("UUID", CBC_NAMESPACE),
                        new XAttribute("schemeName", string.IsNullOrWhiteSpace(referencia.SchemeName) ? "CUFE-SHA384" : referencia.SchemeName),
                        referencia.CUFE),
                    new XElement(XName.Get("IssueDate", CBC_NAMESPACE), referencia.FechaEmision.ToString("yyyy-MM-dd"))));
        }

        private XElement CrearCreditNoteLine(ItemDto item)
        {
            var line = new XElement(XName.Get("CreditNoteLine", CAC_NAMESPACE));
            line.Add(new XElement(XName.Get("ID", CBC_NAMESPACE), item.NumeroLinea));
            line.Add(new XElement(XName.Get("CreditedQuantity", CBC_NAMESPACE),
                new XAttribute("unitCode", item.UnidadMedida),
                item.Cantidad.ToString("F2", CultureInfo.InvariantCulture)));
            line.Add(new XElement(XName.Get("LineExtensionAmount", CBC_NAMESPACE),
                new XAttribute("currencyID", "COP"),
                item.Subtotal.ToString("F2", CultureInfo.InvariantCulture)));

            if (item.Impuestos != null && item.Impuestos.Count > 0)
            {
                line.Add(CrearTaxTotals(new List<ItemDto> { item }));
                line.Add(CrearWithholdingTaxTotals(new List<ItemDto> { item }));
            }

            var itemElement = new XElement(XName.Get("Item", CAC_NAMESPACE));
            itemElement.Add(new XElement(XName.Get("Description", CBC_NAMESPACE), item.Descripcion));
            itemElement.Add(new XElement(XName.Get("SellersItemIdentification", CAC_NAMESPACE),
                new XElement(XName.Get("ID", CBC_NAMESPACE), item.Codigo)));
            line.Add(itemElement);

            line.Add(new XElement(XName.Get("Price", CAC_NAMESPACE),
                new XElement(XName.Get("PriceAmount", CBC_NAMESPACE),
                    new XAttribute("currencyID", "COP"),
                    item.PrecioUnitario.ToString("F2", CultureInfo.InvariantCulture)),
                new XElement(XName.Get("BaseQuantity", CBC_NAMESPACE),
                    new XAttribute("unitCode", item.UnidadMedida),
                    "1.00")));

            return line;
        }

        public string GenerarXmlDocumentoSoporte(DocumentoSoporteDto documentoSoporte)
        {
            var facturaEquivalente = new FacturaDto
            {
                TipoDocumento = "DS",
                NumeroDocumento = documentoSoporte.NumeroDocumento,
                FechaEmision = documentoSoporte.FechaEmision,
                FechaVencimiento = documentoSoporte.FechaEmision,
                Moneda = documentoSoporte.Moneda,
                Emisor = documentoSoporte.Emisor,
                Cliente = documentoSoporte.Cliente,
                Items = documentoSoporte.Items,
                Totales = documentoSoporte.Totales,
                Observaciones = documentoSoporte.Observaciones,
                Notas = documentoSoporte.Notas,
                ConfiguracionDian = documentoSoporte.ConfiguracionDian
            };

            var xml = XDocument.Parse(GenerarXmlFactura(facturaEquivalente));
            var cbc = XNamespace.Get(CBC_NAMESPACE);

            xml.Descendants(cbc + "ProfileID").FirstOrDefault()?.SetValue("DIAN 2.1: Documento Soporte en adquisiciones efectuadas a no obligados a facturar");
            xml.Descendants(cbc + "InvoiceTypeCode").FirstOrDefault()?.SetValue("05");

            return xml.ToString();
        }

        public string GenerarXmlNomina(NominaDto nomina)
        {
            var ns = XNamespace.Get("dian:gov:co:facturaelectronica:NominaIndividual");
            var cbc = XNamespace.Get(CBC_NAMESPACE);
            var ext = XNamespace.Get(EXT_NAMESPACE);
            var ds = XNamespace.Get(DS_NAMESPACE);
            var xades = XNamespace.Get(XADES_NAMESPACE);

            var root = new XElement(ns + "NominaIndividual",
                new XAttribute(XNamespace.Xmlns + "cbc", CBC_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ext", EXT_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "ds", DS_NAMESPACE),
                new XAttribute(XNamespace.Xmlns + "xades", XADES_NAMESPACE),
                new XAttribute("xmlns", ns.NamespaceName));

            root.Add(new XElement(ext + "UBLExtensions",
                new XElement(ext + "UBLExtension",
                    new XElement(ext + "ExtensionContent",
                        new XElement(ns + "DianExtensions",
                            new XElement(ns + "SoftwareProvider",
                                new XElement(ns + "ProviderID", nomina.Empleador?.Nit ?? string.Empty),
                                new XElement(ns + "SoftwareID", nomina.ConfiguracionDian?.SoftwareId ?? string.Empty)),
                            new XElement(ns + "SoftwareSecurityCode", nomina.ConfiguracionDian?.ClaveTecnica ?? string.Empty)))),
                new XElement(ext + "UBLExtension",
                    new XElement(ext + "ExtensionContent"))));

            root.Add(new XElement(cbc + "UBLVersionID", "UBL 2.1"));
            root.Add(new XElement(cbc + "ProfileExecutionID", "1"));
            root.Add(new XElement(cbc + "ID", nomina.NumeroDocumento));
            root.Add(new XElement(cbc + "IssueDate", nomina.FechaEmision.ToString("yyyy-MM-dd")));
            root.Add(new XElement(cbc + "IssueTime", nomina.FechaEmision.ToString("HH:mm:ss") + "-05:00"));
            root.Add(new XElement(cbc + "DocumentCurrencyCode", nomina.Moneda));
            root.Add(new XElement(ns + "CUNE", string.Empty));

            foreach (var nota in nomina.Notas)
            {
                root.Add(new XElement(cbc + "Note", nota));
            }

            root.Add(new XElement(ns + "Empleador",
                new XElement(ns + "NIT", nomina.Empleador?.Nit ?? string.Empty),
                new XElement(ns + "RazonSocial", nomina.Empleador?.RazonSocial ?? string.Empty)));

            root.Add(new XElement(ns + "Trabajador",
                new XElement(ns + "TipoDocumento", nomina.Trabajador?.TipoIdentificacion ?? string.Empty),
                new XElement(ns + "NumeroDocumento", nomina.Trabajador?.NumeroIdentificacion ?? string.Empty),
                new XElement(ns + "PrimerNombre", nomina.Trabajador?.PrimerNombre ?? string.Empty),
                new XElement(ns + "OtrosNombres", nomina.Trabajador?.OtrosNombres ?? string.Empty),
                new XElement(ns + "PrimerApellido", nomina.Trabajador?.PrimerApellido ?? string.Empty),
                new XElement(ns + "SegundoApellido", nomina.Trabajador?.SegundoApellido ?? string.Empty),
                new XElement(ns + "TipoContrato", nomina.Trabajador?.TipoContrato ?? string.Empty),
                new XElement(ns + "SubTipoTrabajador", nomina.Trabajador?.SubtipoTrabajador ?? string.Empty),
                new XElement(ns + "TipoTrabajador", nomina.Trabajador?.TipoTrabajador ?? string.Empty),
                new XElement(ns + "Sueldo", (nomina.Trabajador?.Sueldo ?? 0).ToString("F2", CultureInfo.InvariantCulture))));

            if (nomina.Pago != null)
            {
                root.Add(new XElement(ns + "Pago",
                    new XElement(ns + "FechaIngreso", nomina.Pago.FechaIngreso.ToString("yyyy-MM-dd")),
                    new XElement(ns + "FechaLiquidacionInicio", nomina.Pago.FechaLiquidacionInicio.ToString("yyyy-MM-dd")),
                    new XElement(ns + "FechaLiquidacionFin", nomina.Pago.FechaLiquidacionFin.ToString("yyyy-MM-dd")),
                    new XElement(ns + "FechaPago", nomina.Pago.FechaPago.ToString("yyyy-MM-dd"))));
            }

            root.Add(CrearConceptosNomina(ns, "Devengados", nomina.Devengados));
            root.Add(CrearConceptosNomina(ns, "Deducciones", nomina.Deducciones));

            root.Add(new XElement(ns + "Totales",
                new XElement(ns + "TotalDevengados", (nomina.Pago?.TotalDevengados ?? 0).ToString("F2", CultureInfo.InvariantCulture)),
                new XElement(ns + "TotalDeducciones", (nomina.Pago?.TotalDeducciones ?? 0).ToString("F2", CultureInfo.InvariantCulture)),
                new XElement(ns + "TotalComprobante", (nomina.Pago?.TotalComprobante ?? 0).ToString("F2", CultureInfo.InvariantCulture))));

            return new XDocument(new XDeclaration("1.0", "utf-8", null), root).ToString();
        }

        private static XElement CrearConceptosNomina(XNamespace ns, string nombreGrupo, List<ConceptoNominaDto> conceptos)
        {
            var grupo = new XElement(ns + nombreGrupo);
            foreach (var concepto in conceptos)
            {
                grupo.Add(new XElement(ns + "Concepto",
                    new XElement(ns + "Codigo", concepto.Codigo),
                    new XElement(ns + "Descripcion", concepto.Descripcion),
                    new XElement(ns + "Valor", concepto.Valor.ToString("F2", CultureInfo.InvariantCulture))));
            }

            return grupo;
        }
    }
}
