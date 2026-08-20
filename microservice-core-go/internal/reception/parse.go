package reception

import (
	"fmt"
	"regexp"
	"strings"
	"unicode"
)

var (
	cufeSHA384 = regexp.MustCompile(`(?i)^[a-f0-9]{96}$`)
	cufeHexMin = regexp.MustCompile(`(?i)^[a-f0-9]{64,128}$`)
	tagRe      = func(local string) *regexp.Regexp {
		return regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?` + local + `\b[^>]*>([^<]*)</(?:[A-Za-z0-9]+:)?` + local + `>`)
	}
	uuidSchemeRe = regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?UUID\b[^>]*schemeName="CUFE-SHA384"[^>]*>([^<]+)</(?:[A-Za-z0-9]+:)?UUID>`)
	invoiceIDRe  = regexp.MustCompile(`(?i)<(?:[A-Za-z0-9]+:)?ID\b[^>]*>([A-Za-z]{1,12}\d{3,})</(?:[A-Za-z0-9]+:)?ID>`)
	partyBlockRe = func(party string) *regexp.Regexp {
		return regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?` + party + `\b[^>]*>([\s\S]*?)</(?:[A-Za-z0-9]+:)?` + party + `>`)
	}
)

type ParsedXML struct {
	Prefijo         string
	InvoiceNumber   string
	ProveedorNombre string
	ProveedorNIT    string
	ReceptorNIT     string
	CUFE            string
	FechaEmision    string
	Total           string
}

func ValidateCUFE(cufe string) []string {
	var issues []string
	cufe = strings.TrimSpace(cufe)
	if cufe == "" {
		return []string{"CUFE ausente"}
	}
	if strings.Contains(cufe, " ") {
		issues = append(issues, "CUFE contiene espacios")
	}
	if !cufeHexMin.MatchString(cufe) {
		issues = append(issues, "CUFE con formato inválido (se esperan 64–128 hex)")
	} else if !cufeSHA384.MatchString(cufe) {
		issues = append(issues, "CUFE no tiene longitud SHA-384 (96 hex); verifique el XML")
	}
	return issues
}

func NormalizeNIT(nit string) string {
	var b strings.Builder
	for _, r := range nit {
		if unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func SameNIT(left, right string) bool {
	a := NormalizeNIT(left)
	b := NormalizeNIT(right)
	if a == "" || b == "" {
		return false
	}
	if a == b {
		return true
	}
	if len(a) == len(b)+1 && strings.HasPrefix(a, b) {
		return true
	}
	if len(b) == len(a)+1 && strings.HasPrefix(b, a) {
		return true
	}
	return false
}

func IsReceivableUBL(xml string) bool {
	xml = strings.TrimSpace(xml)
	xml = strings.TrimPrefix(xml, "\ufeff")
	if xml == "" || !strings.HasPrefix(xml, "<") {
		return false
	}
	head := strings.ToLower(xml)
	if len(head) > 800 {
		head = head[:800]
	}
	return strings.Contains(head, "<invoice") ||
		strings.Contains(head, ":invoice") ||
		strings.Contains(head, "<creditnote") ||
		strings.Contains(head, ":creditnote") ||
		strings.Contains(head, "<debitnote") ||
		strings.Contains(head, ":debitnote") ||
		strings.Contains(head, "<attacheddocument") ||
		strings.Contains(head, ":attacheddocument")
}

func ParseInvoiceXML(xml string) (ParsedXML, error) {
	source := extractEmbeddedInvoice(xml)
	if source == "" {
		source = xml
	}
	if !IsReceivableUBL(source) && !IsReceivableUBL(xml) {
		return ParsedXML{}, fmt.Errorf("XML no es Invoice/CreditNote/DebitNote/AttachedDocument")
	}

	id := firstInvoiceID(source)
	prefijo := "FV"
	if m := regexp.MustCompile(`^([A-Za-z]+)(\d+)$`).FindStringSubmatch(strings.TrimSpace(id)); len(m) == 3 {
		prefijo = m[1]
	}

	proveedorNombre := firstNonBlank(
		partyField(source, "AccountingSupplierParty", "RegistrationName"),
		partyField(source, "AccountingSupplierParty", "Name"),
		firstTag(source, "RegistrationName"),
		"Proveedor",
	)
	proveedorNIT := firstNonBlank(
		partyField(source, "AccountingSupplierParty", "CompanyID"),
		firstTag(source, "CompanyID"),
		"—",
	)
	receptorNIT := partyField(source, "AccountingCustomerParty", "CompanyID")
	cufe := firstNonBlank(taggedUUID(source), taggedUUID(xml), firstTag(source, "UUID"))
	fecha := firstTag(source, "IssueDate")
	total := firstNonBlank(firstTag(source, "PayableAmount"), firstTag(source, "TaxInclusiveAmount"), "0")
	invoiceNumber := strings.TrimSpace(id)
	if invoiceNumber == "" {
		invoiceNumber = prefijo
	}
	return ParsedXML{
		Prefijo:         prefijo,
		InvoiceNumber:   invoiceNumber,
		ProveedorNombre: proveedorNombre,
		ProveedorNIT:    proveedorNIT,
		ReceptorNIT:     receptorNIT,
		CUFE:            strings.TrimSpace(cufe),
		FechaEmision:    strings.TrimSpace(fecha),
		Total:           strings.TrimSpace(total),
	}, nil
}

func extractEmbeddedInvoice(xml string) string {
	cdata := regexp.MustCompile(`(?s)<!\[CDATA\[(.*?)]]>`).FindAllStringSubmatch(xml, -1)
	for _, m := range cdata {
		inner := unescapeXML(strings.TrimSpace(m[1]))
		if IsReceivableUBL(inner) && (strings.Contains(inner, "Invoice") || strings.Contains(inner, "CreditNote") || strings.Contains(inner, "DebitNote")) {
			return inner
		}
	}
	desc := regexp.MustCompile(`(?is)<(?:[A-Za-z0-9]+:)?Description\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?Description>`).FindAllStringSubmatch(xml, -1)
	for _, m := range desc {
		inner := unescapeXML(strings.TrimSpace(m[1]))
		if IsReceivableUBL(inner) && (strings.Contains(inner, "Invoice") || strings.Contains(inner, "CreditNote") || strings.Contains(inner, "DebitNote")) {
			return inner
		}
	}
	return ""
}

func unescapeXML(value string) string {
	r := strings.NewReplacer(
		"&lt;", "<",
		"&gt;", ">",
		"&quot;", "\"",
		"&apos;", "'",
		"&amp;", "&",
	)
	return r.Replace(value)
}

func firstInvoiceID(xml string) string {
	if m := invoiceIDRe.FindStringSubmatch(xml); len(m) == 2 {
		return strings.TrimSpace(m[1])
	}
	return firstTag(xml, "ID")
}

func taggedUUID(xml string) string {
	if m := uuidSchemeRe.FindStringSubmatch(xml); len(m) == 2 {
		return strings.TrimSpace(m[1])
	}
	return ""
}

func partyField(xml, party, field string) string {
	m := partyBlockRe(party).FindStringSubmatch(xml)
	if len(m) < 2 {
		return ""
	}
	return firstTag(m[1], field)
}

func firstTag(xml, local string) string {
	m := tagRe(local).FindStringSubmatch(xml)
	if len(m) < 2 {
		return ""
	}
	return strings.TrimSpace(m[1])
}

func firstNonBlank(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
