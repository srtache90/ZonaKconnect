package com.zonak.portal.recepcion;

import com.zonak.portal.admin.SupplierDefaultPointRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReceivedInvoiceAssignmentService {
    private final JdbcTemplate jdbcTemplate;
    private final SupplierDefaultPointRepository supplierDefaultPointRepository;

    public ReceivedInvoiceAssignmentService(
            JdbcTemplate jdbcTemplate,
            SupplierDefaultPointRepository supplierDefaultPointRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.supplierDefaultPointRepository = supplierDefaultPointRepository;
    }

    public Optional<UUID> resolveAutomaticPoint(UUID companyId, String supplierNit) {
        return supplierDefaultPointRepository.findActivePointForSupplier(companyId, supplierNit);
    }

    @Transactional
    public void applyAutomaticAssignment(UUID companyId, UUID invoiceId, String supplierNit) {
        Optional<UUID> point = resolveAutomaticPoint(companyId, supplierNit);
        if (point.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
                """
                        UPDATE received_invoices
                        SET assigned_emission_point_id = ?,
                            assignment_source = 'SUPPLIER_DEFAULT',
                            assigned_at = now(),
                            assigned_by_user_id = NULL,
                            updated_at = now()
                        WHERE id = ?
                          AND company_id = ?
                          AND assigned_emission_point_id IS NULL
                          AND assignment_source = 'UNASSIGNED'
                        """,
                point.get(),
                invoiceId,
                companyId
        );
    }

    public int assignPendingForCompany(UUID companyId) {
        List<Map.Entry<UUID, String>> pending = jdbcTemplate.query(
                """
                        SELECT id, supplier_nit
                        FROM received_invoices
                        WHERE company_id = ?
                          AND assigned_emission_point_id IS NULL
                          AND COALESCE(assignment_source, 'UNASSIGNED') = 'UNASSIGNED'
                        LIMIT 500
                        """,
                (rs, rowNum) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        rs.getString("supplier_nit")
                ),
                companyId
        );
        int count = 0;
        for (var row : pending) {
            applyAutomaticAssignment(companyId, row.getKey(), row.getValue());
            Integer assigned = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*) FROM received_invoices
                            WHERE id = ? AND assigned_emission_point_id IS NOT NULL
                            """,
                    Integer.class,
                    row.getKey()
            );
            if (assigned != null && assigned > 0) {
                count++;
            }
        }
        return count;
    }

    @Transactional
    public void assignManually(
            UUID companyId,
            UUID invoiceId,
            UUID toEmissionPointId,
            UUID actorUserId,
            String reason
    ) {
        UUID fromPoint = jdbcTemplate.query(
                """
                        SELECT assigned_emission_point_id
                        FROM received_invoices
                        WHERE id = ? AND company_id = ?
                        """,
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                invoiceId,
                companyId
        );

        int updated = jdbcTemplate.update(
                """
                        UPDATE received_invoices
                        SET assigned_emission_point_id = ?,
                            assignment_source = ?,
                            assigned_at = now(),
                            assigned_by_user_id = ?,
                            updated_at = now()
                        WHERE id = ? AND company_id = ?
                        """,
                toEmissionPointId,
                toEmissionPointId == null ? "UNASSIGNED" : "MANUAL",
                actorUserId,
                invoiceId,
                companyId
        );
        if (updated == 0) {
            throw new IllegalArgumentException("Factura recibida no encontrada");
        }

        jdbcTemplate.update(
                """
                        INSERT INTO received_invoice_assignment_audit (
                            received_invoice_id, company_id, from_emission_point_id, to_emission_point_id,
                            actor_user_id, reason
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                invoiceId,
                companyId,
                fromPoint,
                toEmissionPointId,
                actorUserId,
                StringUtils.hasText(reason) ? reason.trim() : "Reasignación manual"
        );
    }
}
