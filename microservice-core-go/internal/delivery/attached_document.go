package delivery

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"fmt"
	"regexp"
	"strings"
	"time"
)

var xmlTag = func(local string) *regexp.Regexp {
	return regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?` + local + `\b[^>]*>([^<]*)</(?:[A-Za-z0-9]+:)?` + local + `>`)
}

// BuildAttachedDocumentZip arma el contenedor electrónico exigido por el Anexo Técnico (AttachedDocument + ZIP).
func BuildAttachedDocumentZip(signedXML, appResponse []byte) ([]byte, string, error) {
	if len(signedXML) == 0 {
		return nil, "", fmt.Errorf("XML firmado no disponible")
	}
	if len(appResponse) == 0 {
		return nil, "", fmt.Errorf("ApplicationResponse DIAN no disponible")
	}

	meta, err := extractSignedXMLMeta(string(signedXML))
	if err != nil {
		return nil, "", err
	}

	xmlContent := buildAttachedDocumentXML(meta, signedXML, appResponse)
	stem := attachedDocumentStem(meta.SenderNIT, meta.ParentDocumentID)
	fileName := stem + ".xml"

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	entry, err := zw.Create(fileName)
	if err != nil {
		return nil, "", err
	}
	if _, err := entry.Write([]byte(xmlContent)); err != nil {
		_ = zw.Close()
		return nil, "", err
	}
	if err := zw.Close(); err != nil {
		return nil, "", err
	}
	if buf.Len() > 2*1024*1024 {
		return nil, "", fmt.Errorf("ZIP supera 2 MB permitidos por DIAN")
	}
	return buf.Bytes(), stem + ".zip", nil
}

type signedXMLMeta struct {
	ParentDocumentID   string
	CUFE               string
	SchemeName         string
	IssueDate          string
	ProfileExecutionID string
	SenderNIT          string
	SenderDV           string
	SenderName         string
	ReceiverNIT        string
	ReceiverDV         string
	ReceiverName       string
	RootLocalName      string
}

func extractSignedXMLMeta(xml string) (signedXMLMeta, error) {
	root := detectRootLocalName(xml)
	parentID := firstTag(xml, "ID")
	if parentID == "" {
		return signedXMLMeta{}, fmt.Errorf("cbc:ID no encontrado en XML firmado")
	}

	cufe := firstTag(xml, "UUID")
	schemeName := "CUFE-SHA384"
	if root == "CreditNote" || root == "DebitNote" {
		schemeName = "CUDE-SHA384"
	}

	issueDate := firstTag(xml, "IssueDate")
	if issueDate == "" {
		issueDate = time.Now().Format("2006-01-02")
	}

	profileExecutionID := firstTag(xml, "ProfileExecutionID")
	if profileExecutionID == "" {
		profileExecutionID = "2"
	}

	senderNIT := partyField(xml, "AccountingSupplierParty", "CompanyID")
	receiverNIT := partyField(xml, "AccountingCustomerParty", "CompanyID")
	senderName := partyField(xml, "AccountingSupplierParty", "RegistrationName")
	if senderName == "" {
		senderName = partyField(xml, "AccountingSupplierParty", "Name")
	}
	receiverName := partyField(xml, "AccountingCustomerParty", "RegistrationName")
	if receiverName == "" {
		receiverName = partyField(xml, "AccountingCustomerParty", "Name")
	}

	return signedXMLMeta{
		ParentDocumentID:   parentID,
		CUFE:               cufe,
		SchemeName:         schemeName,
		IssueDate:          issueDate,
		ProfileExecutionID: profileExecutionID,
		SenderNIT:          digitsOnly(senderNIT),
		SenderDV:           extractDV(senderNIT),
		SenderName:         senderName,
		ReceiverNIT:        digitsOnly(receiverNIT),
		ReceiverDV:           extractDV(receiverNIT),
		ReceiverName:       receiverName,
		RootLocalName:      root,
	}, nil
}

func buildAttachedDocumentXML(meta signedXMLMeta, signedXML, appResponse []byte) string {
	now := time.Now().In(time.FixedZone("COT", -5*3600))
	issueDate := now.Format("2006-01-02")
	issueTime := now.Format("15:04:05") + "-05:00"
	containerID := meta.ParentDocumentID
	profileID := attachedProfileID(meta.RootLocalName)
	signedB64 := base64.StdEncoding.EncodeToString(signedXML)
	appB64 := base64.StdEncoding.EncodeToString(appResponse)

	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<AttachedDocument xmlns="urn:oasis:names:specification:ubl:schema:xsd:AttachedDocument-2"
    xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
    xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
    <cbc:UBLVersionID>UBL 2.1</cbc:UBLVersionID>
    <cbc:CustomizationID>Documentos adjuntos</cbc:CustomizationID>
    <cbc:ProfileID>%s</cbc:ProfileID>
    <cbc:ProfileExecutionID>%s</cbc:ProfileExecutionID>
    <cbc:ID>%s</cbc:ID>
    <cbc:IssueDate>%s</cbc:IssueDate>
    <cbc:IssueTime>%s</cbc:IssueTime>
    <cbc:DocumentType>Contenedor de Factura Electrónica</cbc:DocumentType>
    <cbc:ParentDocumentID>%s</cbc:ParentDocumentID>
    <cac:SenderParty>
        <cac:PartyTaxScheme>
            <cbc:RegistrationName>%s</cbc:RegistrationName>
            <cbc:CompanyID schemeAgencyID="195" schemeAgencyName="CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)" schemeID="%s" schemeName="31">%s</cbc:CompanyID>
            <cac:TaxScheme>
                <cbc:ID>01</cbc:ID>
                <cbc:Name>IVA</cbc:Name>
            </cac:TaxScheme>
        </cac:PartyTaxScheme>
    </cac:SenderParty>
    <cac:ReceiverParty>
        <cac:PartyTaxScheme>
            <cbc:RegistrationName>%s</cbc:RegistrationName>
            <cbc:CompanyID schemeAgencyID="195" schemeAgencyName="CO, DIAN (Dirección de Impuestos y Aduanas Nacionales)" schemeID="%s" schemeName="31">%s</cbc:CompanyID>
            <cac:TaxScheme>
                <cbc:ID>01</cbc:ID>
                <cbc:Name>IVA</cbc:Name>
            </cac:TaxScheme>
        </cac:PartyTaxScheme>
    </cac:ReceiverParty>
    <cac:Attachment>
        <cac:ExternalReference>
            <cbc:MimeCode>text/xml</cbc:MimeCode>
            <cbc:EncodingCode>UTF-8</cbc:EncodingCode>
            <cbc:Description>%s</cbc:Description>
        </cac:ExternalReference>
    </cac:Attachment>
    <cac:ParentDocumentLineReference>
        <cbc:LineID>1</cbc:LineID>
        <cac:DocumentReference>
            <cbc:ID>%s</cbc:ID>
            <cbc:UUID schemeName="%s">%s</cbc:UUID>
            <cbc:IssueDate>%s</cbc:IssueDate>
            <cbc:DocumentType>ApplicationResponse</cbc:DocumentType>
            <cac:Attachment>
                <cac:ExternalReference>
                    <cbc:MimeCode>text/xml</cbc:MimeCode>
                    <cbc:EncodingCode>UTF-8</cbc:EncodingCode>
                    <cbc:Description>%s</cbc:Description>
                </cac:ExternalReference>
            </cac:Attachment>
            <cac:ResultOfVerification>
                <cbc:ValidatorID>Unidad Especial Dirección de Impuestos y Aduanas Nacionales</cbc:ValidatorID>
                <cbc:ValidationResultCode>002</cbc:ValidationResultCode>
                <cbc:ValidationDate>%s</cbc:ValidationDate>
                <cbc:ValidationTime>%s</cbc:ValidationTime>
            </cac:ResultOfVerification>
        </cac:DocumentReference>
    </cac:ParentDocumentLineReference>
</AttachedDocument>`,
		xmlEscape(profileID),
		xmlEscape(meta.ProfileExecutionID),
		xmlEscape(containerID),
		issueDate,
		issueTime,
		xmlEscape(meta.ParentDocumentID),
		xmlEscape(defaultString(meta.SenderName, "Emisor")),
		xmlEscape(defaultString(meta.SenderDV, "0")),
		xmlEscape(meta.SenderNIT),
		xmlEscape(defaultString(meta.ReceiverName, "Adquirente")),
		xmlEscape(defaultString(meta.ReceiverDV, "0")),
		xmlEscape(meta.ReceiverNIT),
		signedB64,
		xmlEscape(meta.ParentDocumentID),
		xmlEscape(meta.SchemeName),
		xmlEscape(meta.CUFE),
		xmlEscape(meta.IssueDate),
		appB64,
		issueDate,
		issueTime,
	)
}

func attachedProfileID(rootLocalName string) string {
	switch rootLocalName {
	case "CreditNote":
		return "DIAN 2.1: Nota Crédito de Factura Electrónica de Venta"
	case "DebitNote":
		return "DIAN 2.1: Nota Débito de Factura Electrónica de Venta"
	default:
		return "DIAN 2.1: Factura Electrónica de Venta"
	}
}

func attachedDocumentStem(senderNIT, parentDocumentID string) string {
	nit := digitsOnly(senderNIT)
	if len(nit) > 10 {
		nit = nit[:10]
	}
	nit = fmt.Sprintf("%010s", nit)
	nit = strings.ReplaceAll(nit, " ", "0")
	year := time.Now().Format("06")
	suffix := fmt.Sprintf("%08x", time.Now().UnixNano()&0xffffffff)
	return "ad" + nit + "001" + year + suffix
}

func detectRootLocalName(xml string) string {
	trimmed := strings.TrimSpace(xml)
	if idx := strings.Index(trimmed, "<"); idx >= 0 {
		trimmed = trimmed[idx+1:]
	}
	if idx := strings.IndexAny(trimmed, " \t\n>"); idx >= 0 {
		token := trimmed[:idx]
		if colon := strings.LastIndex(token, ":"); colon >= 0 {
			return token[colon+1:]
		}
		return token
	}
	return "Invoice"
}

func partyField(xml, party, field string) string {
	re := regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?` + party + `\b[^>]*>([\s\S]*?)</(?:[A-Za-z0-9]+:)?` + party + `>`)
	m := re.FindStringSubmatch(xml)
	if len(m) < 2 {
		return ""
	}
	return firstTag(m[1], field)
}

func firstTag(xml, local string) string {
	m := xmlTag(local).FindStringSubmatch(xml)
	if len(m) < 2 {
		return ""
	}
	return strings.TrimSpace(m[1])
}

func digitsOnly(value string) string {
	var b strings.Builder
	for _, r := range value {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func extractDV(nit string) string {
	digits := digitsOnly(nit)
	if len(digits) > 10 {
		return digits[len(digits)-1:]
	}
	return "0"
}

func defaultString(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func xmlEscape(value string) string {
	replacer := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		`"`, "&quot;",
		"'", "&apos;",
	)
	return replacer.Replace(value)
}
