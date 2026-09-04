package com.zonak.portal.integration.sap;

import com.zonak.portal.auth.ApiTenant;
import com.zonak.portal.security.SensitiveDataCryptoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class SapTenantResolver {
    private static final String LOCAL_PASSWORD_PREFIX = "LOCAL_";

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveDataCryptoService cryptoService;

    public SapTenantResolver(JdbcTemplate jdbcTemplate, SensitiveDataCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    public ApiTenant requireFromDocumento(SapEnviarDocumento documento) {
        SapEnviarDocumento.FelCabezaDocumento cabeza = documento == null ? null : documento.getFelCabezaDocumento();
        if (cabeza == null) {
            throw new IllegalArgumentException("felCabezaDocumento requerido");
        }
        Integer idEmpresa = parseIdEmpresa(cabeza.getIdEmpresa());
        String usuario = trim(cabeza.getUsuario());
        String contrasenia = trim(cabeza.getContrasenia());
        String prefijo = trim(cabeza.getPrefijo());
        if (idEmpresa == null) {
            throw new IllegalArgumentException("idEmpresa requerido para autenticar SAP");
        }
        if (!StringUtils.hasText(usuario) || !StringUtils.hasText(contrasenia)) {
            throw new IllegalArgumentException("usuario y contrasenia SAP son obligatorios");
        }

        SociedadRow sociedad = findByIdEmpresa(idEmpresa)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No hay sociedad con idEmpresa " + idEmpresa
                ));
        if (!constantEquals(usuario, sociedad.sapUsuario()) || !passwordMatches(contrasenia, sociedad.sapPasswordEnc())) {
            throw new IllegalArgumentException("Credenciales SAP inválidas para idEmpresa " + idEmpresa);
        }

        UUID emissionPointId = resolveEmissionPoint(sociedad.id(), prefijo);
        return new ApiTenant(sociedad.id(), emissionPointId, sociedad.razonSocial());
    }

    private Optional<SociedadRow> findByIdEmpresa(Integer idEmpresa) {
        List<SociedadRow> rows = jdbcTemplate.query(
                """
                        SELECT id, razon_social, sap_usuario, sap_password_enc
                        FROM sociedades
                        WHERE id_empresa = ?
                        """,
                (rs, rowNum) -> new SociedadRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("razon_social"),
                        rs.getString("sap_usuario"),
                        rs.getString("sap_password_enc")
                ),
                idEmpresa
        );
        return rows.stream().findFirst();
    }

    private UUID resolveEmissionPoint(UUID companyId, String prefijo) {
        if (StringUtils.hasText(prefijo)) {
            List<UUID> byPrefix = jdbcTemplate.query(
                    """
                            SELECT id
                            FROM emission_points
                            WHERE company_id = ?
                              AND is_active = TRUE
                              AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                              AND (
                                    BTRIM(prefijo) ILIKE BTRIM(?)
                                 OR BTRIM(prefijo_nc) ILIKE BTRIM(?)
                                 OR BTRIM(prefijo_nd) ILIKE BTRIM(?)
                              )
                            ORDER BY codigo ASC
                            LIMIT 2
                            """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    companyId,
                    prefijo,
                    prefijo,
                    prefijo
            );
            if (byPrefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "No hay punto de venta activo con prefijo '" + prefijo + "' para esta sociedad"
                );
            }
            if (byPrefix.size() > 1) {
                throw new IllegalArgumentException(
                        "Hay varios puntos de venta con prefijo '" + prefijo + "'. Desambigue en Configuración."
                );
            }
            return byPrefix.get(0);
        }

        List<UUID> fallback = jdbcTemplate.query(
                """
                        SELECT id
                        FROM emission_points
                        WHERE company_id = ?
                          AND is_active = TRUE
                          AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                        ORDER BY codigo ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId
        );
        return fallback.stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                "La sociedad no tiene punto de venta activo para emitir desde SAP"
        ));
    }

    private boolean passwordMatches(String provided, String storedEncrypted) {
        if (!StringUtils.hasText(storedEncrypted)) {
            return false;
        }
        String stored = storedEncrypted.trim();
        if (stored.startsWith(LOCAL_PASSWORD_PREFIX)) {
            return constantEquals(provided, stored.substring(LOCAL_PASSWORD_PREFIX.length()));
        }
        try {
            return constantEquals(provided, cryptoService.decryptToString(stored));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean constantEquals(String left, String right) {
        byte[] a = (left == null ? "" : left).getBytes(StandardCharsets.UTF_8);
        byte[] b = (right == null ? "" : right).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static Integer parseIdEmpresa(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("idEmpresa inválido: " + raw);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record SociedadRow(UUID id, String razonSocial, String sapUsuario, String sapPasswordEnc) {
    }
}
