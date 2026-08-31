package com.zonak.portal.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminPortalRepository {
    private static final UUID PROTECTED_SOCIEDAD_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID LOCAL_DIAN_MOCK_PUNTO_VENTA_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final JdbcTemplate jdbcTemplate;

    public AdminPortalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Sociedad> findSociedades() {
        return jdbcTemplate.query(
                """
                        SELECT s.id, s.razon_social, s.nit, s.api_key, s.correo_emision, s.correo_recepcion,
                               s.host_smtp, s.puerto_smtp, s.usuario_smtp,
                               s.host_imap, s.puerto_imap, s.usuario_imap, s.dian_ambiente,
                               COALESCE(
                                   NULLIF(TRIM(s.dian_regimen_fiscal), ''),
                                   NULLIF(TRIM(c.dian_config->>'regimen_fiscal'), ''),
                                   'O-99'
                               ) AS dian_regimen_fiscal,
                               COALESCE(
                                   NULLIF(TRIM(s.dian_software_id), ''),
                                   NULLIF(TRIM(c.dian_config->>'software_id'), '')
                               ) AS dian_software_id,
                               (
                                   s.dian_software_pin_enc IS NOT NULL
                                   OR NULLIF(TRIM(c.dian_config->>'pin'), '') IS NOT NULL
                               ) AS dian_software_pin_configured
                        FROM sociedades s
                        LEFT JOIN companies c ON c.id = s.id
                        ORDER BY CASE WHEN s.id = ? THEN 0 ELSE 1 END, s.razon_social ASC
                        """,
                (rs, rowNum) -> new Sociedad(
                        rs.getObject("id", UUID.class),
                        rs.getString("razon_social"),
                        rs.getString("nit"),
                        rs.getString("api_key"),
                        rs.getString("correo_emision"),
                        rs.getString("correo_recepcion"),
                        rs.getString("host_smtp"),
                        rs.getObject("puerto_smtp", Integer.class),
                        rs.getString("usuario_smtp"),
                        rs.getString("host_imap"),
                        rs.getObject("puerto_imap", Integer.class),
                        rs.getString("usuario_imap"),
                        rs.getString("dian_ambiente"),
                        rs.getString("dian_regimen_fiscal"),
                        rs.getString("dian_software_id"),
                        rs.getBoolean("dian_software_pin_configured")
                ),
                PROTECTED_SOCIEDAD_ID
        );
    }

    public List<Sociedad> findSociedadesByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                """
                        SELECT s.id, s.razon_social, s.nit, s.api_key, s.correo_emision, s.correo_recepcion,
                               s.host_smtp, s.puerto_smtp, s.usuario_smtp,
                               s.host_imap, s.puerto_imap, s.usuario_imap, s.dian_ambiente,
                               COALESCE(
                                   NULLIF(TRIM(s.dian_regimen_fiscal), ''),
                                   NULLIF(TRIM(c.dian_config->>'regimen_fiscal'), ''),
                                   'O-99'
                               ) AS dian_regimen_fiscal,
                               COALESCE(
                                   NULLIF(TRIM(s.dian_software_id), ''),
                                   NULLIF(TRIM(c.dian_config->>'software_id'), '')
                               ) AS dian_software_id,
                               (
                                   s.dian_software_pin_enc IS NOT NULL
                                   OR NULLIF(TRIM(c.dian_config->>'pin'), '') IS NOT NULL
                               ) AS dian_software_pin_configured
                        FROM sociedades s
                        LEFT JOIN companies c ON c.id = s.id
                        WHERE s.id IN (%s)
                        ORDER BY CASE WHEN s.id = ? THEN 0 ELSE 1 END, s.razon_social ASC
                        """.formatted(placeholders),
                (rs, rowNum) -> new Sociedad(
                        rs.getObject("id", UUID.class),
                        rs.getString("razon_social"),
                        rs.getString("nit"),
                        rs.getString("api_key"),
                        rs.getString("correo_emision"),
                        rs.getString("correo_recepcion"),
                        rs.getString("host_smtp"),
                        rs.getObject("puerto_smtp", Integer.class),
                        rs.getString("usuario_smtp"),
                        rs.getString("host_imap"),
                        rs.getObject("puerto_imap", Integer.class),
                        rs.getString("usuario_imap"),
                        rs.getString("dian_ambiente"),
                        rs.getString("dian_regimen_fiscal"),
                        rs.getString("dian_software_id"),
                        rs.getBoolean("dian_software_pin_configured")
                ),
                append(ids, PROTECTED_SOCIEDAD_ID)
        );
    }

    private static Object[] append(List<UUID> values, UUID extraValue) {
        Object[] params = new Object[values.size() + 1];
        for (int i = 0; i < values.size(); i++) {
            params[i] = values.get(i);
        }
        params[values.size()] = extraValue;
        return params;
    }

    public void saveSociedad(
            UUID id,
            String razonSocial,
            String nit,
            String apiKey,
            String correoEmision,
            String correoRecepcion,
            String hostSmtp,
            Integer puertoSmtp,
            String usuarioSmtp,
            String passwordSmtpEnc,
            String hostImap,
            Integer puertoImap,
            String usuarioImap,
            String passwordImapEnc,
            String dianAmbiente,
            String dianRegimenFiscal,
            String dianSoftwareId,
            String dianSoftwarePinEnc,
            String dianSoftwarePinPlaintext
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO sociedades (
                            id, razon_social, nit, api_key, correo_emision, correo_recepcion,
                            host_smtp, puerto_smtp, usuario_smtp, password_smtp_enc,
                            host_imap, puerto_imap, usuario_imap, password_imap_enc,
                            dian_ambiente, dian_regimen_fiscal, dian_software_id, dian_software_pin_enc
                        )
                        VALUES (?, ?, ?, NULLIF(?, ''), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?)
                        ON CONFLICT (id) DO UPDATE SET
                            razon_social = EXCLUDED.razon_social,
                            nit = EXCLUDED.nit,
                            api_key = COALESCE(EXCLUDED.api_key, sociedades.api_key),
                            correo_emision = EXCLUDED.correo_emision,
                            correo_recepcion = EXCLUDED.correo_recepcion,
                            host_smtp = EXCLUDED.host_smtp,
                            puerto_smtp = EXCLUDED.puerto_smtp,
                            usuario_smtp = EXCLUDED.usuario_smtp,
                            password_smtp_enc = COALESCE(EXCLUDED.password_smtp_enc, sociedades.password_smtp_enc),
                            host_imap = EXCLUDED.host_imap,
                            puerto_imap = EXCLUDED.puerto_imap,
                            usuario_imap = EXCLUDED.usuario_imap,
                            password_imap_enc = COALESCE(EXCLUDED.password_imap_enc, sociedades.password_imap_enc),
                            dian_ambiente = EXCLUDED.dian_ambiente,
                            dian_regimen_fiscal = EXCLUDED.dian_regimen_fiscal,
                            dian_software_id = COALESCE(NULLIF(EXCLUDED.dian_software_id, ''), sociedades.dian_software_id),
                            dian_software_pin_enc = COALESCE(EXCLUDED.dian_software_pin_enc, sociedades.dian_software_pin_enc)
                        """,
                id,
                razonSocial,
                nit,
                apiKey,
                correoEmision,
                correoRecepcion,
                hostSmtp,
                puertoSmtp,
                usuarioSmtp,
                passwordSmtpEnc,
                hostImap,
                puertoImap,
                usuarioImap,
                passwordImapEnc,
                normalizeDianAmbiente(dianAmbiente),
                DianRegimenFiscal.normalize(dianRegimenFiscal),
                dianSoftwareId,
                dianSoftwarePinEnc
        );
        ensureCompanyForSociedad(id);
        syncCompanyDianConfig(id, dianSoftwarePinPlaintext);
    }

    public void updateImapPassword(UUID sociedadId, String passwordImapEnc) {
        jdbcTemplate.update(
                """
                        UPDATE sociedades
                        SET password_imap_enc = ?
                        WHERE id = ?
                        """,
                passwordImapEnc,
                sociedadId
        );
    }

    @Transactional
    public Optional<String> deleteSociedad(UUID id) {
        if (PROTECTED_SOCIEDAD_ID.equals(id)) {
            return Optional.of("No se puede eliminar la sociedad local del sistema.");
        }
        if (!sociedadExists(id)) {
            return Optional.of("La sociedad no existe o ya fue eliminada.");
        }

        Integer invoiceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM invoices WHERE company_id = ?",
                Integer.class,
                id
        );
        if (invoiceCount != null && invoiceCount > 0) {
            return Optional.of(
                    "No se puede eliminar: tiene " + invoiceCount + " factura(s) asociada(s)."
            );
        }

        jdbcTemplate.update("DELETE FROM audit_events WHERE company_id = ?", id);
        jdbcTemplate.update("DELETE FROM emission_points WHERE company_id = ?", id);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", id);
        jdbcTemplate.update("DELETE FROM sociedades WHERE id = ?", id);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
        return Optional.empty();
    }

    public void updateDianAmbiente(UUID sociedadId, String dianAmbiente) {
        String normalized = normalizeDianAmbiente(dianAmbiente);
        jdbcTemplate.update(
                """
                        UPDATE sociedades
                        SET dian_ambiente = ?
                        WHERE id = ?
                        """,
                normalized,
                sociedadId
        );
        syncCompanyDianConfig(sociedadId, null);
    }

    public List<CertificadoDigital> findCertificados() {
        return jdbcTemplate.query(
                """
                        SELECT c.id, c.sociedad_id, s.razon_social, c.alias, c.valido_hasta, c.activo
                        FROM certificados_digitales c
                        JOIN sociedades s ON s.id = c.sociedad_id
                        ORDER BY s.razon_social ASC, c.valido_hasta DESC
                        """,
                (rs, rowNum) -> new CertificadoDigital(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sociedad_id", UUID.class),
                        rs.getString("razon_social"),
                        rs.getString("alias"),
                        rs.getObject("valido_hasta", LocalDate.class),
                        rs.getBoolean("activo")
                )
        );
    }

    public void saveCertificado(
            UUID sociedadId,
            String alias,
            String contenidoBase64Enc,
            String passwordEnc,
            LocalDate validoHasta,
            boolean activo
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO certificados_digitales (
                            sociedad_id, alias, contenido_base64_enc, password_enc, valido_hasta, activo
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (sociedad_id, alias) DO UPDATE SET
                            contenido_base64_enc = EXCLUDED.contenido_base64_enc,
                            password_enc = EXCLUDED.password_enc,
                            valido_hasta = EXCLUDED.valido_hasta,
                            activo = EXCLUDED.activo
                        """,
                sociedadId,
                alias,
                contenidoBase64Enc,
                passwordEnc,
                validoHasta,
                activo
        );
    }

    public record CertificadoProvisionRow(UUID sociedadId, String contenidoBase64Enc, String passwordEnc) {
    }

    public List<CertificadoProvisionRow> findCertificadosPendingDianConfigSync() {
        return jdbcTemplate.query(
                """
                        SELECT DISTINCT ON (c.sociedad_id)
                               c.sociedad_id,
                               c.contenido_base64_enc,
                               c.password_enc
                        FROM certificados_digitales c
                        JOIN companies co ON co.id = c.sociedad_id
                        WHERE c.activo = TRUE
                          AND c.valido_hasta >= CURRENT_DATE
                          AND (
                              NULLIF(TRIM(co.dian_config->>'s3_certificate_key'), '') IS NULL
                              OR NULLIF(TRIM(co.dian_config->>'secrets_manager_password_key'), '') IS NULL
                          )
                        ORDER BY c.sociedad_id, c.valido_hasta DESC
                        """,
                (rs, rowNum) -> new CertificadoProvisionRow(
                        rs.getObject("sociedad_id", UUID.class),
                        rs.getString("contenido_base64_enc"),
                        rs.getString("password_enc")
                )
        );
    }

    public void syncDianCertificateKeys(UUID sociedadId, String s3CertificateKey, String secretsManagerPasswordKey) {
        jdbcTemplate.update(
                """
                        UPDATE companies
                        SET dian_config = COALESCE(dian_config, '{}'::jsonb)
                            || jsonb_build_object(
                                's3_certificate_key', ?::text,
                                'secrets_manager_password_key', ?::text
                            ),
                            updated_at = now()
                        WHERE id = ?
                        """,
                s3CertificateKey,
                secretsManagerPasswordKey,
                sociedadId
        );
    }

    public SociedadDianContext findSociedadDianContext(UUID sociedadId) {
        return jdbcTemplate.query(
                """
                        SELECT s.id,
                               s.nit,
                               COALESCE(
                                   NULLIF(TRIM(s.dian_software_id), ''),
                                   NULLIF(TRIM(c.dian_config->>'software_id'), '')
                               ) AS software_id,
                               COALESCE(
                                   NULLIF(TRIM(c.dian_config->>'ambiente'), ''),
                                   NULLIF(TRIM(s.dian_ambiente), ''),
                                   'Habilitacion'
                               ) AS ambiente,
                               NULLIF(TRIM(c.dian_config->>'s3_certificate_key'), '') AS s3_certificate_key,
                               NULLIF(TRIM(c.dian_config->>'secrets_manager_password_key'), '') AS secrets_manager_password_key
                        FROM sociedades s
                        JOIN companies c ON c.id = s.id
                        WHERE s.id = ?
                        """,
                rs -> rs.next()
                        ? new SociedadDianContext(
                                rs.getObject("id", UUID.class),
                                rs.getString("nit"),
                                rs.getString("software_id"),
                                rs.getString("ambiente"),
                                rs.getString("s3_certificate_key"),
                                rs.getString("secrets_manager_password_key")
                        )
                        : null,
                sociedadId
        );
    }

    public List<PuntoVenta> findPuntosVenta() {
        return jdbcTemplate.query(
                """
                        SELECT ep.id, ep.company_id AS sociedad_id, s.razon_social,
                               ep.codigo, ep.nombre, ep.direccion, ep.prefijo,
                               ep.resolucion_dian, ep.clave_tecnica,
                               ep.rango_desde, ep.rango_hasta, ep.numero_actual,
                               ep.prefijo_nc, ep.numero_actual_nc,
                               ep.prefijo_nd, ep.numero_actual_nd,
                               ep.vigencia_desde, ep.vigencia_hasta, ep.is_active
                        FROM emission_points ep
                        JOIN sociedades s ON s.id = ep.company_id
                        ORDER BY s.razon_social ASC, ep.codigo ASC
                        """,
                (rs, rowNum) -> mapPuntoVentaRow(rs)
        );
    }

    public List<PuntoVenta> findPuntosVentaActivos(UUID sociedadId) {
        return findPuntosVentaActivosBySociedades(List.of(sociedadId));
    }

    public List<PuntoVenta> findPuntosVentaActivosBySociedades(List<UUID> sociedadIds) {
        if (sociedadIds == null || sociedadIds.isEmpty()) {
            return List.of();
        }

        String placeholders = sociedadIds.stream()
                .map(ignored -> "?")
                .collect(Collectors.joining(","));
        return jdbcTemplate.query(
                """
                        SELECT ep.id, ep.company_id AS sociedad_id, s.razon_social,
                               ep.codigo, ep.nombre, ep.direccion, ep.prefijo,
                               ep.resolucion_dian, ep.clave_tecnica,
                               ep.rango_desde, ep.rango_hasta, ep.numero_actual,
                               ep.prefijo_nc, ep.numero_actual_nc,
                               ep.prefijo_nd, ep.numero_actual_nd,
                               ep.vigencia_desde, ep.vigencia_hasta, ep.is_active
                        FROM emission_points ep
                        JOIN sociedades s ON s.id = ep.company_id
                        WHERE ep.company_id IN (%s)
                          AND ep.is_active = true
                          AND CURRENT_DATE BETWEEN ep.vigencia_desde AND ep.vigencia_hasta
                        ORDER BY s.razon_social ASC, ep.codigo ASC
                        """.formatted(placeholders),
                (rs, rowNum) -> mapPuntoVentaRow(rs),
                sociedadIds.toArray()
        );
    }

    public boolean puntoVentaActivoPerteneceASociedad(UUID puntoVentaId, UUID sociedadId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM emission_points
                            WHERE id = ?
                              AND company_id = ?
                              AND is_active = true
                              AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                        )
                        """,
                Boolean.class,
                puntoVentaId,
                sociedadId
        );
        return Boolean.TRUE.equals(exists);
    }

    public void savePuntoVenta(
            UUID id,
            UUID sociedadId,
            String codigo,
            String nombre,
            String direccion,
            String prefijo,
            String resolucionDian,
            String claveTecnica,
            Long rangoDesde,
            Long rangoHasta,
            Long numeroActual,
            String prefijoNc,
            Long numeroActualNc,
            String prefijoNd,
            Long numeroActualNd,
            LocalDate vigenciaDesde,
            LocalDate vigenciaHasta,
            boolean activo
    ) {
        ensureCompanyForSociedad(sociedadId);
        String safePrefijoNc = normalizePrefijoDocumento(prefijoNc, "NC");
        String safePrefijoNd = normalizePrefijoDocumento(prefijoNd, "ND");
        long safeNumeroNc = numeroActualNc != null ? numeroActualNc : Math.max(rangoDesde - 1, 0);
        long safeNumeroNd = numeroActualNd != null ? numeroActualNd : Math.max(rangoDesde - 1, 0);
        jdbcTemplate.update(
                """
                        INSERT INTO emission_points (
                            id, company_id, codigo, nombre, direccion, prefijo,
                            resolucion_dian, clave_tecnica, rango_desde, rango_hasta,
                            numero_actual, prefijo_nc, numero_actual_nc, prefijo_nd, numero_actual_nd,
                            vigencia_desde, vigencia_hasta, is_active
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            company_id = EXCLUDED.company_id,
                            codigo = EXCLUDED.codigo,
                            nombre = EXCLUDED.nombre,
                            direccion = EXCLUDED.direccion,
                            prefijo = EXCLUDED.prefijo,
                            resolucion_dian = EXCLUDED.resolucion_dian,
                            clave_tecnica = EXCLUDED.clave_tecnica,
                            rango_desde = EXCLUDED.rango_desde,
                            rango_hasta = EXCLUDED.rango_hasta,
                            numero_actual = EXCLUDED.numero_actual,
                            prefijo_nc = EXCLUDED.prefijo_nc,
                            numero_actual_nc = EXCLUDED.numero_actual_nc,
                            prefijo_nd = EXCLUDED.prefijo_nd,
                            numero_actual_nd = EXCLUDED.numero_actual_nd,
                            vigencia_desde = EXCLUDED.vigencia_desde,
                            vigencia_hasta = EXCLUDED.vigencia_hasta,
                            is_active = EXCLUDED.is_active,
                            updated_at = now()
                        """,
                id,
                sociedadId,
                codigo,
                nombre,
                direccion,
                prefijo,
                resolucionDian,
                claveTecnica,
                rangoDesde,
                rangoHasta,
                numeroActual,
                safePrefijoNc,
                safeNumeroNc,
                safePrefijoNd,
                safeNumeroNd,
                vigenciaDesde,
                vigenciaHasta,
                activo
        );
    }

    private static PuntoVenta mapPuntoVentaRow(ResultSet rs) throws SQLException {
        return new PuntoVenta(
                rs.getObject("id", UUID.class),
                rs.getObject("sociedad_id", UUID.class),
                rs.getString("razon_social"),
                rs.getString("codigo"),
                rs.getString("nombre"),
                rs.getString("direccion"),
                rs.getString("prefijo"),
                rs.getString("resolucion_dian"),
                rs.getString("clave_tecnica"),
                rs.getObject("rango_desde", Long.class),
                rs.getObject("rango_hasta", Long.class),
                rs.getObject("numero_actual", Long.class),
                rs.getString("prefijo_nc"),
                rs.getObject("numero_actual_nc", Long.class),
                rs.getString("prefijo_nd"),
                rs.getObject("numero_actual_nd", Long.class),
                rs.getObject("vigencia_desde", LocalDate.class),
                rs.getObject("vigencia_hasta", LocalDate.class),
                rs.getBoolean("is_active")
        );
    }

    private static String normalizePrefijoDocumento(String prefijo, String fallback) {
        if (prefijo == null || prefijo.isBlank()) {
            return fallback;
        }
        return prefijo.trim().toUpperCase();
    }

    private void ensureCompanyForSociedad(UUID sociedadId) {
        jdbcTemplate.update(
                """
                        INSERT INTO companies (
                            id, nit, dv, razon_social, email, direccion, dian_config, is_active
                        )
                        SELECT id, nit, '0', razon_social, correo_emision, '{}'::jsonb,
                               jsonb_build_object(
                                   'ambiente', dian_ambiente,
                                   'regimen_fiscal', dian_regimen_fiscal
                               )
                               || CASE
                                   WHEN NULLIF(dian_software_id, '') IS NOT NULL
                                       THEN jsonb_build_object('software_id', dian_software_id)
                                   ELSE '{}'::jsonb
                               END,
                               true
                        FROM sociedades
                        WHERE id = ?
                        ON CONFLICT (id) DO UPDATE SET
                            nit = EXCLUDED.nit,
                            razon_social = EXCLUDED.razon_social,
                            email = EXCLUDED.email,
                            dian_config = COALESCE(companies.dian_config, '{}'::jsonb)
                                || jsonb_build_object(
                                    'ambiente', EXCLUDED.dian_config -> 'ambiente',
                                    'regimen_fiscal', EXCLUDED.dian_config -> 'regimen_fiscal'
                                )
                                || CASE
                                    WHEN NULLIF(EXCLUDED.dian_config ->> 'software_id', '') IS NOT NULL
                                        THEN jsonb_build_object(
                                            'software_id', EXCLUDED.dian_config ->> 'software_id'
                                        )
                                    ELSE '{}'::jsonb
                                END,
                            updated_at = now()
                        """,
                sociedadId
        );
    }

    private void syncCompanyDianConfig(UUID sociedadId, String dianSoftwarePinPlaintext) {
        if (dianSoftwarePinPlaintext != null) {
            jdbcTemplate.update(
                    """
                            UPDATE companies c
                            SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)
                                || jsonb_build_object('ambiente', s.dian_ambiente)
                                || jsonb_build_object('regimen_fiscal', s.dian_regimen_fiscal)
                                || CASE
                                    WHEN NULLIF(s.dian_software_id, '') IS NOT NULL
                                        THEN jsonb_build_object('software_id', s.dian_software_id)
                                    ELSE '{}'::jsonb
                                END
                                || jsonb_build_object('pin', ?::text),
                                updated_at = now()
                            FROM sociedades s
                            WHERE c.id = s.id
                              AND s.id = ?
                            """,
                    dianSoftwarePinPlaintext,
                    sociedadId
            );
            return;
        }

        jdbcTemplate.update(
                """
                        UPDATE companies c
                        SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)
                            || jsonb_build_object('ambiente', s.dian_ambiente)
                            || jsonb_build_object('regimen_fiscal', s.dian_regimen_fiscal)
                            || CASE
                                WHEN NULLIF(s.dian_software_id, '') IS NOT NULL
                                    THEN jsonb_build_object('software_id', s.dian_software_id)
                                ELSE '{}'::jsonb
                            END,
                            updated_at = now()
                        FROM sociedades s
                        WHERE c.id = s.id
                          AND s.id = ?
                        """,
                sociedadId
        );
    }

    private boolean sociedadExists(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sociedades WHERE id = ?)",
                Boolean.class,
                id
        );
        return Boolean.TRUE.equals(exists);
    }

    public void ensureLocalDianMockPuntoVenta() {
        ensureCompanyForSociedad(PROTECTED_SOCIEDAD_ID);
        savePuntoVenta(
                LOCAL_DIAN_MOCK_PUNTO_VENTA_ID,
                PROTECTED_SOCIEDAD_ID,
                "DIAN-MOCK",
                "Punto de venta DIAN Mock (Local)",
                "Entorno local - pruebas DIAN Habilitacion",
                "EPR",
                "18764000000000",
                "CLAVE-TECNICA-DIAN-MOCK-LOCAL",
                1L,
                99_999_999L,
                0L,
                "NC",
                0L,
                "ND",
                0L,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusYears(10),
                true
        );
    }

    private String normalizeDianAmbiente(String value) {
        if (value == null || value.isBlank()) {
            return "Habilitacion";
        }
        String trimmed = value.trim();
        if ("Produccion".equalsIgnoreCase(trimmed) || "Producción".equalsIgnoreCase(trimmed)) {
            return "Produccion";
        }
        if ("Mock".equalsIgnoreCase(trimmed)) {
            return "Mock";
        }
        return "Habilitacion";
    }
}
