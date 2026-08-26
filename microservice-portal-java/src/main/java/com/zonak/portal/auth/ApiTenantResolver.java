package com.zonak.portal.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class ApiTenantResolver {
    private final JdbcTemplate jdbcTemplate;

    public ApiTenantResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ApiTenant> resolve(String apiKey, String requestedEmissionPointId) {
        if (!StringUtils.hasText(apiKey)) {
            return Optional.empty();
        }

        List<SociedadRow> sociedades = jdbcTemplate.query(
                """
                        SELECT id, razon_social
                        FROM sociedades
                        WHERE api_key = ?
                        """,
                (rs, rowNum) -> new SociedadRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("razon_social")
                ),
                apiKey
        );

        return sociedades.stream()
                .findFirst()
                .flatMap(sociedad -> resolveEmissionPoint(sociedad, requestedEmissionPointId));
    }

    private Optional<ApiTenant> resolveEmissionPoint(SociedadRow sociedad, String requestedEmissionPointId) {
        if (StringUtils.hasText(requestedEmissionPointId)) {
            UUID emissionPointId = UUID.fromString(requestedEmissionPointId);
            boolean belongsToTenant = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    """
                            SELECT EXISTS (
                                SELECT 1
                                FROM emission_points
                                WHERE id = ?
                                  AND company_id = ?
                                  AND is_active = TRUE
                                  AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                            )
                            """,
                    Boolean.class,
                    emissionPointId,
                    sociedad.id()
            ));

            return belongsToTenant
                    ? Optional.of(new ApiTenant(sociedad.id(), emissionPointId, sociedad.razonSocial()))
                    : Optional.empty();
        }

        List<UUID> emissionPointIds = jdbcTemplate.query(
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
                sociedad.id()
        );

        return emissionPointIds.stream()
                .findFirst()
                .map(emissionPointId -> new ApiTenant(sociedad.id(), emissionPointId, sociedad.razonSocial()));
    }

    public Optional<UUID> findActivePointByCodigo(UUID companyId, String codigo) {
        if (companyId == null || !StringUtils.hasText(codigo)) {
            return Optional.empty();
        }
        List<UUID> ids = jdbcTemplate.query(
                """
                        SELECT id
                        FROM emission_points
                        WHERE company_id = ?
                          AND codigo = ?
                          AND is_active = TRUE
                          AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                codigo.trim()
        );
        return ids.stream().findFirst();
    }

    public UUID requirePointByResolucion(UUID companyId, String resolucion, String prefijo) {
        return requirePointByResolucion(companyId, resolucion, prefijo, null);
    }

    public UUID requirePointByResolucion(
            UUID companyId,
            String resolucion,
            String prefijo,
            String referenciaNumero
    ) {
        if (companyId == null || !StringUtils.hasText(resolucion)) {
            throw new IllegalArgumentException("La resolución DIAN es obligatoria para rutar el punto de venta.");
        }
        String resolution = resolucion.trim();
        List<PointRow> matches = jdbcTemplate.query(
                """
                        SELECT id, prefijo, prefijo_nc, prefijo_nd, codigo
                        FROM emission_points
                        WHERE company_id = ?
                          AND BTRIM(resolucion_dian) = BTRIM(?)
                          AND is_active = TRUE
                          AND CURRENT_DATE BETWEEN vigencia_desde AND vigencia_hasta
                        ORDER BY prefijo ASC, codigo ASC
                        """,
                (rs, rowNum) -> new PointRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("prefijo"),
                        rs.getString("prefijo_nc"),
                        rs.getString("prefijo_nd"),
                        rs.getString("codigo")
                ),
                companyId,
                resolution
        );
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "No hay punto de venta activo con resolución " + resolution + " en esta sociedad."
            );
        }
        List<PointRow> selected = matches;
        if (StringUtils.hasText(prefijo)) {
            String prefix = prefijo.trim();
            selected = matches.stream().filter(row -> row.matchesPrefix(prefix)).toList();
            if (selected.isEmpty()) {
                throw new IllegalArgumentException(
                        "Resolución " + resolution + " encontrada, pero ningún PV tiene prefijo FE/NC/ND '"
                                + prefix + "'."
                );
            }
        }
        if (selected.size() > 1 && StringUtils.hasText(referenciaNumero)) {
            String invoicePrefix = leadingLetters(referenciaNumero);
            if (StringUtils.hasText(invoicePrefix)) {
                List<PointRow> byInvoicePrefix = selected.stream()
                        .filter(row -> invoicePrefix.equalsIgnoreCase(trim(row.prefijo())))
                        .toList();
                if (!byInvoicePrefix.isEmpty()) {
                    selected = byInvoicePrefix;
                }
            }
        }
        if (selected.size() == 1) {
            return selected.get(0).id();
        }
        if (selected.size() > 1 && !StringUtils.hasText(prefijo) && !StringUtils.hasText(referenciaNumero)) {
            String prefixes = selected.stream()
                    .map(row -> trim(row.prefijo()))
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalArgumentException(
                    "Hay " + selected.size() + " puntos con resolución " + resolution
                            + ". Envíe prefijo para desempatar. Prefijos: " + prefixes
            );
        }
        if (selected.size() > 1) {
            throw new IllegalArgumentException(
                    "Hay " + selected.size() + " puntos con resolución " + resolution
                            + " y prefijo NC/ND compartido. Incluya factura_referencia.numeroDocumento (ej. EPR1)."
            );
        }
        return matches.get(0).id();
    }

    private static String leadingLetters(String documentNumber) {
        if (!StringUtils.hasText(documentNumber)) {
            return "";
        }
        StringBuilder letters = new StringBuilder();
        for (char ch : documentNumber.trim().toCharArray()) {
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else if (letters.length() > 0) {
                break;
            }
        }
        return letters.toString();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record SociedadRow(UUID id, String razonSocial) {
    }

    private record PointRow(UUID id, String prefijo, String prefijoNc, String prefijoNd, String codigo) {
        boolean matchesPrefix(String prefix) {
            return prefix.equalsIgnoreCase(trim(prefijo))
                    || prefix.equalsIgnoreCase(trim(prefijoNc))
                    || prefix.equalsIgnoreCase(trim(prefijoNd));
        }
    }
}
