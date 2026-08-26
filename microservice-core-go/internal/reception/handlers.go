package reception

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Handlers struct {
	Service *Service
}

func NewHandlers(db *pgxpool.Pool, jwtSecret, s3Endpoint string) *Handlers {
	return &Handlers{Service: NewService(db, jwtSecret, s3Endpoint)}
}

func TenantIDFromRequest(r *http.Request) (uuid.UUID, error) {
	return uuid.Parse(r.Header.Get("X-Tenant-ID"))
}

func (h *Handlers) SyncIMAP(w http.ResponseWriter, r *http.Request) {
	tenantID, err := TenantIDFromRequest(r)
	if err != nil {
		http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
		return
	}
	result, err := h.Service.SyncIMAP(r.Context(), tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Handlers) TestIMAP(w http.ResponseWriter, r *http.Request) {
	tenantID, err := TenantIDFromRequest(r)
	if err != nil {
		http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
		return
	}
	msg, err := h.Service.TestIMAP(r.Context(), tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "OK", "message": msg})
}

type importXMLRequest struct {
	FileName      string `json:"file_name"`
	ContentBase64 string `json:"content_base64"`
	Source        string `json:"source"`
}

func (h *Handlers) ImportXML(w http.ResponseWriter, r *http.Request) {
	tenantID, err := TenantIDFromRequest(r)
	if err != nil {
		http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
		return
	}

	ct := r.Header.Get("Content-Type")
	var content []byte
	fileName := "upload.xml"
	source := "XML_UPLOAD"

	if strings.HasPrefix(ct, "multipart/form-data") {
		if err := r.ParseMultipartForm(12 << 20); err != nil {
			http.Error(w, "multipart inválido: "+err.Error(), http.StatusBadRequest)
			return
		}
		file, header, err := r.FormFile("archivo")
		if err != nil {
			file, header, err = r.FormFile("file")
		}
		if err != nil {
			http.Error(w, "archivo requerido", http.StatusBadRequest)
			return
		}
		defer file.Close()
		content, err = io.ReadAll(io.LimitReader(file, 12<<20))
		if err != nil {
			http.Error(w, "no se pudo leer archivo", http.StatusBadRequest)
			return
		}
		if header != nil && header.Filename != "" {
			fileName = header.Filename
		}
	} else {
		var req importXMLRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "payload inválido", http.StatusBadRequest)
			return
		}
		if req.ContentBase64 == "" {
			http.Error(w, "content_base64 requerido", http.StatusBadRequest)
			return
		}
		content, err = base64.StdEncoding.DecodeString(req.ContentBase64)
		if err != nil {
			http.Error(w, "content_base64 inválido", http.StatusBadRequest)
			return
		}
		if req.FileName != "" {
			fileName = req.FileName
		}
		if req.Source != "" {
			source = req.Source
		}
	}

	result, err := h.Service.Store.IngestPackage(r.Context(), tenantID, content, fileName, source)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Handlers) IncomingEmailWebhook(w http.ResponseWriter, r *http.Request) {
	tenantID, err := TenantIDFromRequest(r)
	if err != nil {
		http.Error(w, "X-Tenant-ID inválido", http.StatusUnauthorized)
		return
	}
	var webhook IncomingEmailWebhook
	if err := json.NewDecoder(r.Body).Decode(&webhook); err != nil {
		http.Error(w, "payload inválido", http.StatusBadRequest)
		return
	}
	if webhook.TenantID != uuid.Nil && webhook.TenantID != tenantID {
		http.Error(w, "tenant_id no coincide", http.StatusForbidden)
		return
	}
	if webhook.S3ObjectKey == "" && webhook.ContentBase64 == "" {
		http.Error(w, "s3_object_key o content_base64 requerido", http.StatusBadRequest)
		return
	}

	// Procesamiento async; 202 inmediato
	h.Service.ProcessWebhookAsync(tenantID, webhook)
	writeJSON(w, http.StatusAccepted, IncomingEmailAcceptedResponse{
		Status:      "WEBHOOK_RECIBIDO_EN_PROCESAMIENTO",
		S3ObjectKey: webhook.S3ObjectKey,
		Async:       true,
	})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
