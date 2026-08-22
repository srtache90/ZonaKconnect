package reception

import (
	"testing"
)

func TestValidateCUFE(t *testing.T) {
	if len(ValidateCUFE("")) == 0 {
		t.Fatal("expected issue for empty cufe")
	}
	cufe := ""
	for i := 0; i < 96; i++ {
		cufe += "a"
	}
	if issues := ValidateCUFE(cufe); len(issues) != 0 {
		t.Fatalf("expected valid cufe, got %v", issues)
	}
}

func TestSameNIT(t *testing.T) {
	if !SameNIT("900123456-1", "9001234561") {
		t.Fatal("expected same nit with DV")
	}
	if SameNIT("900123456", "800123456") {
		t.Fatal("expected different nit")
	}
}

func TestParseInvoiceXML(t *testing.T) {
	xml := `<?xml version="1.0"?>
	<Invoice>
	  <cbc:ID>SETT999</cbc:ID>
	  <cbc:UUID schemeName="CUFE-SHA384">` + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + `</cbc:UUID>
	  <cbc:IssueDate>2026-08-01</cbc:IssueDate>
	  <cac:AccountingSupplierParty><cac:Party><cac:PartyTaxScheme><cbc:RegistrationName>Prov SA</cbc:RegistrationName><cbc:CompanyID>800111222</cbc:CompanyID></cac:PartyTaxScheme></cac:Party></cac:AccountingSupplierParty>
	  <cac:AccountingCustomerParty><cac:Party><cac:PartyTaxScheme><cbc:CompanyID>900123456</cbc:CompanyID></cac:PartyTaxScheme></cac:Party></cac:AccountingCustomerParty>
	  <cac:LegalMonetaryTotal><cbc:PayableAmount>1000.00</cbc:PayableAmount></cac:LegalMonetaryTotal>
	</Invoice>`
	parsed, err := ParseInvoiceXML(xml)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.ProveedorNIT != "800111222" {
		t.Fatalf("proveedor nit=%s", parsed.ProveedorNIT)
	}
	if parsed.ReceptorNIT != "900123456" {
		t.Fatalf("receptor nit=%s", parsed.ReceptorNIT)
	}
	if parsed.InvoiceNumber != "SETT999" {
		t.Fatalf("invoice=%s", parsed.InvoiceNumber)
	}
}
