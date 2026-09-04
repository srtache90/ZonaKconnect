package main

import (
	"encoding/json"
	"strconv"
	"strings"
	"unicode"
)

func requestedInvoiceNumber(totals json.RawMessage) (int64, bool) {
	if len(totals) == 0 || !json.Valid(totals) {
		return 0, false
	}
	var raw map[string]any
	if json.Unmarshal(totals, &raw) != nil {
		return 0, false
	}
	for _, key := range []string{"consecutivo", "numero", "numero_factura", "numero_factura_completo"} {
		if number, ok := parseDocumentNumber(raw[key]); ok {
			return number, true
		}
	}
	return 0, false
}

func parseDocumentNumber(value any) (int64, bool) {
	switch typed := value.(type) {
	case float64:
		if typed <= 0 {
			return 0, false
		}
		return int64(typed), true
	case int:
		if typed <= 0 {
			return 0, false
		}
		return int64(typed), true
	case int64:
		if typed <= 0 {
			return 0, false
		}
		return typed, true
	case json.Number:
		parsed, err := typed.Int64()
		if err != nil || parsed <= 0 {
			return 0, false
		}
		return parsed, true
	case string:
		digits := trailingDigits(typed)
		if digits == "" {
			return 0, false
		}
		parsed, err := strconv.ParseInt(digits, 10, 64)
		if err != nil || parsed <= 0 {
			return 0, false
		}
		return parsed, true
	default:
		return 0, false
	}
}

func trailingDigits(value string) string {
	var digits strings.Builder
	for _, ch := range strings.TrimSpace(value) {
		if unicode.IsDigit(ch) {
			digits.WriteRune(ch)
			continue
		}
		if digits.Len() > 0 {
			break
		}
	}
	return digits.String()
}

func invoiceLockedForCorrection(estado string) bool {
	normalized := strings.ToUpper(strings.TrimSpace(estado))
	switch normalized {
	case "ENVIADO", "ACEPTADO", "ACEPTADA", "APROBADO", "APROBADA":
		return true
	}
	return strings.Contains(normalized, "VALIDADO EXITOSAMENTE")
}
