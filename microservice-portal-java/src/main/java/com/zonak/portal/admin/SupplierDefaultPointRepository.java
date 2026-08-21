package com.zonak.portal.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class SupplierDefaultPointRepository {
    private final JdbcTemplate jdbcTemplate;

    public SupplierDefaultPointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SupplierDefaultPoint> findByCompany(UUID companyId) {
        return jdbcTemplate.query(
                """
                        SELECT s.id,
                               s.company_id,
                               s.supplier_nit,
                               s.emission_point_id,
                               COALESCE(ep.codigo || ' - ' || ep.nombre, s.emission_point_id::text) AS point_label,
                               COALESCE(s.notes, '') AS notes,
                               s.is_active
                        FROM supplier_default_points s
                        LEFT JOIN emission_points ep ON ep.id = s.emission_point_id
                        WHERE s.company_id = ?
                        ORDER BY s.supplier_nit
                        """,
                (rs, rowNum) -> new SupplierDefaultPoint(
                        rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class),
                        rs.getString("supplier_nit"),
                        rs.getObject("emission_point_id", UUID.class),
                        rs.getString("point_label"),
                        rs.getString("notes"),
                        rs.getBoolean("is_active")
                ),
                companyId
        );
    }

    public Optional<UUID> findActivePointForSupplier(UUID companyId, String supplierNit) {
        String nit = normalizeNit(supplierNit);
        if (!StringUtils.hasText(nit)) {
            return Optional.empty();
        }
        List<UUID> ids = jdbcTemplate.query(
                """
                        SELECT emission_point_id
                        FROM supplier_default_points
                        WHERE company_id = ?
                          AND is_active = TRUE
                          AND regexp_replace(supplier_nit, '[^0-9]', '', 'g') = ?
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("emission_point_id", UUID.class),
                companyId,
                nit
        );
        return ids.stream().findFirst();
    }

    @Transactional
    public void upsert(UUID companyId, String supplierNit, UUID emissionPointId, String notes) {
        String nit = normalizeNit(supplierNit);
        if (!StringUtils.hasText(nit) || emissionPointId == null) {
            throw new IllegalArgumentException("NIT proveedor y punto de venta son obligatorios");
        }
        jdbcTemplate.update(
                """
                        INSERT INTO supplier_default_points (company_id, supplier_nit, emission_point_id, notes, is_active)
                        VALUES (?, ?, ?, ?, TRUE)
                        ON CONFLICT (company_id, supplier_nit) DO UPDATE
                        SET emission_point_id = EXCLUDED.emission_point_id,
                            notes = EXCLUDED.notes,
                            is_active = TRUE,
                            updated_at = now()
                        """,
                companyId,
                nit,
                emissionPointId,
                notes == null ? "" : notes.trim()
        );
    }

    public void deactivate(UUID id, UUID companyId) {
        jdbcTemplate.update(
                """
                        UPDATE supplier_default_points
                        SET is_active = FALSE, updated_at = now()
                        WHERE id = ? AND company_id = ?
                        """,
                id,
                companyId
        );
    }

    private static String normalizeNit(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }
}
