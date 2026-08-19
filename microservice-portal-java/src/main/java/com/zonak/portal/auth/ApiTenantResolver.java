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

    private record SociedadRow(UUID id, String razonSocial) {
    }
}
