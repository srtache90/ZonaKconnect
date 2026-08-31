package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"zonak/microservice-core-go/internal/delivery"
	"zonak/microservice-core-go/internal/reception"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type ctxKey string

const (
	tenantIDKey        ctxKey = "tenant_id"
	emissionPointIDKey ctxKey = "emission_point_id"
)

type app struct {
	db         *pgxpool.Pool
	dianAPIURL string
	httpClient *http.Client
}

type createInvoiceRequest struct {
	Ambiente string          `json:"ambiente"`
	Cliente  invoiceCustomer `json:"cliente"`
	Items    []invoiceItem   `json:"items"`
	XMLBase  string          `json:"xml_base"`
	Totals   json.RawMessage `json:"totals_jsonb"`
}

type createCreditNoteRequest struct {
	Ambiente            string                  `json:"ambiente"`
	CustomizationID     string                  `json:"customization_id"`
	CreditNoteTypeCode  string                  `json:"credit_note_type_code"`
	Cliente             invoiceCustomer         `json:"cliente"`
	FacturaReferencia   dianDocumentReference   `json:"factura_referencia"`
	ConceptosCorreccion []dianCorrectionConcept `json:"conceptos_correccion"`
	Items               []invoiceItem           `json:"items"`
	Totals              json.RawMessage         `json:"totals_jsonb"`
}

type createDebitNoteRequest struct {
	Ambiente            string                  `json:"ambiente"`
	CustomizationID     string                  `json:"customization_id"`
	DebitNoteTypeCode   string                  `json:"debit_note_type_code"`
	Cliente             invoiceCustomer         `json:"cliente"`
	FacturaReferencia   dianDocumentReference   `json:"factura_referencia"`
	ConceptosCorreccion []dianCorrectionConcept `json:"conceptos_correccion"`
	Items               []invoiceItem           `json:"items"`
	Totals              json.RawMessage         `json:"totals_jsonb"`
}

type invoiceCustomer struct {
	TipoIdentificacion   string     `json:"tipo_identificacion"`
	NumeroIdentificacion string     `json:"numero_identificacion"`
	Dv                   string     `json:"dv,omitempty"`
	RazonSocial          string     `json:"razon_social"`
	Email                string     `json:"email"`
	Telefono             string     `json:"telefono,omitempty"`
	Direccion            addressDTO `json:"direccion,omitempty"`
}

type invoiceItem struct {
	Codigo         string    `json:"codigo"`
	Descripcion    string    `json:"descripcion"`
	Cantidad       float64   `json:"cantidad"`
	PrecioUnitario float64   `json:"precio_unitario"`
	Descuento      float64   `json:"descuento"`
	Impuestos      []dianTax `json:"impuestos,omitempty"`
}

type dianNetRequest struct {
	Ambiente    string          `json:"ambiente"`
	Factura     *dianInvoice    `json:"factura,omitempty"`
	NotaCredito *dianCreditNote `json:"notaCredito,omitempty"`
	NotaDebito  *dianDebitNote  `json:"notaDebito,omitempty"`
	XMLBase     string          `json:"xml_base,omitempty"`
}

type DIANConfig struct {
	S3CertificateKey          string `json:"s3_certificate_key"`
	SecretsManagerPasswordKey string `json:"secrets_manager_password_key"`
	Ambiente                  string `json:"ambiente"`
	RegimenFiscal             string `json:"regimen_fiscal"`
	SoftwareID                string `json:"software_id"`
	Pin                       string `json:"pin"`
}

type dianNetResponse struct {
	Status                       string   `json:"status"`
	Exitoso                      bool     `json:"exitoso"`
	EstadoDian                   string   `json:"estado_dian"`
	StatusCode                   string   `json:"statusCode"`
	StatusDescription            string   `json:"statusDescription"`
	StatusMessage                string   `json:"statusMessage"`
	CudeCune                     string   `json:"cufeCune"`
	CUFE                         string   `json:"cufe"`
	CUNE                         string   `json:"cune"`
	UUID                         string   `json:"uuid"`
	TrackID                      string   `json:"trackID"`
	SignedXMLBase64              string   `json:"signedXmlBase64"`
	ApplicationResponseXML       string   `json:"applicationResponseXml"`
	ApplicationResponseXMLBase64 string   `json:"applicationResponseXmlBase64"`
	ZipBase64                    string   `json:"zipBase64"`
	Errores                      []string `json:"errores"`
}

type companyEmissionContext struct {
	NIT                  string
	DV                   string
	RazonSocial          string
	NombreComercial      string
	Email                string
	Telefono             string
	Direccion            addressDTO
	DIANConfig           DIANConfig
	RegimenFiscal        string
	ResolucionDIAN       string
	ClaveTecnica         string
	RangoDesde           int64
	RangoHasta           int64
	VigenciaDesde        time.Time
	VigenciaHasta        time.Time
	EmissionPointAddress string
}

type addressDTO struct {
	CodigoPostal       string `json:"codigoPostal,omitempty"`
	Departamento       string `json:"departamento,omitempty"`
	CodigoDepartamento string `json:"codigoDepartamento,omitempty"`
	Municipio          string `json:"municipio,omitempty"`
	CodigoMunicipio    string `json:"codigoMunicipio,omitempty"`
	DireccionCompleta  string `json:"direccionCompleta,omitempty"`
	Pais               string `json:"pais,omitempty"`
}

type dianInvoice struct {
	TipoDocumento     string            `json:"tipoDocumento"`
	InvoiceTypeCode   string            `json:"invoiceTypeCode"`
	NumeroDocumento   string            `json:"numeroDocumento"`
	FechaEmision      time.Time         `json:"fechaEmision"`
	FechaVencimiento  time.Time         `json:"fechaVencimiento"`
	Moneda            string            `json:"moneda"`
	Emisor            dianParty         `json:"emisor"`
	Cliente           dianCustomer      `json:"cliente"`
	Items             []dianInvoiceItem `json:"items"`
	Totales           dianTotals        `json:"totales"`
	Observaciones     string            `json:"observaciones,omitempty"`
	Notas             []string          `json:"notas,omitempty"`
	ConfiguracionDian dianConfigDTO     `json:"configuracionDian"`
}

type dianCreditNote struct {
	TipoDocumento       string                  `json:"tipoDocumento"`
	CustomizationID     string                  `json:"customizationID"`
	CreditNoteTypeCode  string                  `json:"creditNoteTypeCode"`
	NumeroDocumento     string                  `json:"numeroDocumento"`
	FechaEmision        time.Time               `json:"fechaEmision"`
	Moneda              string                  `json:"moneda"`
	FacturaReferencia   dianDocumentReference   `json:"facturaReferencia"`
	Emisor              dianParty               `json:"emisor"`
	Cliente             dianCustomer            `json:"cliente"`
	ConceptosCorreccion []dianCorrectionConcept `json:"conceptosCorreccion"`
	Items               []dianInvoiceItem       `json:"items"`
	Totales             dianTotals              `json:"totales"`
	Observaciones       string                  `json:"observaciones,omitempty"`
	Notas               []string                `json:"notas,omitempty"`
	ConfiguracionDian   dianConfigDTO           `json:"configuracionDian"`
}

type dianDebitNote struct {
	TipoDocumento       string                  `json:"tipoDocumento"`
	CustomizationID     string                  `json:"customizationID"`
	DebitNoteTypeCode   string                  `json:"debitNoteTypeCode"`
	NumeroDocumento     string                  `json:"numeroDocumento"`
	FechaEmision        time.Time               `json:"fechaEmision"`
	Moneda              string                  `json:"moneda"`
	FacturaReferencia   dianDocumentReference   `json:"facturaReferencia"`
	Emisor              dianParty               `json:"emisor"`
	Cliente             dianCustomer            `json:"cliente"`
	ConceptosCorreccion []dianCorrectionConcept `json:"conceptosCorreccion"`
	Items               []dianInvoiceItem       `json:"items"`
	Totales             dianTotals              `json:"totales"`
	Observaciones       string                  `json:"observaciones,omitempty"`
	Notas               []string                `json:"notas,omitempty"`
	ConfiguracionDian   dianConfigDTO           `json:"configuracionDian"`
}

type dianDocumentReference struct {
	TipoDocumento   string    `json:"tipoDocumento"`
	NumeroDocumento string    `json:"numeroDocumento"`
	FechaEmision    time.Time `json:"fechaEmision"`
	CUFE            string    `json:"cufe"`
	SchemeName      string    `json:"schemeName"`
}

type dianCorrectionConcept struct {
	ReferenceID string `json:"referenceID"`
	Codigo      string `json:"codigo"`
	Descripcion string `json:"descripcion"`
}

type dianParty struct {
	Nit                string     `json:"nit"`
	Dv                 string     `json:"dv"`
	TipoIdentificacion string     `json:"tipoIdentificacion"`
	TipoPersona        string     `json:"tipoPersona"`
	RazonSocial        string     `json:"razonSocial"`
	NombreComercial    string     `json:"nombreComercial"`
	Direccion          addressDTO `json:"direccion"`
	Telefono           string     `json:"telefono"`
	Email              string     `json:"email"`
	RegimenFiscal      string     `json:"regimenFiscal"`
	TributoID          string     `json:"tributoId"`
	TributoNombre      string     `json:"tributoNombre"`
	ActividadEconomica string     `json:"actividadEconomica"`
}

type dianCustomer struct {
	TipoIdentificacion   string     `json:"tipoIdentificacion"`
	NumeroIdentificacion string     `json:"numeroIdentificacion"`
	Dv                   string     `json:"dv"`
	TipoPersona          string     `json:"tipoPersona"`
	RazonSocial          string     `json:"razonSocial"`
	NombreComercial      string     `json:"nombreComercial"`
	Direccion            addressDTO `json:"direccion"`
	Telefono             string     `json:"telefono"`
	Email                string     `json:"email"`
	RegimenFiscal        string     `json:"regimenFiscal"`
	TributoID            string     `json:"tributoId"`
	TributoNombre        string     `json:"tributoNombre"`
}

type dianInvoiceItem struct {
	NumeroLinea    int       `json:"numeroLinea"`
	Codigo         string    `json:"codigo"`
	Descripcion    string    `json:"descripcion"`
	Cantidad       float64   `json:"cantidad"`
	UnidadMedida   string    `json:"unidadMedida"`
	PrecioUnitario float64   `json:"precioUnitario"`
	Descuento      float64   `json:"descuento"`
	Subtotal       float64   `json:"subtotal"`
	Impuestos      []dianTax `json:"impuestos,omitempty"`
	Total          float64   `json:"total"`
}

type dianTax struct {
	Codigo          string  `json:"codigo"`
	Nombre          string  `json:"nombre,omitempty"`
	Tipo            string  `json:"tipo,omitempty"`
	Porcentaje      float64 `json:"porcentaje,omitempty"`
	BaseImponible   float64 `json:"baseImponible,omitempty"`
	Valor           float64 `json:"valor,omitempty"`
	BaseUnitMeasure float64 `json:"baseUnitMeasure,omitempty"`
	UnitCode        string  `json:"unitCode,omitempty"`
	PerUnitAmount   float64 `json:"perUnitAmount,omitempty"`
	EsRetencion     bool    `json:"esRetencion,omitempty"`
}

type dianTotals struct {
	Subtotal        float64 `json:"subtotal"`
	TotalDescuentos float64 `json:"totalDescuentos"`
	TotalImpuestos  float64 `json:"totalImpuestos"`
	Propina         float64 `json:"propina,omitempty"`
	Total           float64 `json:"total"`
}

type dianConfigDTO struct {
	NumeroResolucion string    `json:"numeroResolucion"`
	FechaResolucion  time.Time `json:"fechaResolucion"`
	FechaInicio      time.Time `json:"fechaInicio"`
	FechaFin         time.Time `json:"fechaFin"`
	Prefijo          string    `json:"prefijo"`
	RangoInicio      string    `json:"rangoInicio"`
	RangoFin         string    `json:"rangoFin"`
	TipoAmbiente     string    `json:"tipoAmbiente"`
	SoftwareID       string    `json:"softwareId"`
	Pin              string    `json:"pin"`
	ClaveTecnica     string    `json:"claveTecnica"`
}

type invoiceListItem struct {
	ID                   uuid.UUID `json:"id"`
	Tipo                 string    `json:"tipo"`
	UUIDCude             *string   `json:"uuid_cude"`
	Prefijo              string    `json:"prefijo"`
	Numero               int64     `json:"numero"`
	EstadoDian           string    `json:"estado_dian"`
	XMLS3URL             *string   `json:"xml_s3_url"`
	PDFS3URL             *string   `json:"pdf_s3_url"`
	CreatedAt            time.Time `json:"created_at"`
	UpdatedAt            time.Time `json:"updated_at"`
	DianErrorCode        *string   `json:"dian_error_code"`
	DianErrorDescription *string   `json:"dian_error_description"`
	DianStatusMessage    *string   `json:"dian_status_message"`
	DianErrores          *string   `json:"dian_errores"`
	DianTrackId          *string   `json:"dian_track_id"`
	CustomerEmail        *string   `json:"customer_email"`
	DocumentKind         string    `json:"document_kind"`
}

type updateInvoiceUrlsRequest struct {
	PdfS3URL *string `json:"pdf_s3_url"`
	XmlS3URL *string `json:"xml_s3_url"`
}

type invoiceListResponse struct {
	Page         int               `json:"page"`
	Limit        int               `json:"limit"`
	TotalRecords int64             `json:"total_records"`
	Invoices     []invoiceListItem `json:"invoices"`
}

func main() {
	ctx := context.Background()
	db, err := pgxpool.New(ctx, mustEnv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	a := &app{
		db:         db,
		dianAPIURL: strings.TrimRight(getenv("DIAN_API_URL", getenv("DIAN_NET_URL", "http://dian-net:8080")), "/"),
		httpClient: &http.Client{Timeout: 30 * time.Second},
	}
	receptionHandlers := reception.NewHandlers(
		db,
		getenv("JWT_SECRET", "local-dev-secret-change-before-production-32-bytes-minimum"),
		getenv("AWS_ENDPOINT_URL", getenv("LOCALSTACK_ENDPOINT", "")),
	)

	r := chi.NewRouter()

	// Emisión / documentos: requieren tenant + punto de emisión
	r.Group(func(pr chi.Router) {
		pr.Use(tenantMiddleware)
		pr.Get("/api/v1/invoices", a.handleGetInvoices)
		pr.Post("/api/v1/invoices", a.handleCreateInvoice)
		pr.Post("/api/v1/credit-notes", a.handleCreateCreditNote)
		pr.Post("/api/v1/debit-notes", a.handleCreateDebitNote)
		pr.Post("/api/v1/support-documents", a.handleCreateSupportDocument)
		pr.Post("/api/v1/payroll", a.handleCreatePayroll)
		pr.Get("/api/v1/invoices/{id}/documents/{kind}", a.handleDownloadInvoiceDocument)
		pr.Post("/api/v1/invoices/{id}/reemit", a.handleReemitInvoice)
		pr.Patch("/api/v1/invoices/{id}/urls", a.handleUpdateInvoiceUrls)
		pr.Get("/api/v1/search", a.handleSearchDocuments)
		pr.Get("/api/v1/dashboard/kpis", a.handleDashboardKpis)
	})

	// Recepción / ingestión: solo tenant (sin emission_point)
	r.Group(func(rr chi.Router) {
		rr.Use(tenantOnlyMiddleware)
		rr.Post("/api/v1/reception/sync-imap", receptionHandlers.SyncIMAP)
		rr.Post("/api/v1/reception/test-imap", receptionHandlers.TestIMAP)
		rr.Post("/api/v1/reception/import-xml", receptionHandlers.ImportXML)
		rr.Post("/api/v1/incoming-invoice-emails", receptionHandlers.IncomingEmailWebhook)
	})

	log.Fatal(http.ListenAndServe(":8080", r))
}

func tenantMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tenantID, err := uuid.Parse(r.Header.Get("X-Tenant-ID"))
		if err != nil {
			http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
			return
		}

		emissionPointID, err := uuid.Parse(r.Header.Get("X-Emission-Point-ID"))
		if err != nil {
			http.Error(w, "X-Emission-Point-ID inválido", http.StatusBadRequest)
			return
		}

		ctx := context.WithValue(r.Context(), tenantIDKey, tenantID)
		ctx = context.WithValue(ctx, emissionPointIDKey, emissionPointID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func tenantOnlyMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tenantID, err := uuid.Parse(r.Header.Get("X-Tenant-ID"))
		if err != nil {
			http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), tenantIDKey, tenantID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (a *app) handleGetInvoices(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	q := r.URL.Query()

	page := 1
	if value := q.Get("page"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 {
			http.Error(w, "page inv?lido", http.StatusBadRequest)
			return
		}
		page = parsed
	}

	limit := 20
	if value := q.Get("limit"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 100 {
			http.Error(w, "limit inv?lido", http.StatusBadRequest)
			return
		}
		limit = parsed
	}

	args := []any{tenantID}
	where := []string{"company_id = $1"}

	if estado := q.Get("estado"); estado != "" {
		args = append(args, estado)
		where = append(where, fmt.Sprintf("estado_dian = $%d", len(args)))
	}

	switch tipo := strings.ToUpper(q.Get("tipo")); tipo {
	case "":
	case "RECIBIDA":
		where = append(where, "emission_point_id IS NULL")
	case "EMITIDA":
		where = append(where, "emission_point_id IS NOT NULL")
	default:
		http.Error(w, "tipo inv?lido", http.StatusBadRequest)
		return
	}

	if kind := strings.ToUpper(strings.TrimSpace(q.Get("document_kind"))); kind != "" {
		args = append(args, kind)
		where = append(where, fmt.Sprintf(`COALESCE(NULLIF(document_kind, ''), 'INVOICE') = $%d`, len(args)))
	}

	if ep := strings.TrimSpace(q.Get("emission_point_id")); ep != "" {
		epID, err := uuid.Parse(ep)
		if err != nil {
			http.Error(w, "emission_point_id inválido", http.StatusBadRequest)
			return
		}
		args = append(args, epID)
		where = append(where, fmt.Sprintf("emission_point_id = $%d", len(args)))
	} else if strings.EqualFold(q.Get("tipo"), "EMITIDA") {
		// Si el cliente envía X-Emission-Point-ID, acotar listado emitido al PV activo.
		if epID, ok := r.Context().Value(emissionPointIDKey).(uuid.UUID); ok {
			args = append(args, epID)
			where = append(where, fmt.Sprintf("emission_point_id = $%d", len(args)))
		}
	}

	whereSQL := strings.Join(where, " AND ")

	var totalRecords int64
	if err := a.db.QueryRow(r.Context(), `
		SELECT count(*)
		FROM invoices
		WHERE `+whereSQL, args...).Scan(&totalRecords); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	offset := (page - 1) * limit
	queryArgs := append(args, limit, offset)
	rows, err := a.db.Query(r.Context(), `
		SELECT
			id,
			CASE WHEN emission_point_id IS NULL THEN 'RECIBIDA' ELSE 'EMITIDA' END AS tipo,
			uuid_cude,
			prefijo,
			numero,
			estado_dian,
			xml_s3_url,
			pdf_s3_url,
			created_at,
			updated_at,
			NULLIF(COALESCE(dian_response_jsonb->>'statusCode', dian_response_jsonb->>'status_code', ''), ''),
			NULLIF(COALESCE(
				dian_response_jsonb->>'statusDescription',
				dian_response_jsonb->>'status_description',
				dian_response_jsonb->>'statusMessage',
				''
			), ''),
			NULLIF(COALESCE(
				dian_response_jsonb->>'statusMessage',
				dian_response_jsonb->>'status',
				dian_response_jsonb->>'estado_dian',
				''
			), ''),
			NULLIF(COALESCE(
				(
					SELECT string_agg(elem, ' | ')
					FROM jsonb_array_elements_text(COALESCE(dian_response_jsonb->'errores', '[]'::jsonb)) AS elem
				),
				dian_response_jsonb->>'errores',
				''
			), ''),
			NULLIF(COALESCE(dian_response_jsonb->>'trackID', dian_response_jsonb->>'trackId', dian_response_jsonb->>'uuid', ''), ''),
			NULLIF(COALESCE(raw_dian_payload_jsonb->'cliente'->>'email', raw_dian_payload_jsonb->'customer'->>'email', ''), ''),
			COALESCE(NULLIF(document_kind, ''), CASE
				WHEN raw_dian_payload_jsonb ? 'credit_note_type_code' THEN 'CREDIT_NOTE'
				WHEN raw_dian_payload_jsonb ? 'debit_note_type_code' THEN 'DEBIT_NOTE'
				WHEN raw_dian_payload_jsonb ? 'trabajador' THEN 'PAYROLL'
				WHEN raw_dian_payload_jsonb ? 'proveedor' AND emission_point_id IS NOT NULL THEN 'SUPPORT'
				ELSE 'INVOICE'
			END)
		FROM invoices
		WHERE `+whereSQL+`
		ORDER BY created_at DESC
		LIMIT $`+strconv.Itoa(len(args)+1)+` OFFSET $`+strconv.Itoa(len(args)+2), queryArgs...)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	invoices := make([]invoiceListItem, 0, limit)
	for rows.Next() {
		var invoice invoiceListItem
		if err := rows.Scan(
			&invoice.ID,
			&invoice.Tipo,
			&invoice.UUIDCude,
			&invoice.Prefijo,
			&invoice.Numero,
			&invoice.EstadoDian,
			&invoice.XMLS3URL,
			&invoice.PDFS3URL,
			&invoice.CreatedAt,
			&invoice.UpdatedAt,
			&invoice.DianErrorCode,
			&invoice.DianErrorDescription,
			&invoice.DianStatusMessage,
			&invoice.DianErrores,
			&invoice.DianTrackId,
			&invoice.CustomerEmail,
			&invoice.DocumentKind,
		); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		invoices = append(invoices, invoice)
	}
	if err := rows.Err(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, invoiceListResponse{
		Page:         page,
		Limit:        limit,
		TotalRecords: totalRecords,
		Invoices:     invoices,
	})
}

func (a *app) handleCreateInvoice(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())

	var req createInvoiceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inv?lido", http.StatusBadRequest)
		return
	}

	if len(req.Items) == 0 {
		http.Error(w, "items requeridos", http.StatusBadRequest)
		return
	}

	tx, err := a.db.BeginTx(r.Context(), pgx.TxOptions{})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var prefijo string
	var numeroActual, rangoHasta int64
	err = tx.QueryRow(r.Context(), `
		SELECT prefijo, numero_actual, rango_hasta
		FROM emission_points
		WHERE company_id = $1
		  AND id = $2
		  AND is_active = TRUE
		  AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
		FOR UPDATE
	`, tenantID, emissionPointID).Scan(&prefijo, &numeroActual, &rangoHasta)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "punto de emisi?n no pertenece al tenant o no est? vigente", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	nextNumber := numeroActual + 1
	if nextNumber > rangoHasta {
		http.Error(w, "rango de resoluci?n DIAN agotado", http.StatusBadRequest)
		return
	}

	rawPayload, err := json.Marshal(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	totals := req.Totals
	if len(totals) == 0 {
		totals, err = json.Marshal(map[string]any{"items": req.Items})
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	var invoiceID uuid.UUID
	err = tx.QueryRow(r.Context(), `
		INSERT INTO invoices (
			company_id,
			emission_point_id,
			prefijo,
			numero,
			estado_dian,
			totals_jsonb,
			raw_dian_payload_jsonb,
			document_kind
		)
		SELECT $1, $2, $3, $4, 'PENDIENTE', $5::jsonb, $6::jsonb, 'INVOICE'
		WHERE EXISTS (
			SELECT 1
			FROM emission_points
			WHERE company_id = $1
			  AND id = $2
		)
		RETURNING id
	`, tenantID, emissionPointID, prefijo, nextNumber, totals, rawPayload).Scan(&invoiceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	_, err = tx.Exec(r.Context(), `
		UPDATE emission_points
		SET numero_actual = $3,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, emissionPointID, nextNumber)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	go func(tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, req createInvoiceRequest) {
		ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()

		if _, err := a.emitInvoiceToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
			log.Printf("emitInvoiceToDianNet invoice_id=%s tenant_id=%s error=%v", invoiceID, tenantID, err)
			if shouldPersistPreDianEmissionError(err) {
				a.persistEmissionError(ctx, invoiceID, err)
			}
		}
	}(tenantID, emissionPointID, invoiceID, prefijo, nextNumber, req)

	writeJSON(w, http.StatusAccepted, map[string]any{
		"id":     invoiceID,
		"status": "PERSISTIDA_EN_PROCESAMIENTO",
	})
}

func (a *app) handleCreateCreditNote(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())

	var req createCreditNoteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inv?lido", http.StatusBadRequest)
		return
	}
	if len(req.Items) == 0 {
		http.Error(w, "items requeridos", http.StatusBadRequest)
		return
	}
	if req.FacturaReferencia.NumeroDocumento == "" || req.FacturaReferencia.CUFE == "" {
		http.Error(w, "factura_referencia con numero_documento y cufe es requerida", http.StatusBadRequest)
		return
	}
	if len(req.ConceptosCorreccion) == 0 {
		http.Error(w, "conceptos_correccion requerido", http.StatusBadRequest)
		return
	}

	tx, err := a.db.BeginTx(r.Context(), pgx.TxOptions{})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var prefijoNC string
	var numeroNC, rangoHasta int64
	err = tx.QueryRow(r.Context(), `
		SELECT COALESCE(NULLIF(BTRIM(prefijo_nc), ''), 'NC'),
		       COALESCE(numero_actual_nc, GREATEST(rango_desde - 1, 0)),
		       rango_hasta
		FROM emission_points
		WHERE company_id = $1
		  AND id = $2
		  AND is_active = TRUE
		  AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
		FOR UPDATE
	`, tenantID, emissionPointID).Scan(&prefijoNC, &numeroNC, &rangoHasta)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "punto de emisi?n no pertenece al tenant o no est? vigente", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	prefijo := prefijoNC
	nextNumber := numeroNC + 1
	if nextNumber > rangoHasta {
		http.Error(w, "rango de resoluci?n DIAN agotado", http.StatusBadRequest)
		return
	}

	rawPayload, err := json.Marshal(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	totals := req.Totals
	if len(totals) == 0 {
		totals, err = json.Marshal(map[string]any{"items": req.Items})
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	var invoiceID uuid.UUID
	err = tx.QueryRow(r.Context(), `
		INSERT INTO invoices (
			company_id,
			emission_point_id,
			prefijo,
			numero,
			estado_dian,
			totals_jsonb,
			raw_dian_payload_jsonb,
			document_kind
		)
		VALUES ($1, $2, $3, $4, 'PENDIENTE', $5::jsonb, $6::jsonb, 'CREDIT_NOTE')
		RETURNING id
	`, tenantID, emissionPointID, prefijo, nextNumber, totals, rawPayload).Scan(&invoiceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	_, err = tx.Exec(r.Context(), `
		UPDATE emission_points
		SET numero_actual_nc = $3,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, emissionPointID, nextNumber)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	go func(tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, req createCreditNoteRequest) {
		ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()

		if _, err := a.emitCreditNoteToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
			log.Printf("emitCreditNoteToDianNet invoice_id=%s tenant_id=%s error=%v", invoiceID, tenantID, err)
			if shouldPersistPreDianEmissionError(err) {
				a.persistEmissionError(ctx, invoiceID, err)
			}
		}
	}(tenantID, emissionPointID, invoiceID, prefijo, nextNumber, req)

	writeJSON(w, http.StatusAccepted, map[string]any{
		"id":     invoiceID,
		"status": "NOTA_CREDITO_PERSISTIDA_EN_PROCESAMIENTO",
	})
}

func (a *app) handleCreateDebitNote(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())

	var req createDebitNoteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inv?lido", http.StatusBadRequest)
		return
	}
	if len(req.Items) == 0 {
		http.Error(w, "items requeridos", http.StatusBadRequest)
		return
	}
	if req.FacturaReferencia.NumeroDocumento == "" || req.FacturaReferencia.CUFE == "" {
		http.Error(w, "factura_referencia con numero_documento y cufe es requerida", http.StatusBadRequest)
		return
	}
	if len(req.ConceptosCorreccion) == 0 {
		http.Error(w, "conceptos_correccion requerido", http.StatusBadRequest)
		return
	}

	tx, err := a.db.BeginTx(r.Context(), pgx.TxOptions{})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var prefijoND string
	var numeroND, rangoHasta int64
	err = tx.QueryRow(r.Context(), `
		SELECT COALESCE(NULLIF(BTRIM(prefijo_nd), ''), 'ND'),
		       COALESCE(numero_actual_nd, GREATEST(rango_desde - 1, 0)),
		       rango_hasta
		FROM emission_points
		WHERE company_id = $1
		  AND id = $2
		  AND is_active = TRUE
		  AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
		FOR UPDATE
	`, tenantID, emissionPointID).Scan(&prefijoND, &numeroND, &rangoHasta)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "punto de emisi?n no pertenece al tenant o no est? vigente", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	nextNumber := numeroND + 1
	if nextNumber > rangoHasta {
		http.Error(w, "rango de resoluci?n DIAN agotado", http.StatusBadRequest)
		return
	}

	rawPayload, err := json.Marshal(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	totals := req.Totals
	if len(totals) == 0 {
		totals, err = json.Marshal(map[string]any{"items": req.Items})
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	var invoiceID uuid.UUID
	err = tx.QueryRow(r.Context(), `
		INSERT INTO invoices (
			company_id,
			emission_point_id,
			prefijo,
			numero,
			estado_dian,
			totals_jsonb,
			raw_dian_payload_jsonb,
			document_kind
		)
		VALUES ($1, $2, $3, $4, 'PENDIENTE', $5::jsonb, $6::jsonb, 'DEBIT_NOTE')
		RETURNING id
	`, tenantID, emissionPointID, prefijoND, nextNumber, totals, rawPayload).Scan(&invoiceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	_, err = tx.Exec(r.Context(), `
		UPDATE emission_points
		SET numero_actual_nd = $3,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, emissionPointID, nextNumber)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	go func(tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, req createDebitNoteRequest) {
		ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()
		if _, err := a.emitDebitNoteToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
			log.Printf("emitDebitNoteToDianNet invoice_id=%s tenant_id=%s error=%v", invoiceID, tenantID, err)
			if shouldPersistPreDianEmissionError(err) {
				a.persistEmissionError(ctx, invoiceID, err)
			}
		}
	}(tenantID, emissionPointID, invoiceID, prefijoND, nextNumber, req)

	writeJSON(w, http.StatusAccepted, map[string]any{
		"id":     invoiceID,
		"status": "NOTA_DEBITO_PERSISTIDA_EN_PROCESAMIENTO",
	})
}

func (a *app) handleReemitInvoice(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())
	invoiceID, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		http.Error(w, "id inválido", http.StatusBadRequest)
		return
	}

	var prefijo string
	var numero int64
	var estadoDian string
	var rawPayload json.RawMessage
	var documentKind string
	err = a.db.QueryRow(r.Context(), `
		SELECT prefijo, numero, estado_dian, raw_dian_payload_jsonb, COALESCE(NULLIF(document_kind, ''), 'INVOICE')
		FROM invoices
		WHERE company_id = $1
		  AND id = $2
		  AND emission_point_id = $3
	`, tenantID, invoiceID, emissionPointID).Scan(&prefijo, &numero, &estadoDian, &rawPayload, &documentKind)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "factura no encontrada", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	_, _ = a.db.Exec(r.Context(), `
		UPDATE invoices
		SET estado_dian = 'EN_REINTENTO', updated_at = now()
		WHERE company_id = $1 AND id = $2
	`, tenantID, invoiceID)

	go func(
		tenantID, emissionPointID, invoiceID uuid.UUID,
		prefijo string,
		numero int64,
		rawPayload json.RawMessage,
		documentKind string,
	) {
		ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()
		if documentKind == "CREDIT_NOTE" || strings.Contains(string(rawPayload), "credit_note_type_code") {
			var req createCreditNoteRequest
			if err := json.Unmarshal(rawPayload, &req); err != nil {
				log.Printf("reemit NC unmarshal invoice_id=%s error=%v", invoiceID, err)
				return
			}
			if _, err := a.emitCreditNoteToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
				log.Printf("reemit NC invoice_id=%s error=%v", invoiceID, err)
				if shouldPersistPreDianEmissionError(err) {
					a.persistEmissionError(ctx, invoiceID, err)
				}
			}
			return
		}
		if documentKind == "DEBIT_NOTE" || strings.Contains(string(rawPayload), "debit_note_type_code") {
			var req createDebitNoteRequest
			if err := json.Unmarshal(rawPayload, &req); err != nil {
				log.Printf("reemit ND unmarshal invoice_id=%s error=%v", invoiceID, err)
				return
			}
			if _, err := a.emitDebitNoteToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
				log.Printf("reemit ND invoice_id=%s error=%v", invoiceID, err)
				if shouldPersistPreDianEmissionError(err) {
					a.persistEmissionError(ctx, invoiceID, err)
				}
			}
			return
		}
		var req createInvoiceRequest
		if err := json.Unmarshal(rawPayload, &req); err != nil {
			log.Printf("reemit FV unmarshal invoice_id=%s error=%v", invoiceID, err)
			return
		}
		if _, err := a.emitInvoiceToDianNet(ctx, tenantID, emissionPointID, invoiceID, prefijo, numero, req); err != nil {
			log.Printf("reemit FV invoice_id=%s error=%v", invoiceID, err)
			if shouldPersistPreDianEmissionError(err) {
				a.persistEmissionError(ctx, invoiceID, err)
			}
		}
	}(tenantID, emissionPointID, invoiceID, prefijo, numero, rawPayload, documentKind)

	writeJSON(w, http.StatusAccepted, map[string]any{
		"id":     invoiceID,
		"status": "EN_REINTENTO",
	})
}

func (a *app) handleUpdateInvoiceUrls(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	invoiceID, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		http.Error(w, "id inválido", http.StatusBadRequest)
		return
	}
	var req updateInvoiceUrlsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inválido", http.StatusBadRequest)
		return
	}
	_, err = a.db.Exec(r.Context(), `
		UPDATE invoices
		SET pdf_s3_url = COALESCE($3, pdf_s3_url),
		    xml_s3_url = COALESCE($4, xml_s3_url),
		    updated_at = now()
		WHERE company_id = $1 AND id = $2
	`, tenantID, invoiceID, req.PdfS3URL, req.XmlS3URL)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": invoiceID, "status": "URLS_UPDATED"})
}

func (a *app) allocateNextNumber(ctx context.Context, tenantID, emissionPointID uuid.UUID) (prefijo string, nextNumber int64, err error) {
	return a.allocateNextNumberByKind(ctx, tenantID, emissionPointID, "INVOICE")
}

func (a *app) allocateNextNumberByKind(ctx context.Context, tenantID, emissionPointID uuid.UUID, documentKind string) (prefijo string, nextNumber int64, err error) {
	tx, err := a.db.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return "", 0, err
	}
	defer tx.Rollback(ctx)

	var prefijoFE, prefijoNC, prefijoND string
	var numeroFE, numeroNC, numeroND, rangoHasta int64
	err = tx.QueryRow(ctx, `
		SELECT prefijo,
		       numero_actual,
		       COALESCE(NULLIF(BTRIM(prefijo_nc), ''), 'NC'),
		       COALESCE(numero_actual_nc, GREATEST(rango_desde - 1, 0)),
		       COALESCE(NULLIF(BTRIM(prefijo_nd), ''), 'ND'),
		       COALESCE(numero_actual_nd, GREATEST(rango_desde - 1, 0)),
		       rango_hasta
		FROM emission_points
		WHERE company_id = $1 AND id = $2 AND is_active = TRUE
		  AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
		FOR UPDATE
	`, tenantID, emissionPointID).Scan(&prefijoFE, &numeroFE, &prefijoNC, &numeroNC, &prefijoND, &numeroND, &rangoHasta)
	if err != nil {
		return "", 0, err
	}

	var updateSQL string
	switch strings.ToUpper(strings.TrimSpace(documentKind)) {
	case "CREDIT_NOTE":
		prefijo = prefijoNC
		nextNumber = numeroNC + 1
		updateSQL = `UPDATE emission_points SET numero_actual_nc = $3, updated_at = now() WHERE company_id = $1 AND id = $2`
	case "DEBIT_NOTE":
		prefijo = prefijoND
		nextNumber = numeroND + 1
		updateSQL = `UPDATE emission_points SET numero_actual_nd = $3, updated_at = now() WHERE company_id = $1 AND id = $2`
	default:
		prefijo = prefijoFE
		nextNumber = numeroFE + 1
		updateSQL = `UPDATE emission_points SET numero_actual = $3, updated_at = now() WHERE company_id = $1 AND id = $2`
	}

	if nextNumber > rangoHasta {
		return "", 0, errors.New("rango de resolución DIAN agotado")
	}
	if _, err = tx.Exec(ctx, updateSQL, tenantID, emissionPointID, nextNumber); err != nil {
		return "", 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", 0, err
	}
	return prefijo, nextNumber, nil
}

func (a *app) handleCreateSupportDocument(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())
	var req map[string]any
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inválido", http.StatusBadRequest)
		return
	}
	prefijo, nextNumber, err := a.allocateNextNumber(r.Context(), tenantID, emissionPointID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	rawPayload, _ := json.Marshal(req)
	totals, _ := json.Marshal(req["totals_jsonb"])
	if string(totals) == "null" || len(totals) == 0 {
		totals = []byte(`{}`)
	}
	var invoiceID uuid.UUID
	err = a.db.QueryRow(r.Context(), `
		INSERT INTO invoices (company_id, emission_point_id, prefijo, numero, estado_dian, totals_jsonb, raw_dian_payload_jsonb, document_kind)
		VALUES ($1, $2, $3, $4, 'PENDIENTE', $5::jsonb, $6::jsonb, 'SUPPORT')
		RETURNING id
	`, tenantID, emissionPointID, prefijo, nextNumber, totals, rawPayload).Scan(&invoiceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	go a.emitGenericToDianNet(tenantID, emissionPointID, invoiceID, "/api/v1/emit/support-document", req)
	writeJSON(w, http.StatusAccepted, map[string]any{"id": invoiceID, "status": "SUPPORT_PERSISTIDO_EN_PROCESAMIENTO", "prefijo": prefijo, "numero": nextNumber})
}

func (a *app) handleCreatePayroll(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	emissionPointID := mustEmissionPointID(r.Context())
	var req map[string]any
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "payload inválido", http.StatusBadRequest)
		return
	}
	prefijo, nextNumber, err := a.allocateNextNumber(r.Context(), tenantID, emissionPointID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	rawPayload, _ := json.Marshal(req)
	totals, _ := json.Marshal(req["totals_jsonb"])
	if string(totals) == "null" || len(totals) == 0 {
		totals = []byte(`{}`)
	}
	var invoiceID uuid.UUID
	err = a.db.QueryRow(r.Context(), `
		INSERT INTO invoices (company_id, emission_point_id, prefijo, numero, estado_dian, totals_jsonb, raw_dian_payload_jsonb, document_kind)
		VALUES ($1, $2, $3, $4, 'PENDIENTE', $5::jsonb, $6::jsonb, 'PAYROLL')
		RETURNING id
	`, tenantID, emissionPointID, prefijo, nextNumber, totals, rawPayload).Scan(&invoiceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	go a.emitGenericToDianNet(tenantID, emissionPointID, invoiceID, "/api/v1/emit/payroll", req)
	writeJSON(w, http.StatusAccepted, map[string]any{"id": invoiceID, "status": "PAYROLL_PERSISTIDO_EN_PROCESAMIENTO", "prefijo": prefijo, "numero": nextNumber})
}

func (a *app) emitGenericToDianNet(tenantID, emissionPointID, invoiceID uuid.UUID, path string, payload map[string]any) {
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()
	emissionCtx, err := a.loadCompanyEmissionContext(ctx, tenantID, emissionPointID)
	if err != nil {
		log.Printf("emitGeneric load context invoice_id=%s error=%v", invoiceID, err)
		return
	}
	requestAmbiente := ""
	if raw, ok := payload["ambiente"]; ok {
		requestAmbiente = fmt.Sprint(raw)
	}
	ambiente := resolveDianAmbiente(requestAmbiente, emissionCtx.DIANConfig.Ambiente)
	payload["ambiente"] = ambiente
	body, _ := json.Marshal(payload)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.dianAPIURL+path, bytes.NewReader(body))
	if err != nil {
		log.Printf("emitGeneric request invoice_id=%s error=%v", invoiceID, err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", tenantID.String())
	req.Header.Set("X-Cert-S3-Key", emissionCtx.DIANConfig.S3CertificateKey)
	req.Header.Set("X-Cert-Password-Secret-Key", emissionCtx.DIANConfig.SecretsManagerPasswordKey)
	req.Header.Set("X-DIAN-Ambiente", ambiente)
	resp, err := a.httpClient.Do(req)
	if err != nil {
		log.Printf("emitGeneric do invoice_id=%s error=%v", invoiceID, err)
		_, _ = a.db.Exec(ctx, `UPDATE invoices SET estado_dian = 'ERROR_DIAN_NET', updated_at = now() WHERE id = $1`, invoiceID)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(resp.Body)
	raw := json.RawMessage(respBody)
	if !json.Valid(raw) {
		raw, _ = json.Marshal(map[string]string{"raw_body": string(respBody)})
	}
	var dianResp dianNetResponse
	_ = json.Unmarshal(raw, &dianResp)
	estado := resolveDianStatus(dianResp, resp.StatusCode)
	cude := resolveDianIdentifier(dianResp)
	_, _ = a.db.Exec(ctx, `
		UPDATE invoices
		SET estado_dian = $2, uuid_cude = NULLIF($3, ''), dian_response_jsonb = $4::jsonb, updated_at = now()
		WHERE id = $1
	`, invoiceID, estado, cude, raw)
}

func (a *app) handleSearchDocuments(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) < 2 {
		writeJSON(w, http.StatusOK, map[string]any{"results": []any{}})
		return
	}
	like := "%" + q + "%"
	rows, err := a.db.Query(r.Context(), `
		SELECT id,
		       CASE WHEN emission_point_id IS NULL THEN 'RECIBIDA' ELSE 'EMITIDA' END,
		       prefijo, numero, COALESCE(uuid_cude, ''), estado_dian,
		       COALESCE(NULLIF(document_kind, ''), 'INVOICE'),
		       COALESCE(raw_dian_payload_jsonb->'cliente'->>'razon_social',
		                raw_dian_payload_jsonb->'proveedor'->>'razon_social', '')
		FROM invoices
		WHERE company_id = $1
		  AND (
		    prefijo ILIKE $2
		    OR CAST(numero AS TEXT) ILIKE $2
		    OR (prefijo || CAST(numero AS TEXT)) ILIKE $2
		    OR COALESCE(uuid_cude, '') ILIKE $2
		    OR COALESCE(raw_dian_payload_jsonb->'cliente'->>'razon_social', '') ILIKE $2
		    OR COALESCE(raw_dian_payload_jsonb->'cliente'->>'numero_identificacion', '') ILIKE $2
		    OR COALESCE(raw_dian_payload_jsonb->'proveedor'->>'nit', '') ILIKE $2
		  )
		ORDER BY created_at DESC
		LIMIT 30
	`, tenantID, like)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rows.Close()
	results := make([]map[string]any, 0)
	for rows.Next() {
		var id uuid.UUID
		var tipo, prefijo, cufe, estado, kind, name string
		var numero int64
		if err := rows.Scan(&id, &tipo, &prefijo, &numero, &cufe, &estado, &kind, &name); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		results = append(results, map[string]any{
			"id": id, "tipo": tipo, "prefijo": prefijo, "numero": numero,
			"uuid_cude": cufe, "estado_dian": estado, "document_kind": kind, "nombre": name,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func (a *app) handleDashboardKpis(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	var emittedToday, emittedMonth, accepted, rejected, pendingReception, supportCount, payrollCount int64
	_ = a.db.QueryRow(r.Context(), `
		SELECT
		  COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND created_at::date = CURRENT_DATE),
		  COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND date_trunc('month', created_at) = date_trunc('month', now())),
		  COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND estado_dian IN ('ENVIADO', 'Documento Validado Exitosamente')),
		  COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND estado_dian IN ('RECHAZADO_DIAN', 'ERROR_DIAN_NET', 'RECHAZADO')),
		  COUNT(*) FILTER (WHERE COALESCE(document_kind, 'INVOICE') = 'SUPPORT'),
		  COUNT(*) FILTER (WHERE COALESCE(document_kind, 'INVOICE') = 'PAYROLL')
		FROM invoices
		WHERE company_id = $1
	`, tenantID).Scan(&emittedToday, &emittedMonth, &accepted, &rejected, &supportCount, &payrollCount)

	_ = a.db.QueryRow(r.Context(), `
		SELECT COUNT(*)
		FROM received_invoices
		WHERE company_id = $1
		  AND estado_dian IN ('PENDIENTE', 'ACUSADA_085', 'RECIBIDA_086')
	`, tenantID).Scan(&pendingReception)

	writeJSON(w, http.StatusOK, map[string]any{
		"emitted_today":       emittedToday,
		"emitted_month":       emittedMonth,
		"accepted_dian":       accepted,
		"rejected_dian":       rejected,
		"pending_reception":   pendingReception,
		"support_documents":   supportCount,
		"payroll_documents":   payrollCount,
	})
}

func (a *app) persistEmissionError(ctx context.Context, invoiceID uuid.UUID, err error) {
	if err == nil {
		return
	}
	payload, marshalErr := json.Marshal(map[string]any{
		"exitoso":           false,
		"estado_dian":       "ERROR_DIAN_NET",
		"statusDescription": err.Error(),
		"errores":           []string{err.Error()},
	})
	if marshalErr != nil {
		log.Printf("persistEmissionError marshal invoice_id=%s error=%v", invoiceID, marshalErr)
		return
	}
	tag, execErr := a.db.Exec(ctx, `
		UPDATE invoices
		SET estado_dian = 'ERROR_DIAN_NET',
		    dian_response_jsonb = $2::jsonb,
		    updated_at = now()
		WHERE id = $1
	`, invoiceID, payload)
	if execErr != nil {
		log.Printf("persistEmissionError update invoice_id=%s error=%v", invoiceID, execErr)
		return
	}
	if tag.RowsAffected() == 0 {
		log.Printf("persistEmissionError update invoice_id=%s affected 0 rows", invoiceID)
	}
}

func (a *app) emitInvoiceToDianNet(ctx context.Context, tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, invoiceReq createInvoiceRequest) (json.RawMessage, error) {
	emissionCtx, err := a.loadCompanyEmissionContext(ctx, tenantID, emissionPointID)
	if err != nil {
		return nil, err
	}
	if emissionCtx.DIANConfig.S3CertificateKey == "" || emissionCtx.DIANConfig.SecretsManagerPasswordKey == "" {
		return nil, fmt.Errorf("configuracion DIAN incompleta tenant_id=%s", tenantID)
	}

	ambiente := resolveDianAmbiente(invoiceReq.Ambiente, emissionCtx.DIANConfig.Ambiente)

	factura := buildDianInvoice(invoiceReq, emissionCtx, prefijo, numero, ambiente)
	body, err := json.Marshal(dianNetRequest{
		Ambiente: ambiente,
		Factura:  &factura,
	})
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.dianAPIURL+"/api/v1/emit/invoice", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", tenantID.String())
	req.Header.Set("X-Cert-S3-Key", emissionCtx.DIANConfig.S3CertificateKey)
	req.Header.Set("X-Cert-Password-Secret-Key", emissionCtx.DIANConfig.SecretsManagerPasswordKey)
	req.Header.Set("X-DIAN-Ambiente", ambiente)

	resp, err := a.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	payload := json.RawMessage(respBody)
	if !json.Valid(payload) {
		payload, _ = json.Marshal(map[string]string{"raw_body": string(respBody)})
	}

	var dianResp dianNetResponse
	_ = json.Unmarshal(payload, &dianResp)

	estadoDian := resolveDianStatus(dianResp, resp.StatusCode)
	uuidCude := resolveDianIdentifier(dianResp)
	xmlURL := fmt.Sprintf("/api/v1/invoices/%s/documents/signed-xml", invoiceID)
	pdfURL := fmt.Sprintf("/api/v1/invoices/%s/documents/pdf", invoiceID)

	_, err = a.db.Exec(ctx, `
		UPDATE invoices
		SET estado_dian = $3,
		    uuid_cude = NULLIF($4, ''),
		    xml_s3_url = $5,
		    pdf_s3_url = $6,
		    dian_response_jsonb = $7::jsonb,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, invoiceID, estadoDian, uuidCude, xmlURL, pdfURL, payload)
	if err != nil {
		return payload, err
	}

	if err := dianNetHTTPError(resp.StatusCode, payload); err != nil {
		return payload, err
	}
	return payload, nil
}

func dianNetHTTPError(httpStatus int, payload json.RawMessage) error {
	if httpStatus >= 200 && httpStatus <= 299 {
		return nil
	}
	// DIAN_NET responde 502 con JSON de rechazo DIAN; no es fallo de transporte.
	if httpStatus == 502 && json.Valid(payload) {
		return nil
	}
	return fmt.Errorf("DIAN_API status %d", httpStatus)
}

func (a *app) emitCreditNoteToDianNet(ctx context.Context, tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, creditNoteReq createCreditNoteRequest) (json.RawMessage, error) {
	emissionCtx, err := a.loadCompanyEmissionContext(ctx, tenantID, emissionPointID)
	if err != nil {
		return nil, err
	}
	if emissionCtx.DIANConfig.S3CertificateKey == "" || emissionCtx.DIANConfig.SecretsManagerPasswordKey == "" {
		return nil, fmt.Errorf("configuracion DIAN incompleta tenant_id=%s", tenantID)
	}

	ambiente := resolveDianAmbiente(creditNoteReq.Ambiente, emissionCtx.DIANConfig.Ambiente)

	notaCredito := buildDianCreditNote(creditNoteReq, emissionCtx, prefijo, numero, ambiente)
	body, err := json.Marshal(dianNetRequest{
		Ambiente:    ambiente,
		NotaCredito: &notaCredito,
	})
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.dianAPIURL+"/api/v1/emit/credit-note", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", tenantID.String())
	req.Header.Set("X-Cert-S3-Key", emissionCtx.DIANConfig.S3CertificateKey)
	req.Header.Set("X-Cert-Password-Secret-Key", emissionCtx.DIANConfig.SecretsManagerPasswordKey)
	req.Header.Set("X-DIAN-Ambiente", ambiente)

	resp, err := a.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	payload := json.RawMessage(respBody)
	if !json.Valid(payload) {
		payload, _ = json.Marshal(map[string]string{"raw_body": string(respBody)})
	}

	var dianResp dianNetResponse
	_ = json.Unmarshal(payload, &dianResp)

	estadoDian := resolveDianStatus(dianResp, resp.StatusCode)
	uuidCude := resolveDianIdentifier(dianResp)
	xmlURL := fmt.Sprintf("/api/v1/invoices/%s/documents/signed-xml", invoiceID)
	pdfURL := fmt.Sprintf("/api/v1/invoices/%s/documents/pdf", invoiceID)

	_, err = a.db.Exec(ctx, `
		UPDATE invoices
		SET estado_dian = $3,
		    uuid_cude = NULLIF($4, ''),
		    xml_s3_url = $5,
		    pdf_s3_url = $6,
		    dian_response_jsonb = $7::jsonb,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, invoiceID, estadoDian, uuidCude, xmlURL, pdfURL, payload)
	if err != nil {
		return payload, err
	}

	if err := dianNetHTTPError(resp.StatusCode, payload); err != nil {
		return payload, err
	}
	return payload, nil
}

func (a *app) emitDebitNoteToDianNet(ctx context.Context, tenantID, emissionPointID, invoiceID uuid.UUID, prefijo string, numero int64, debitNoteReq createDebitNoteRequest) (json.RawMessage, error) {
	emissionCtx, err := a.loadCompanyEmissionContext(ctx, tenantID, emissionPointID)
	if err != nil {
		return nil, err
	}
	if emissionCtx.DIANConfig.S3CertificateKey == "" || emissionCtx.DIANConfig.SecretsManagerPasswordKey == "" {
		return nil, fmt.Errorf("configuracion DIAN incompleta tenant_id=%s", tenantID)
	}

	ambiente := resolveDianAmbiente(debitNoteReq.Ambiente, emissionCtx.DIANConfig.Ambiente)

	notaDebito := buildDianDebitNote(debitNoteReq, emissionCtx, prefijo, numero, ambiente)
	body, err := json.Marshal(dianNetRequest{
		Ambiente:   ambiente,
		NotaDebito: &notaDebito,
	})
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.dianAPIURL+"/api/v1/emit/debit-note", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", tenantID.String())
	req.Header.Set("X-Cert-S3-Key", emissionCtx.DIANConfig.S3CertificateKey)
	req.Header.Set("X-Cert-Password-Secret-Key", emissionCtx.DIANConfig.SecretsManagerPasswordKey)
	req.Header.Set("X-DIAN-Ambiente", ambiente)

	resp, err := a.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	payload := json.RawMessage(respBody)
	if !json.Valid(payload) {
		payload, _ = json.Marshal(map[string]string{"raw_body": string(respBody)})
	}

	var dianResp dianNetResponse
	_ = json.Unmarshal(payload, &dianResp)

	estadoDian := resolveDianStatus(dianResp, resp.StatusCode)
	uuidCude := resolveDianIdentifier(dianResp)
	xmlURL := fmt.Sprintf("/api/v1/invoices/%s/documents/signed-xml", invoiceID)
	pdfURL := fmt.Sprintf("/api/v1/invoices/%s/documents/pdf", invoiceID)

	_, err = a.db.Exec(ctx, `
		UPDATE invoices
		SET estado_dian = $3,
		    uuid_cude = NULLIF($4, ''),
		    xml_s3_url = $5,
		    pdf_s3_url = $6,
		    dian_response_jsonb = $7::jsonb,
		    updated_at = now()
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, invoiceID, estadoDian, uuidCude, xmlURL, pdfURL, payload)
	if err != nil {
		return payload, err
	}

	if err := dianNetHTTPError(resp.StatusCode, payload); err != nil {
		return payload, err
	}
	return payload, nil
}

func shouldPersistPreDianEmissionError(err error) bool {
	if err == nil {
		return false
	}
	return !strings.HasPrefix(err.Error(), "DIAN_API status ")
}

func (a *app) loadCompanyEmissionContext(ctx context.Context, tenantID, emissionPointID uuid.UUID) (companyEmissionContext, error) {
	var result companyEmissionContext
	var direccionRaw, dianConfigRaw json.RawMessage

	err := a.db.QueryRow(ctx, `
		SELECT
			c.nit,
			c.dv,
			c.razon_social,
			COALESCE(c.nombre_comercial, ''),
			COALESCE(c.email::text, ''),
			COALESCE(c.telefono, ''),
			c.direccion,
			c.dian_config,
			ep.resolucion_dian,
			COALESCE(ep.clave_tecnica, ''),
			ep.rango_desde,
			ep.rango_hasta,
			ep.vigencia_desde,
			ep.vigencia_hasta,
			COALESCE(ep.direccion, '')
		FROM companies c
		JOIN emission_points ep ON ep.company_id = c.id
		WHERE c.id = $1
		  AND ep.id = $2
		  AND c.is_active = TRUE
		  AND ep.is_active = TRUE
	`, tenantID, emissionPointID).Scan(
		&result.NIT,
		&result.DV,
		&result.RazonSocial,
		&result.NombreComercial,
		&result.Email,
		&result.Telefono,
		&direccionRaw,
		&dianConfigRaw,
		&result.ResolucionDIAN,
		&result.ClaveTecnica,
		&result.RangoDesde,
		&result.RangoHasta,
		&result.VigenciaDesde,
		&result.VigenciaHasta,
		&result.EmissionPointAddress,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return result, fmt.Errorf("tenant_id=%s o emission_point_id=%s no existe o esta inactivo", tenantID, emissionPointID)
		}
		return result, err
	}

	_ = json.Unmarshal(direccionRaw, &result.Direccion)
	if result.Direccion.DireccionCompleta == "" {
		result.Direccion.DireccionCompleta = result.EmissionPointAddress
	}
	result.Direccion = defaultAddress(result.Direccion)

	if err := json.Unmarshal(dianConfigRaw, &result.DIANConfig); err != nil {
		return result, err
	}
	result.RegimenFiscal = normalizeRegimenFiscal(result.DIANConfig.RegimenFiscal)
	return result, nil
}

func normalizeRegimenFiscal(value string) string {
	allowed := map[string]struct{}{
		"O-13": {}, "O-15": {}, "O-23": {}, "O-47": {}, "ZZ": {},
	}
	legacy := map[string]string{
		"O-48": "ZZ",
		"O-49": "ZZ",
		"O-99": "ZZ",
		"O-33": "",
	}
	parts := strings.Split(strings.ToUpper(strings.TrimSpace(value)), ";")
	normalized := make([]string, 0, len(parts))
	seen := make(map[string]struct{}, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		if mapped, ok := legacy[token]; ok {
			if mapped == "" {
				continue
			}
			token = mapped
		}
		if _, ok := allowed[token]; !ok {
			continue
		}
		if _, dup := seen[token]; dup {
			continue
		}
		seen[token] = struct{}{}
		normalized = append(normalized, token)
	}
	if len(normalized) == 0 {
		return "ZZ"
	}
	return strings.Join(normalized, ";")
}

func defaultRegimenFiscalAdquirente(tipoIdentificacion string) string {
	if normalizeDianIdentificationType(tipoIdentificacion) == "31" {
		return "R-99-PJ"
	}
	return "R-99-PN"
}

func describeConceptoNotaCredito(codigo string) string {
	switch strings.TrimSpace(codigo) {
	case "2":
		return "Anulación de factura electrónica"
	case "3":
		return "Rebaja  o descuento parcial o total"
	case "4":
		return "Ajuste de precio"
	case "5":
		return "Otros"
	default:
		return "Devolución parcial de los bienes y/o no aceptación parcial del servicio"
	}
}

func buildDianEmisor(emissionCtx companyEmissionContext) dianParty {
	nit := calcularSoloDigitosNIT(emissionCtx.NIT)
	dv := strings.TrimSpace(emissionCtx.DV)
	if dv == "" {
		dv = calcularDVNIT(nit)
	}
	return dianParty{
		Nit:                nit,
		Dv:                 dv,
		TipoIdentificacion: "31",
		TipoPersona:        "1",
		RazonSocial:        strings.TrimSpace(emissionCtx.RazonSocial),
		NombreComercial:    defaultString(emissionCtx.NombreComercial, emissionCtx.RazonSocial),
		Direccion:          defaultAddress(emissionCtx.Direccion),
		Telefono:           emissionCtx.Telefono,
		Email:              emissionCtx.Email,
		RegimenFiscal:      normalizeRegimenFiscal(emissionCtx.RegimenFiscal),
		TributoID:          "01",
		TributoNombre:      "IVA",
		ActividadEconomica: "5611",
	}
}

func buildDianCustomer(c invoiceCustomer) dianCustomer {
	numeroIdentificacion := calcularSoloDigitosNIT(strings.TrimSpace(c.NumeroIdentificacion))
	if numeroIdentificacion == "" {
		numeroIdentificacion = "222222222222"
	}
	tipoIdentificacion := normalizeDianIdentificationType(c.TipoIdentificacion)
	dv := strings.TrimSpace(c.Dv)
	if tipoIdentificacion == "31" {
		dv = calcularDVNIT(numeroIdentificacion)
	} else if dv == "" {
		dv = "0"
	}
	razonSocial := strings.TrimSpace(defaultString(c.RazonSocial, "Consumidor final"))
	razonSocial = strings.Join(strings.Fields(razonSocial), " ")
	regimenFiscal := defaultRegimenFiscalAdquirente(tipoIdentificacion)
	tributoID := "ZZ"
	tributoNombre := "No aplica"
	if tipoIdentificacion == "31" {
		tributoID = "01"
		tributoNombre = "IVA"
	}
	tipoPersona := "2"
	if tipoIdentificacion == "31" {
		tipoPersona = "1"
	}
	return dianCustomer{
		TipoIdentificacion:   tipoIdentificacion,
		NumeroIdentificacion: numeroIdentificacion,
		Dv:                   dv,
		TipoPersona:          tipoPersona,
		RazonSocial:          razonSocial,
		NombreComercial:      razonSocial,
		Direccion:            defaultAddress(c.Direccion),
		Telefono:             c.Telefono,
		Email:                c.Email,
		RegimenFiscal:        regimenFiscal,
		TributoID:            tributoID,
		TributoNombre:        tributoNombre,
	}
}

func buildDianInvoice(req createInvoiceRequest, emissionCtx companyEmissionContext, prefijo string, numero int64, ambiente string) dianInvoice {
	sourceItems := ensureItemTaxes(filterOutPropinaLines(req.Items), req.Totals)
	items := make([]dianInvoiceItem, 0, len(sourceItems))
	for i, item := range sourceItems {
		quantity := defaultFloat(item.Cantidad, 1)
		subtotal := quantity*item.PrecioUnitario - item.Descuento
		if subtotal < 0 {
			subtotal = 0
		}
		taxes := normalizeDianTaxes(item.Impuestos, subtotal, quantity, "94")
		items = append(items, dianInvoiceItem{
			NumeroLinea:    i + 1,
			Codigo:         defaultString(item.Codigo, fmt.Sprintf("ITEM-%03d", i+1)),
			Descripcion:    defaultString(item.Descripcion, "Item facturado"),
			Cantidad:       quantity,
			UnidadMedida:   "94",
			PrecioUnitario: item.PrecioUnitario,
			Descuento:      item.Descuento,
			Subtotal:       subtotal,
			Impuestos:      taxes,
			Total:          subtotal + sumDianTaxValues(taxes),
		})
	}

	now := nowColombia()
	invoiceTypeCode := "01"
	if isContingencyInvoice(req) {
		invoiceTypeCode = "05"
	}
	totals := resolveTotalsFromItems(sourceItems, req.Totals)
	totals.Propina = parseStoredTotals(req.Totals).Propina
	if totals.Propina > 0 && totals.Total <= totals.Subtotal+totals.TotalImpuestos {
		totals.Total = totals.Subtotal + totals.TotalImpuestos + totals.Propina
	}
	return dianInvoice{
		TipoDocumento:    "FV",
		InvoiceTypeCode:  invoiceTypeCode,
		NumeroDocumento:  fmt.Sprintf("%s%d", prefijo, numero),
		FechaEmision:     now,
		FechaVencimiento: now,
		Moneda:           "COP",
		Emisor:           buildDianEmisor(emissionCtx),
		Cliente:          buildDianCustomer(req.Cliente),
		Items:         items,
		Totales:       totals,
		Observaciones: "Factura generada desde Core Go y emitida por DIAN_NET",
		Notas:         []string{},
		ConfiguracionDian: dianConfigDTO{
			NumeroResolucion: emissionCtx.ResolucionDIAN,
			FechaResolucion:  emissionCtx.VigenciaDesde,
			FechaInicio:      emissionCtx.VigenciaDesde,
			FechaFin:         emissionCtx.VigenciaHasta,
			Prefijo:          prefijo,
			RangoInicio:      strconv.FormatInt(emissionCtx.RangoDesde, 10),
			RangoFin:         strconv.FormatInt(emissionCtx.RangoHasta, 10),
			TipoAmbiente:     dianEnvironmentCode(ambiente),
			SoftwareID:       defaultString(emissionCtx.DIANConfig.SoftwareID, "SOFTWARE-ID-LOCAL"),
			Pin:              defaultString(emissionCtx.DIANConfig.Pin, "PIN-LOCAL"),
			ClaveTecnica:     emissionCtx.ClaveTecnica,
		},
	}
}

func buildDianCreditNote(req createCreditNoteRequest, emissionCtx companyEmissionContext, prefijo string, numero int64, ambiente string) dianCreditNote {
	totals := resolveTotalsFromItems(req.Items, req.Totals)
	items := make([]dianInvoiceItem, 0, len(req.Items))
	for i, item := range req.Items {
		quantity := defaultFloat(item.Cantidad, 1)
		subtotal := quantity*item.PrecioUnitario - item.Descuento
		if subtotal < 0 {
			subtotal = 0
		}
		taxes := normalizeDianTaxes(item.Impuestos, subtotal, quantity, "94")
		items = append(items, dianInvoiceItem{
			NumeroLinea:    i + 1,
			Codigo:         defaultString(item.Codigo, fmt.Sprintf("NC-ITEM-%03d", i+1)),
			Descripcion:    defaultString(item.Descripcion, "Ajuste de nota credito"),
			Cantidad:       quantity,
			UnidadMedida:   "94",
			PrecioUnitario: item.PrecioUnitario,
			Descuento:      item.Descuento,
			Subtotal:       subtotal,
			Impuestos:      taxes,
			Total:          subtotal + sumDianTaxValues(taxes),
		})
	}

	now := nowColombia()
	reference := req.FacturaReferencia
	if reference.TipoDocumento == "" {
		reference.TipoDocumento = "FV"
	}
	if reference.SchemeName == "" {
		reference.SchemeName = "CUFE-SHA384"
	}

	concepts := req.ConceptosCorreccion
	for i := range concepts {
		if concepts[i].ReferenceID == "" {
			concepts[i].ReferenceID = reference.NumeroDocumento
		}
		if concepts[i].Codigo == "" {
			concepts[i].Codigo = "1"
		}
		concepts[i].Descripcion = describeConceptoNotaCredito(concepts[i].Codigo)
	}

	return dianCreditNote{
		TipoDocumento:      "NC",
		CustomizationID:    defaultString(req.CustomizationID, "20"),
		CreditNoteTypeCode: defaultString(req.CreditNoteTypeCode, "91"),
		NumeroDocumento:    fmt.Sprintf("%s%d", prefijo, numero),
		FechaEmision:       now,
		Moneda:             "COP",
		FacturaReferencia:  reference,
		Emisor:             buildDianEmisor(emissionCtx),
		Cliente:            buildDianCustomer(req.Cliente),
		ConceptosCorreccion: concepts,
		Items:               items,
		Totales:             totals,
		Observaciones:       "Nota credito generada desde Core Go y emitida por DIAN_NET",
		Notas:               []string{},
		ConfiguracionDian: dianConfigDTO{
			NumeroResolucion: emissionCtx.ResolucionDIAN,
			FechaResolucion:  emissionCtx.VigenciaDesde,
			FechaInicio:      emissionCtx.VigenciaDesde,
			FechaFin:         emissionCtx.VigenciaHasta,
			Prefijo:          prefijo,
			RangoInicio:      strconv.FormatInt(emissionCtx.RangoDesde, 10),
			RangoFin:         strconv.FormatInt(emissionCtx.RangoHasta, 10),
			TipoAmbiente:     dianEnvironmentCode(ambiente),
			SoftwareID:       defaultString(emissionCtx.DIANConfig.SoftwareID, "SOFTWARE-ID-LOCAL"),
			Pin:              defaultString(emissionCtx.DIANConfig.Pin, "PIN-LOCAL"),
			ClaveTecnica:     emissionCtx.ClaveTecnica,
		},
	}
}

func buildDianDebitNote(req createDebitNoteRequest, emissionCtx companyEmissionContext, prefijo string, numero int64, ambiente string) dianDebitNote {
	totals := resolveTotalsFromItems(req.Items, req.Totals)
	items := make([]dianInvoiceItem, 0, len(req.Items))
	for i, item := range req.Items {
		quantity := defaultFloat(item.Cantidad, 1)
		subtotal := quantity*item.PrecioUnitario - item.Descuento
		if subtotal < 0 {
			subtotal = 0
		}
		taxes := normalizeDianTaxes(item.Impuestos, subtotal, quantity, "94")
		items = append(items, dianInvoiceItem{
			NumeroLinea:    i + 1,
			Codigo:         defaultString(item.Codigo, fmt.Sprintf("ND-ITEM-%03d", i+1)),
			Descripcion:    defaultString(item.Descripcion, "Ajuste de nota debito"),
			Cantidad:       quantity,
			UnidadMedida:   "94",
			PrecioUnitario: item.PrecioUnitario,
			Descuento:      item.Descuento,
			Subtotal:       subtotal,
			Impuestos:      taxes,
			Total:          subtotal + sumDianTaxValues(taxes),
		})
	}

	now := nowColombia()
	reference := req.FacturaReferencia
	if reference.TipoDocumento == "" {
		reference.TipoDocumento = "FV"
	}
	if reference.SchemeName == "" {
		reference.SchemeName = "CUFE-SHA384"
	}

	concepts := req.ConceptosCorreccion
	for i := range concepts {
		if concepts[i].ReferenceID == "" {
			concepts[i].ReferenceID = reference.NumeroDocumento
		}
		if concepts[i].Codigo == "" {
			concepts[i].Codigo = "1"
		}
		if concepts[i].Descripcion == "" {
			concepts[i].Descripcion = "Intereses"
		}
	}

	return dianDebitNote{
		TipoDocumento:     "ND",
		CustomizationID:   defaultString(req.CustomizationID, "30"),
		DebitNoteTypeCode: defaultString(req.DebitNoteTypeCode, "92"),
		NumeroDocumento:   fmt.Sprintf("%s%d", prefijo, numero),
		FechaEmision:      now,
		Moneda:            "COP",
		FacturaReferencia: reference,
		Emisor:            buildDianEmisor(emissionCtx),
		Cliente:           buildDianCustomer(req.Cliente),
		ConceptosCorreccion: concepts,
		Items:               items,
		Totales:             totals,
		Observaciones:       "Nota debito generada desde Core Go y emitida por DIAN_NET",
		Notas:               []string{},
		ConfiguracionDian: dianConfigDTO{
			NumeroResolucion: emissionCtx.ResolucionDIAN,
			FechaResolucion:  emissionCtx.VigenciaDesde,
			FechaInicio:      emissionCtx.VigenciaDesde,
			FechaFin:         emissionCtx.VigenciaHasta,
			Prefijo:          prefijo,
			RangoInicio:      strconv.FormatInt(emissionCtx.RangoDesde, 10),
			RangoFin:         strconv.FormatInt(emissionCtx.RangoHasta, 10),
			TipoAmbiente:     dianEnvironmentCode(ambiente),
			SoftwareID:       defaultString(emissionCtx.DIANConfig.SoftwareID, "SOFTWARE-ID-LOCAL"),
			Pin:              defaultString(emissionCtx.DIANConfig.Pin, "PIN-LOCAL"),
			ClaveTecnica:     emissionCtx.ClaveTecnica,
		},
	}
}

func isContingencyInvoice(req createInvoiceRequest) bool {
	if strings.Contains(strings.ToLower(req.XMLBase), "tipooperacion=05") {
		return true
	}
	if len(req.Totals) == 0 {
		return false
	}
	var raw map[string]any
	if json.Unmarshal(req.Totals, &raw) != nil {
		return false
	}
	if v, ok := raw["contingency"].(bool); ok && v {
		return true
	}
	if v, ok := raw["tipoOperacion"].(string); ok && strings.TrimSpace(v) == "05" {
		return true
	}
	return false
}

func resolveTotals(req createInvoiceRequest) dianTotals {
	return resolveTotalsFromItems(req.Items, req.Totals)
}

func resolveTotalsFromItems(items []invoiceItem, totalsJSON json.RawMessage) dianTotals {
	discounts := 0.0
	lineNet := 0.0
	for _, item := range items {
		qty := defaultFloat(item.Cantidad, 1)
		base := qty*item.PrecioUnitario - item.Descuento
		if base < 0 {
			base = 0
		}
		lineNet += base
		discounts += item.Descuento
	}

	computedTaxes := sumTaxesFromItems(items)
	totals := dianTotals{
		Subtotal:        lineNet,
		TotalDescuentos: discounts,
		TotalImpuestos:  computedTaxes,
		Total:           lineNet + computedTaxes,
	}

	if computedTaxes > 0 {
		return totals
	}

	stored := parseStoredTotals(totalsJSON)
	if stored.Subtotal > 0 {
		totals.Subtotal = stored.Subtotal
	}
	if stored.TotalImpuestos > 0 {
		totals.TotalImpuestos = stored.TotalImpuestos
	}
	if stored.Total > 0 {
		totals.Total = stored.Total
	} else if stored.Subtotal > 0 || stored.TotalImpuestos > 0 {
		totals.Total = totals.Subtotal + totals.TotalImpuestos + stored.Propina
	}

	return totals
}

type storedInvoiceTotals struct {
	Subtotal       float64 `json:"subtotal"`
	Iva            float64 `json:"iva"`
	Propina        float64 `json:"propina"`
	Total          float64 `json:"total"`
	TotalImpuestos float64 `json:"impuestos"`
}

func parseStoredTotals(raw json.RawMessage) storedInvoiceTotals {
	if len(raw) == 0 || !json.Valid(raw) {
		return storedInvoiceTotals{}
	}
	var totals storedInvoiceTotals
	_ = json.Unmarshal(raw, &totals)
	if totals.TotalImpuestos == 0 && totals.Iva > 0 {
		totals.TotalImpuestos = totals.Iva
	}
	return totals
}

func ensureItemTaxes(items []invoiceItem, totalsJSON json.RawMessage) []invoiceItem {
	hasTaxes := false
	for _, item := range items {
		if len(item.Impuestos) > 0 {
			hasTaxes = true
			break
		}
	}
	if hasTaxes {
		return items
	}

	stored := parseStoredTotals(totalsJSON)
	if stored.TotalImpuestos <= 0 && stored.Iva <= 0 {
		return items
	}

	enriched := make([]invoiceItem, len(items))
	copy(enriched, items)
	lineSubtotal := 0.0
	for i, item := range enriched {
		qty := defaultFloat(item.Cantidad, 1)
		base := qty*item.PrecioUnitario - item.Descuento
		if base < 0 {
			base = 0
		}
		lineSubtotal += base
		enriched[i] = item
	}

	taxRate := 19.0
	if stored.Iva > 0 && lineSubtotal > 0 {
		taxRate = stored.Iva / lineSubtotal * 100
	} else if stored.TotalImpuestos > 0 && lineSubtotal > 0 {
		taxRate = stored.TotalImpuestos / lineSubtotal * 100
	}

	for i, item := range enriched {
		if strings.EqualFold(strings.TrimSpace(item.Codigo), "PROPINA") {
			continue
		}
		qty := defaultFloat(item.Cantidad, 1)
		base := qty*item.PrecioUnitario - item.Descuento
		if base < 0 {
			base = 0
		}
		if base <= 0 {
			continue
		}
		taxVal := math.Round(base*taxRate/100*100) / 100
		item.Impuestos = []dianTax{{
			Codigo:        "01",
			Nombre:        "IVA",
			Porcentaje:    taxRate,
			BaseImponible: base,
			Valor:         taxVal,
		}}
		enriched[i] = item
	}
	return enriched
}

func filterOutPropinaLines(items []invoiceItem) []invoiceItem {
	filtered := make([]invoiceItem, 0, len(items))
	for _, item := range items {
		if strings.EqualFold(strings.TrimSpace(item.Codigo), "PROPINA") {
			continue
		}
		filtered = append(filtered, item)
	}
	return filtered
}

func appendPropinaItem(items []invoiceItem, totalsJSON json.RawMessage) []invoiceItem {
	if hasPropinaItem(items) {
		return items
	}
	propina := parseStoredTotals(totalsJSON).Propina
	if propina <= 0 {
		return items
	}
	return append(items, invoiceItem{
		Codigo:         "PROPINA",
		Descripcion:    "Propina voluntaria",
		Cantidad:       1,
		PrecioUnitario: propina,
		Descuento:      0,
		Impuestos:      []dianTax{},
	})
}

func hasPropinaItem(items []invoiceItem) bool {
	for _, item := range items {
		if strings.EqualFold(strings.TrimSpace(item.Codigo), "PROPINA") {
			return true
		}
	}
	return false
}

func normalizeDianIdentificationType(raw string) string {
	value := strings.ToUpper(strings.TrimSpace(defaultString(raw, "31")))
	switch value {
	case "CC", "13":
		return "13"
	case "CE", "22":
		return "22"
	case "PA", "42":
		return "42"
	case "NIT", "31":
		return "31"
	default:
		return value
	}
}

var colombiaLocation = time.FixedZone("America/Bogota", -5*60*60)

func nowColombia() time.Time {
	return time.Now().In(colombiaLocation)
}

func normalizeDianTaxes(taxes []dianTax, base, quantity float64, unitCode string) []dianTax {
	if len(taxes) == 0 {
		return []dianTax{}
	}
	normalized := make([]dianTax, 0, len(taxes))
	for _, tax := range taxes {
		tax.Codigo = defaultString(strings.ToUpper(strings.TrimSpace(tax.Codigo)), "01")
		if tax.BaseImponible == 0 {
			tax.BaseImponible = base
		}
		if tax.UnitCode == "" {
			tax.UnitCode = unitCode
		}
		if tax.BaseUnitMeasure == 0 {
			tax.BaseUnitMeasure = quantity
		}
		if tax.Valor == 0 && tax.Porcentaje > 0 {
			tax.Valor = tax.BaseImponible * tax.Porcentaje / 100
		}
		if tax.Valor == 0 && tax.PerUnitAmount > 0 {
			tax.Valor = tax.BaseUnitMeasure * tax.PerUnitAmount
		}
		normalized = append(normalized, tax)
	}
	return normalized
}

func sumDianTaxValues(taxes []dianTax) float64 {
	total := 0.0
	for _, tax := range taxes {
		if !tax.EsRetencion {
			total += tax.Valor
		}
	}
	return total
}

func sumTaxesFromItems(items []invoiceItem) float64 {
	total := 0.0
	for _, item := range items {
		quantity := defaultFloat(item.Cantidad, 1)
		subtotal := quantity*item.PrecioUnitario - item.Descuento
		if subtotal < 0 {
			subtotal = 0
		}
		for _, tax := range normalizeDianTaxes(item.Impuestos, subtotal, quantity, "94") {
			if !tax.EsRetencion {
				total += tax.Valor
			}
		}
	}
	return total
}

func calcularSoloDigitosNIT(value string) string {
	var digits strings.Builder
	for _, r := range value {
		if r >= '0' && r <= '9' {
			digits.WriteRune(r)
		}
	}
	return digits.String()
}

func calcularDVNIT(nit string) string {
	digits := calcularSoloDigitosNIT(nit)
	if digits == "" {
		return "0"
	}
	weights := []int{3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71}
	sum := 0
	for i := len(digits) - 1; i >= 0; i-- {
		weightIndex := len(digits) - 1 - i
		if weightIndex >= len(weights) {
			break
		}
		sum += int(digits[i]-'0') * weights[weightIndex]
	}
	remainder := sum % 11
	if remainder < 2 {
		return strconv.Itoa(remainder)
	}
	return strconv.Itoa(11 - remainder)
}

func resolveDianStatus(resp dianNetResponse, httpStatus int) string {
	canonical := strings.ToUpper(strings.TrimSpace(resp.EstadoDian))
	switch canonical {
	case "ENVIADO", "RECHAZADO_DIAN", "ERROR_DIAN_NET", "PENDIENTE", "EN_REINTENTO":
		return canonical
	}

	exitoso := resp.Exitoso ||
		strings.EqualFold(strings.TrimSpace(resp.StatusCode), "00") ||
		strings.EqualFold(strings.TrimSpace(resp.Status), "Exitoso")
	if exitoso {
		return "ENVIADO"
	}

	desc := strings.ToLower(resp.StatusDescription + " " + resp.StatusMessage + " " + resp.Status)
	if strings.Contains(desc, "validado") && strings.Contains(desc, "exitos") && !strings.Contains(desc, "rechaz") {
		return "ENVIADO"
	}

	if httpStatus < 200 || httpStatus > 299 {
		// Si hay payload DIAN con código/descripcion, es rechazo; si no, fallo de red/servicio.
		if resp.StatusCode != "" || resp.StatusDescription != "" || len(resp.Errores) > 0 {
			return "RECHAZADO_DIAN"
		}
		return "ERROR_DIAN_NET"
	}
	return "RECHAZADO_DIAN"
}

func resolveDianIdentifier(resp dianNetResponse) string {
	for _, value := range []string{resp.CudeCune, resp.CUFE, resp.CUNE, resp.UUID} {
		if value != "" {
			return value
		}
	}
	return ""
}

func defaultAddress(address addressDTO) addressDTO {
	if address.Departamento == "" {
		address.Departamento = "Bogota D.C."
	}
	if address.CodigoDepartamento == "" {
		address.CodigoDepartamento = "11"
	}
	if address.Municipio == "" {
		address.Municipio = "Bogota"
	}
	if address.CodigoMunicipio == "" {
		address.CodigoMunicipio = "11001"
	}
	if address.CodigoPostal == "" {
		address.CodigoPostal = "110111"
	}
	if address.DireccionCompleta == "" {
		address.DireccionCompleta = "Direccion local"
	}
	if address.Pais == "" {
		address.Pais = "CO"
	}
	return address
}

func dianEnvironmentCode(ambiente string) string {
	switch strings.ToLower(strings.TrimSpace(ambiente)) {
	case "produccion", "producción", "prod":
		return "1"
	default:
		return "2"
	}
}

func resolveDianAmbiente(requestAmbiente, configAmbiente string) string {
	// LOCAL/dev en payload histórico no debe pisar el ambiente DIAN de la sociedad (Produccion/Habilitacion).
	if explicit := explicitDianAmbiente(requestAmbiente); explicit != "" {
		return explicit
	}
	if normalized := normalizeDianAmbiente(configAmbiente); normalized != "" {
		return normalized
	}
	return "Habilitacion"
}

func explicitDianAmbiente(ambiente string) string {
	switch strings.ToLower(strings.TrimSpace(ambiente)) {
	case "", "local", "dev", "development":
		return ""
	}
	return normalizeDianAmbiente(ambiente)
}

func normalizeDianAmbiente(ambiente string) string {
	switch strings.ToLower(strings.TrimSpace(ambiente)) {
	case "", "local", "dev", "development":
		return "Habilitacion"
	case "mock":
		return "Mock"
	case "habilitacion", "habilitación", "hab":
		return "Habilitacion"
	case "produccion", "producción", "prod":
		return "Produccion"
	default:
		return strings.TrimSpace(ambiente)
	}
}

func numberFromMap(values map[string]any, key string, fallback float64) float64 {
	value, ok := values[key]
	if !ok {
		return fallback
	}
	switch typed := value.(type) {
	case float64:
		return typed
	case int:
		return float64(typed)
	case string:
		parsed, err := strconv.ParseFloat(typed, 64)
		if err == nil {
			return parsed
		}
	}
	return fallback
}

func defaultString(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func defaultFloat(value, fallback float64) float64 {
	if value == 0 {
		return fallback
	}
	return value
}

func (a *app) handleDownloadInvoiceDocument(w http.ResponseWriter, r *http.Request) {
	tenantID := mustTenantID(r.Context())
	invoiceID, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		http.Error(w, "invoice_id invalido", http.StatusBadRequest)
		return
	}
	kind := strings.ToLower(chi.URLParam(r, "kind"))

	var prefijo, estadoDian string
	var numero int64
	var rawPayload, dianResponse json.RawMessage
	err = a.db.QueryRow(r.Context(), `
		SELECT prefijo, numero, estado_dian, raw_dian_payload_jsonb, dian_response_jsonb
		FROM invoices
		WHERE company_id = $1
		  AND id = $2
	`, tenantID, invoiceID).Scan(&prefijo, &numero, &estadoDian, &rawPayload, &dianResponse)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			http.Error(w, "factura no encontrada", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	var dianResp dianNetResponse
	_ = json.Unmarshal(dianResponse, &dianResp)

	var content []byte
	var contentType, fileName string
	switch kind {
	case "signed-xml", "xml":
		content, err = decodeRequiredBase64(dianResp.SignedXMLBase64)
		contentType = "application/xml; charset=utf-8"
		fileName = fmt.Sprintf("%s-%d-firmado.xml", prefijo, numero)
	case "app-response", "application-response":
		content = []byte(dianResp.ApplicationResponseXML)
		if len(content) == 0 {
			content, err = decodeRequiredBase64(dianResp.ApplicationResponseXMLBase64)
		}
		contentType = "application/xml; charset=utf-8"
		fileName = fmt.Sprintf("%s-%d-application-response.xml", prefijo, numero)
	case "pdf", "representacion-grafica":
		content = buildGraphicRepresentationPDF(prefijo, numero, estadoDian, invoiceID)
		contentType = "application/pdf"
		fileName = fmt.Sprintf("%s-%d.pdf", prefijo, numero)
	case "attachment", "contenedor", "attached-document":
		signedXML, signedErr := decodeRequiredBase64(dianResp.SignedXMLBase64)
		appResponse := []byte(dianResp.ApplicationResponseXML)
		if len(appResponse) == 0 {
			appResponse, _ = decodeRequiredBase64(dianResp.ApplicationResponseXMLBase64)
		}
		if signedErr != nil {
			err = signedErr
		} else {
			content, fileName, err = delivery.BuildAttachedDocumentZip(signedXML, appResponse)
		}
		contentType = "application/zip"
	default:
		http.Error(w, "tipo de documento invalido", http.StatusBadRequest)
		return
	}
	if err != nil || len(content) == 0 {
		http.Error(w, "documento no disponible", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, fileName))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(content)
}

func decodeRequiredBase64(value string) ([]byte, error) {
	if strings.TrimSpace(value) == "" {
		return nil, errors.New("base64 vacio")
	}
	return base64.StdEncoding.DecodeString(value)
}

func buildGraphicRepresentationPDF(prefijo string, numero int64, estadoDian string, invoiceID uuid.UUID) []byte {
	text := fmt.Sprintf("Factura %s-%d | Estado DIAN: %s | ID: %s", prefijo, numero, estadoDian, invoiceID)
	stream := fmt.Sprintf("BT /F1 12 Tf 72 720 Td (%s) Tj ET", sanitizePDFText(text))
	objects := []string{
		"<< /Type /Catalog /Pages 2 0 R >>",
		"<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
		"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
		fmt.Sprintf("<< /Length %d >>\nstream\n%s\nendstream", len(stream), stream),
		"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
	}

	var builder bytes.Buffer
	builder.WriteString("%PDF-1.4\n")
	offsets := make([]int, 0, len(objects))
	for i, obj := range objects {
		offsets = append(offsets, builder.Len())
		builder.WriteString(fmt.Sprintf("%d 0 obj\n%s\nendobj\n", i+1, obj))
	}

	xrefOffset := builder.Len()
	builder.WriteString(fmt.Sprintf("xref\n0 %d\n", len(objects)+1))
	builder.WriteString("0000000000 65535 f \n")
	for _, offset := range offsets {
		builder.WriteString(fmt.Sprintf("%010d 00000 n \n", offset))
	}
	builder.WriteString(fmt.Sprintf("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(objects)+1, xrefOffset))
	return builder.Bytes()
}

func sanitizePDFText(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, "(", `\(`)
	value = strings.ReplaceAll(value, ")", `\)`)
	return value
}

func mustTenantID(ctx context.Context) uuid.UUID {
	return ctx.Value(tenantIDKey).(uuid.UUID)
}

func mustEmissionPointID(ctx context.Context) uuid.UUID {
	return ctx.Value(emissionPointIDKey).(uuid.UUID)
}

func nullJSON(raw json.RawMessage) any {
	if len(raw) == 0 {
		return nil
	}
	return raw
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func mustEnv(key string) string {
	value := os.Getenv(key)
	if value == "" {
		log.Fatalf("%s requerido", key)
	}
	return value
}

func getenv(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}
