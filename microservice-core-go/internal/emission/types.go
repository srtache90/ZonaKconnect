package emission

import (
	"time"

	"github.com/google/uuid"
)

const JobNameRadianSync = "emission-radian-sync"

// RadianSyncJob encola la consulta GetDocumentInfo para un documento emitido.
type RadianSyncJob struct {
	Job             string    `json:"job"`
	TenantID        uuid.UUID `json:"tenant_id"`
	EmissionPointID uuid.UUID `json:"emission_point_id"`
	InvoiceID       uuid.UUID `json:"invoice_id"`
	Cufe            string    `json:"cufe"`
	Ambiente        string    `json:"ambiente"`
	Attempt         int       `json:"attempt"`
	EnqueuedAt      time.Time `json:"enqueued_at"`
	Source          string    `json:"source"`
}

// DIANConfig certificado y ambiente por tenant.
type DIANConfig struct {
	S3CertificateKey          string `json:"s3_certificate_key"`
	SecretsManagerPasswordKey string `json:"secrets_manager_password_key"`
	Ambiente                  string `json:"ambiente"`
}

// CompanyContext datos mínimos para llamar DIAN_NET.
type CompanyContext struct {
	TenantID   uuid.UUID
	DIANConfig DIANConfig
}

// OpenDocumentCandidate documento emitido que aún puede recibir eventos del adquirente.
type OpenDocumentCandidate struct {
	InvoiceID       uuid.UUID
	TenantID        uuid.UUID
	EmissionPointID uuid.UUID
	Cufe            string
	Ambiente        string
}
