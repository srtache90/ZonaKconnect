package emission

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

const closedEventCodes = `'031','033','034','087','088'`

// RadianSyncService consulta DIAN GetDocumentInfo y persiste eventos en invoices.
type RadianSyncService struct {
	db         *pgxpool.Pool
	dianAPIURL string
	httpClient *http.Client
}

func NewRadianSyncService(db *pgxpool.Pool, dianAPIURL string, httpClient *http.Client) *RadianSyncService {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 45 * time.Second}
	}
	return &RadianSyncService{
		db:         db,
		dianAPIURL: strings.TrimRight(dianAPIURL, "/"),
		httpClient: httpClient,
	}
}

func (s *RadianSyncService) Process(ctx context.Context, job RadianSyncJob) error {
	if job.Cufe == "" {
		return fmt.Errorf("cufe vacio invoice_id=%s", job.InvoiceID)
	}

	companyCtx, err := LoadCompanyContext(ctx, s.db, job.TenantID, job.EmissionPointID)
	if err != nil {
		return err
	}
	if companyCtx.DIANConfig.S3CertificateKey == "" || companyCtx.DIANConfig.SecretsManagerPasswordKey == "" {
		return fmt.Errorf("configuracion DIAN incompleta tenant_id=%s", job.TenantID)
	}

	ambiente := resolveAmbiente(job.Ambiente, companyCtx.DIANConfig.Ambiente)
	payload, err := s.fetchDocumentInfo(ctx, companyCtx, job.Cufe, ambiente)
	if err != nil {
		return err
	}

	eventsJSON, statusJSON, err := mapDocumentInfoResponse(payload)
	if err != nil {
		return err
	}

	syncedAt := time.Now().UTC()
	_, err = s.db.Exec(ctx, `
		UPDATE invoices
		SET dian_response_jsonb = COALESCE(dian_response_jsonb, '{}'::jsonb)
		    || jsonb_build_object(
		        'radian_events', $3::jsonb,
		        'radian_events_synced_at', to_jsonb($4::text),
		        'radian_events_dian_status', $5::jsonb,
		        'radian_events_sync_source', to_jsonb($6::text)
		    ),
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, job.TenantID, job.InvoiceID, eventsJSON, syncedAt.Format(time.RFC3339), statusJSON, job.Source)
	if err != nil {
		return err
	}

	log.Printf("radian_sync ok tenant_id=%s invoice_id=%s cufe=%s events=%d source=%s",
		job.TenantID, job.InvoiceID, truncate(job.Cufe, 16), countEvents(eventsJSON), job.Source)
	return nil
}

func (s *RadianSyncService) fetchDocumentInfo(ctx context.Context, company CompanyContext, cufe, ambiente string) (json.RawMessage, error) {
	endpoint := fmt.Sprintf("%s/api/v1/dian/document-info?uuid=%s&ambiente=%s",
		s.dianAPIURL,
		url.QueryEscape(strings.TrimSpace(cufe)),
		url.QueryEscape(ambiente),
	)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Tenant-ID", company.TenantID.String())
	req.Header.Set("X-Cert-S3-Key", company.DIANConfig.S3CertificateKey)
	req.Header.Set("X-Cert-Password-Secret-Key", company.DIANConfig.SecretsManagerPasswordKey)
	req.Header.Set("X-DIAN-Ambiente", ambiente)

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("DIAN document-info status %d: %s", resp.StatusCode, truncate(string(body), 240))
	}
	if !json.Valid(body) {
		return nil, fmt.Errorf("respuesta DIAN document-info no es JSON valido")
	}
	return json.RawMessage(body), nil
}

type documentInfoResponseDTO struct {
	StatusCode        string                  `json:"statusCode"`
	StatusDescription string                  `json:"statusDescription"`
	DocumentUuid      string                  `json:"documentUuid"`
	Events            []documentInfoEventDTO  `json:"events"`
}

type documentInfoEventDTO struct {
	Code      string `json:"code"`
	Label     string `json:"label"`
	Estado    string `json:"estado"`
	EventUUID string `json:"eventUuid"`
}

func mapDocumentInfoResponse(payload json.RawMessage) (eventsJSON string, statusJSON string, err error) {
	var root documentInfoResponseDTO
	if err = json.Unmarshal(payload, &root); err != nil {
		return "", "", err
	}

	statusBytes, _ := json.Marshal(map[string]string{
		"statusCode":        root.StatusCode,
		"statusDescription": root.StatusDescription,
		"documentUuid":      root.DocumentUuid,
	})

	now := time.Now().UTC().Format(time.RFC3339)
	events := make([]map[string]string, 0, len(root.Events))
	for _, item := range root.Events {
		event := map[string]string{
			"code":   strings.TrimSpace(item.Code),
			"label":  firstNonEmpty(item.Label, "Evento RADIAN"),
			"estado": firstNonEmpty(item.Estado, "REGISTRADO"),
			"at":     now,
			"source": "DIAN_GET_DOCUMENT_INFO",
		}
		if eventUUID := strings.TrimSpace(item.EventUUID); eventUUID != "" {
			event["eventUuid"] = eventUUID
		}
		events = append(events, event)
	}

	eventsBytes, err := json.Marshal(events)
	if err != nil {
		return "", "", err
	}
	return string(eventsBytes), string(statusBytes), nil
}

// FindOpenDocuments devuelve documentos emitidos sin cierre comercial reciente para re-consulta.
func (s *RadianSyncService) FindOpenDocuments(ctx context.Context, tenantID *uuid.UUID, limit int) ([]OpenDocumentCandidate, error) {
	if limit <= 0 {
		limit = 50
	}

	query := fmt.Sprintf(`
		SELECT i.id,
		       i.company_id,
		       i.emission_point_id,
		       COALESCE(NULLIF(i.uuid_cude, ''), i.dian_response_jsonb->>'cufe', i.dian_response_jsonb->>'uuid') AS cufe,
		       COALESCE(NULLIF(c.dian_config->>'ambiente', ''), 'Habilitacion') AS ambiente
		FROM invoices i
		JOIN companies c ON c.id = i.company_id
		WHERE i.emission_point_id IS NOT NULL
		  AND i.created_at >= now() - interval '90 days'
		  AND COALESCE(NULLIF(i.document_kind, ''), 'INVOICE') IN ('INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE')
		  AND btrim(COALESCE(NULLIF(i.uuid_cude, ''), i.dian_response_jsonb->>'cufe', i.dian_response_jsonb->>'uuid', '')) <> ''
		  AND c.is_active = TRUE
		  AND (
		    i.dian_response_jsonb->>'radian_events_synced_at' IS NULL
		    OR (trim(both '"' from i.dian_response_jsonb->>'radian_events_synced_at'))::timestamptz < now() - interval '4 hours'
		  )
		  AND NOT EXISTS (
		    SELECT 1
		    FROM jsonb_array_elements(COALESCE(i.dian_response_jsonb->'radian_events', '[]'::jsonb)) AS ev
		    WHERE ev->>'code' IN (%s)
		  )
	`, closedEventCodes)

	args := make([]any, 0, 2)
	argIdx := 1
	if tenantID != nil {
		query += fmt.Sprintf(" AND i.company_id = $%d", argIdx)
		args = append(args, *tenantID)
		argIdx++
	}
	query += fmt.Sprintf(" ORDER BY i.created_at DESC LIMIT $%d", argIdx)
	args = append(args, limit)

	rows, err := s.db.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	result := make([]OpenDocumentCandidate, 0)
	for rows.Next() {
		var item OpenDocumentCandidate
		if err := rows.Scan(&item.InvoiceID, &item.TenantID, &item.EmissionPointID, &item.Cufe, &item.Ambiente); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

// ListActiveTenantIDs sociedades activas con certificado DIAN provisionado.
func (s *RadianSyncService) ListActiveTenantIDs(ctx context.Context) ([]uuid.UUID, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id
		FROM companies
		WHERE is_active = TRUE
		  AND NULLIF(trim(dian_config->>'s3_certificate_key'), '') IS NOT NULL
		  AND NULLIF(trim(dian_config->>'secrets_manager_password_key'), '') IS NOT NULL
		ORDER BY id
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	ids := make([]uuid.UUID, 0)
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func resolveAmbiente(requestAmbiente, configAmbiente string) string {
	candidate := strings.TrimSpace(requestAmbiente)
	if candidate == "" {
		candidate = strings.TrimSpace(configAmbiente)
	}
	switch strings.ToLower(candidate) {
	case "produccion", "producción":
		return "Produccion"
	case "mock":
		return "Mock"
	default:
		return "Habilitacion"
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max] + "..."
}

func countEvents(eventsJSON string) int {
	var events []json.RawMessage
	if err := json.Unmarshal([]byte(eventsJSON), &events); err != nil {
		return 0
	}
	return len(events)
}

// ShouldEnqueue indica si conviene encolar sync tras emisión exitosa.
func ShouldEnqueue(estadoDian, cufe string) bool {
	if strings.TrimSpace(cufe) == "" {
		return false
	}
	return strings.EqualFold(strings.TrimSpace(estadoDian), "ENVIADO")
}
