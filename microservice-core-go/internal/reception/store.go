package reception

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Store struct {
	DB *pgxpool.Pool
}

type IngestResult struct {
	Imported int      `json:"imported"`
	Skipped  int      `json:"skipped"`
	Issues   []string `json:"issues,omitempty"`
}

func (s *Store) LoadSociedadNIT(ctx context.Context, companyID uuid.UUID) (string, error) {
	var nit string
	err := s.DB.QueryRow(ctx, `
		SELECT COALESCE(NULLIF(s.nit, ''), NULLIF(c.nit, ''), '')
		FROM (SELECT $1::uuid AS id) x
		LEFT JOIN sociedades s ON s.id = x.id
		LEFT JOIN companies c ON c.id = x.id
	`, companyID).Scan(&nit)
	return nit, err
}

func (s *Store) AlreadyImported(ctx context.Context, companyID uuid.UUID, cufe string) (bool, error) {
	if strings.TrimSpace(cufe) == "" {
		return false, nil
	}
	var exists bool
	err := s.DB.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM invoices
			WHERE company_id = $1
			  AND (uuid_cude = $2 OR COALESCE(raw_dian_payload_jsonb->>'cufe','') = $2)
		)
	`, companyID, cufe).Scan(&exists)
	return exists, err
}

func (s *Store) NextReceivedNumber(ctx context.Context, companyID uuid.UUID) (int64, error) {
	var next int64
	err := s.DB.QueryRow(ctx, `
		SELECT COALESCE(MAX(numero), 0) + 1
		FROM invoices
		WHERE company_id = $1 AND emission_point_id IS NULL
	`, companyID).Scan(&next)
	if err != nil {
		return 0, err
	}
	if next <= 0 {
		return 1, nil
	}
	return next, nil
}

func (s *Store) AttachPDFIfMissing(ctx context.Context, companyID uuid.UUID, cufe string, pdf []byte) {
	if strings.TrimSpace(cufe) == "" || !isPDF(pdf) {
		return
	}
	encoded := EncodePDFBase64(pdf)
	_, _ = s.DB.Exec(ctx, `
		UPDATE invoices
		SET raw_dian_payload_jsonb = jsonb_set(
				COALESCE(raw_dian_payload_jsonb, '{}'::jsonb),
				'{pdf_base}',
				to_jsonb($1::text)
			),
			updated_at = now()
		WHERE company_id = $2
		  AND uuid_cude = $3
		  AND COALESCE(raw_dian_payload_jsonb->>'pdf_base', '') = ''
	`, encoded, companyID, cufe)
}

func (s *Store) InsertReceivedInvoice(
	ctx context.Context,
	companyID uuid.UUID,
	xml string,
	source string,
	pdf []byte,
	sociedadNIT string,
) (bool, string, error) {
	parsed, err := ParseInvoiceXML(xml)
	if err != nil {
		return false, err.Error(), nil
	}
	issues := ValidateCUFE(parsed.CUFE)
	if parsed.CUFE != "" {
		dup, err := s.AlreadyImported(ctx, companyID, parsed.CUFE)
		if err != nil {
			return false, "", err
		}
		if dup {
			s.AttachPDFIfMissing(ctx, companyID, parsed.CUFE, pdf)
			return false, "CUFE duplicado", nil
		}
	}

	receptorMismatch := sociedadNIT != "" && parsed.ReceptorNIT != "" && !SameNIT(sociedadNIT, parsed.ReceptorNIT)
	numero, err := s.NextReceivedNumber(ctx, companyID)
	if err != nil {
		return false, "", err
	}

	payload := map[string]any{
		"role":           "RECIBIDA",
		"source":         source,
		"xml_base":       xml,
		"invoice_number": parsed.InvoiceNumber,
		"cufe":           parsed.CUFE,
		"fecha_emision":  parsed.FechaEmision,
		"total":          parsed.Total,
		"receptor_nit":   parsed.ReceptorNIT,
		"receptor":       map[string]any{"nit": parsed.ReceptorNIT},
		"proveedor": map[string]any{
			"razon_social": parsed.ProveedorNombre,
			"nit":          parsed.ProveedorNIT,
		},
	}
	if len(issues) > 0 {
		payload["cufe_validation"] = strings.Join(issues, "; ")
	}
	if receptorMismatch {
		payload["receptor_nit_mismatch"] = true
	}
	if encoded := EncodePDFBase64(pdf); encoded != "" {
		payload["pdf_base"] = encoded
	}
	rawJSON, _ := json.Marshal(payload)

	totalFloat, _ := strconv.ParseFloat(strings.ReplaceAll(parsed.Total, ",", "."), 64)
	totalsJSON, _ := json.Marshal(map[string]any{"total": totalFloat})

	var cufeArg any
	if strings.TrimSpace(parsed.CUFE) != "" {
		cufeArg = parsed.CUFE
	}

	_, err = s.DB.Exec(ctx, `
		INSERT INTO invoices (
			company_id, emission_point_id, prefijo, numero, uuid_cude,
			estado_dian, document_kind, totals_jsonb, raw_dian_payload_jsonb
		) VALUES ($1, NULL, 'RCV', $2, $3, 'PENDIENTE', 'INVOICE', $4::jsonb, $5::jsonb)
	`, companyID, numero, cufeArg, string(totalsJSON), string(rawJSON))
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "duplicate") || strings.Contains(err.Error(), "unique") {
			return false, "duplicado UNIQUE", nil
		}
		return false, "", fmt.Errorf("insert recibida: %w", err)
	}
	msg := ""
	if receptorMismatch {
		msg = "importada con NIT receptor distinto"
	}
	return true, msg, nil
}

func (s *Store) IngestPackage(ctx context.Context, companyID uuid.UUID, content []byte, fileName, source string) (IngestResult, error) {
	sociedadNIT, _ := s.LoadSociedadNIT(ctx, companyID)
	pack := ExtractFiscalPackage(content, fileName)
	result := IngestResult{}
	if len(pack.XMLs) == 0 {
		return result, fmt.Errorf("no se encontró XML de factura (Invoice o AttachedDocument) en el archivo")
	}
	for _, xml := range pack.XMLs {
		ok, note, err := s.InsertReceivedInvoice(ctx, companyID, xml, source, pack.PDF, sociedadNIT)
		if err != nil {
			return result, err
		}
		if ok {
			result.Imported++
		} else {
			result.Skipped++
			if note != "" {
				result.Issues = append(result.Issues, note)
			}
		}
	}
	if result.Imported == 0 {
		return result, fmt.Errorf("los XML ya estaban registrados o no se pudieron guardar para esta sociedad")
	}
	return result, nil
}
