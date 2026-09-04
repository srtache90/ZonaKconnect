package main

import (
	"encoding/json"
	"testing"
)

func TestRequestedInvoiceNumberFromHarmonyFolio(t *testing.T) {
	totals, _ := json.Marshal(map[string]any{
		"consecutivo":    660502,
		"numero_factura": "P660502",
	})
	number, ok := requestedInvoiceNumber(totals)
	if !ok || number != 660502 {
		t.Fatalf("expected 660502, got %d ok=%v", number, ok)
	}
}

func TestParseDocumentNumberFromPrefixedString(t *testing.T) {
	number, ok := parseDocumentNumber("P660502")
	if !ok || number != 660502 {
		t.Fatalf("expected 660502, got %d ok=%v", number, ok)
	}
}

func TestInvoiceLockedForCorrection(t *testing.T) {
	if !invoiceLockedForCorrection("ENVIADO") {
		t.Fatal("ENVIADO must be locked")
	}
	if invoiceLockedForCorrection("RECHAZADO_DIAN") {
		t.Fatal("RECHAZADO_DIAN must be overwritable")
	}
}
