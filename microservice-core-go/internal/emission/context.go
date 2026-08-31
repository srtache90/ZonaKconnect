package emission

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// LoadCompanyContext carga certificado DIAN del tenant + punto de emisión.
func LoadCompanyContext(ctx context.Context, db *pgxpool.Pool, tenantID, emissionPointID uuid.UUID) (CompanyContext, error) {
	var result CompanyContext
	result.TenantID = tenantID

	var dianConfigRaw json.RawMessage
	err := db.QueryRow(ctx, `
		SELECT c.dian_config
		FROM companies c
		JOIN emission_points ep ON ep.company_id = c.id
		WHERE c.id = $1
		  AND ep.id = $2
		  AND c.is_active = TRUE
		  AND ep.is_active = TRUE
	`, tenantID, emissionPointID).Scan(&dianConfigRaw)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return result, fmt.Errorf("tenant_id=%s o emission_point_id=%s no existe o esta inactivo", tenantID, emissionPointID)
		}
		return result, err
	}

	if err := json.Unmarshal(dianConfigRaw, &result.DIANConfig); err != nil {
		return result, err
	}
	return result, nil
}
