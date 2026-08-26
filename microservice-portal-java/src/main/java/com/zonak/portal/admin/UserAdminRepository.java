package com.zonak.portal.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class UserAdminRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AdminUser> findUsers() {
        return jdbcTemplate.query(
                """
                        SELECT u.id,
                               u.username,
                               u.rol,
                               COALESCE(
                                   string_agg(s.razon_social, ', ' ORDER BY s.razon_social),
                                   CASE WHEN EXISTS (
                                       SELECT 1 FROM usuario_sociedades usg
                                       WHERE usg.usuario_id = u.id AND usg.sociedad_id IS NULL
                                   ) THEN 'Todas las sociedades' ELSE '' END
                               ) AS sociedades_label,
                               COALESCE(
                                   (
                                       SELECT string_agg(us.sociedad_id::text, ',')
                                       FROM usuario_sociedades us
                                       WHERE us.usuario_id = u.id AND us.sociedad_id IS NOT NULL
                                   ),
                                   ''
                               ) AS sociedad_ids
                        FROM usuarios u
                        LEFT JOIN usuario_sociedades us ON us.usuario_id = u.id AND us.sociedad_id IS NOT NULL
                        LEFT JOIN sociedades s ON s.id = us.sociedad_id
                        GROUP BY u.id, u.username, u.rol
                        ORDER BY u.username ASC
                        """,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    List<UserPointScope> scopes = findPointScopes(id);
                    return new AdminUser(
                            id,
                            rs.getString("username"),
                            rs.getString("rol"),
                            parseUuidList(rs.getString("sociedad_ids")),
                            rs.getString("sociedades_label"),
                            scopes,
                            buildPuntosLabel(scopes)
                    );
                }
        );
    }

    public Optional<AdminUser> findById(UUID id) {
        List<AdminUser> users = jdbcTemplate.query(
                """
                        SELECT u.id, u.username, u.rol,
                               COALESCE(
                                   (
                                       SELECT string_agg(us.sociedad_id::text, ',')
                                       FROM usuario_sociedades us
                                       WHERE us.usuario_id = u.id AND us.sociedad_id IS NOT NULL
                                   ),
                                   ''
                               ) AS sociedad_ids
                        FROM usuarios u
                        WHERE u.id = ?
                        """,
                (rs, rowNum) -> {
                    UUID uid = rs.getObject("id", UUID.class);
                    List<UserPointScope> scopes = findPointScopes(uid);
                    return new AdminUser(
                            uid,
                            rs.getString("username"),
                            rs.getString("rol"),
                            parseUuidList(rs.getString("sociedad_ids")),
                            "",
                            scopes,
                            buildPuntosLabel(scopes)
                    );
                },
                id
        );
        return users.stream().findFirst();
    }

    public boolean existsByUsername(String username, UUID excludeId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM usuarios
                        WHERE username = ?
                          AND (?::uuid IS NULL OR id <> ?)
                        """,
                Integer.class,
                username,
                excludeId,
                excludeId
        );
        return count != null && count > 0;
    }

    @Transactional
    public void saveUser(
            UUID id,
            String username,
            String passwordHashOrNull,
            String rol,
            List<UUID> sociedadIds
    ) {
        saveUser(id, username, passwordHashOrNull, rol, sociedadIds, List.of());
    }

    @Transactional
    public void saveUser(
            UUID id,
            String username,
            String passwordHashOrNull,
            String rol,
            List<UUID> sociedadIds,
            List<UserPointScope> pointScopes
    ) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM usuarios WHERE id = ?)",
                Boolean.class,
                id
        ));

        if (exists) {
            if (StringUtils.hasText(passwordHashOrNull)) {
                jdbcTemplate.update(
                        """
                                UPDATE usuarios
                                SET username = ?, password_hash = ?, rol = ?
                                WHERE id = ?
                                """,
                        username,
                        passwordHashOrNull,
                        rol,
                        id
                );
            } else {
                jdbcTemplate.update(
                        """
                                UPDATE usuarios
                                SET username = ?, rol = ?
                                WHERE id = ?
                                """,
                        username,
                        rol,
                        id
                );
            }
        } else {
            if (!StringUtils.hasText(passwordHashOrNull)) {
                throw new IllegalArgumentException("La contraseña es obligatoria para usuarios nuevos");
            }
            jdbcTemplate.update(
                    """
                            INSERT INTO usuarios (id, username, password_hash, rol)
                            VALUES (?, ?, ?, ?)
                            """,
                    id,
                    username,
                    passwordHashOrNull,
                    rol
            );
        }

        jdbcTemplate.update("DELETE FROM usuario_sociedades WHERE usuario_id = ?", id);
        if (sociedadIds == null || sociedadIds.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO usuario_sociedades (usuario_id, sociedad_id) VALUES (?, NULL)",
                    id
            );
            replacePointScopes(id, pointScopes);
            return;
        }

        for (UUID sociedadId : sociedadIds) {
            if (sociedadId == null) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO usuario_sociedades (usuario_id, sociedad_id) VALUES (?, ?)",
                    id,
                    sociedadId
            );
        }
        replacePointScopes(id, pointScopes);
    }

    private static List<UUID> parseUuidList(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(UUID::fromString)
                .collect(Collectors.toList());
    }

    public record AdminUser(
            UUID id,
            String username,
            String rol,
            List<UUID> sociedadIds,
            String sociedadesLabel,
            List<UserPointScope> pointScopes,
            String puntosLabel
    ) {
        public String sociedadIdsCsv() {
            if (sociedadIds == null || sociedadIds.isEmpty()) {
                return "";
            }
            return sociedadIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        }

        public String pointScopesCsv() {
            if (pointScopes == null || pointScopes.isEmpty()) {
                return "";
            }
            return pointScopes.stream()
                    .map(scope -> scope.sociedadId() + ":"
                            + (scope.allPointsOfSociety() || scope.emissionPointId() == null
                            ? "*"
                            : scope.emissionPointId().toString()))
                    .collect(Collectors.joining(","));
        }
    }

    public List<UserPointScope> findPointScopes(UUID usuarioId) {
        return jdbcTemplate.query(
                """
                        SELECT sociedad_id, emission_point_id
                        FROM usuario_puntos_venta
                        WHERE usuario_id = ?
                        ORDER BY sociedad_id, emission_point_id NULLS FIRST
                        """,
                (rs, rowNum) -> {
                    UUID sociedadId = rs.getObject("sociedad_id", UUID.class);
                    UUID pointId = rs.getObject("emission_point_id", UUID.class);
                    if (pointId == null) {
                        return UserPointScope.allOf(sociedadId);
                    }
                    return UserPointScope.point(sociedadId, pointId);
                },
                usuarioId
        );
    }

    @Transactional
    public void replacePointScopes(UUID usuarioId, List<UserPointScope> scopes) {
        jdbcTemplate.update("DELETE FROM usuario_puntos_venta WHERE usuario_id = ?", usuarioId);
        if (scopes == null) {
            return;
        }
        for (UserPointScope scope : scopes) {
            if (scope == null || scope.sociedadId() == null) {
                continue;
            }
            jdbcTemplate.update(
                    """
                            INSERT INTO usuario_puntos_venta (usuario_id, sociedad_id, emission_point_id)
                            VALUES (?, ?, ?)
                            """,
                    usuarioId,
                    scope.sociedadId(),
                    scope.allPointsOfSociety() ? null : scope.emissionPointId()
            );
        }
    }

    public List<UUID> findAllowedEmissionPointIds(UUID usuarioId, UUID sociedadId, boolean isAdmin) {
        if (isAdmin) {
            return jdbcTemplate.query(
                    """
                            SELECT id FROM emission_points
                            WHERE company_id = ? AND is_active = TRUE
                            ORDER BY codigo
                            """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    sociedadId
            );
        }

        Boolean allPoints = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM usuario_puntos_venta
                            WHERE usuario_id = ? AND sociedad_id = ? AND emission_point_id IS NULL
                        )
                        """,
                Boolean.class,
                usuarioId,
                sociedadId
        );
        if (Boolean.TRUE.equals(allPoints)) {
            return jdbcTemplate.query(
                    """
                            SELECT id FROM emission_points
                            WHERE company_id = ? AND is_active = TRUE
                            ORDER BY codigo
                            """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    sociedadId
            );
        }

        Integer scopedCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM usuario_puntos_venta
                        WHERE usuario_id = ? AND sociedad_id = ?
                        """,
                Integer.class,
                usuarioId,
                sociedadId
        );
        // Sin filas de alcance: compatibilidad = todos los puntos de la sociedad autorizada
        if (scopedCount == null || scopedCount == 0) {
            return jdbcTemplate.query(
                    """
                            SELECT id FROM emission_points
                            WHERE company_id = ? AND is_active = TRUE
                            ORDER BY codigo
                            """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    sociedadId
            );
        }

        return jdbcTemplate.query(
                """
                        SELECT emission_point_id
                        FROM usuario_puntos_venta
                        WHERE usuario_id = ?
                          AND sociedad_id = ?
                          AND emission_point_id IS NOT NULL
                        """,
                (rs, rowNum) -> rs.getObject("emission_point_id", UUID.class),
                usuarioId,
                sociedadId
        );
    }

    public boolean hasUnrestrictedPoints(UUID usuarioId, UUID sociedadId, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        Boolean allPoints = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM usuario_puntos_venta
                            WHERE usuario_id = ? AND sociedad_id = ? AND emission_point_id IS NULL
                        )
                        """,
                Boolean.class,
                usuarioId,
                sociedadId
        );
        if (Boolean.TRUE.equals(allPoints)) {
            return true;
        }
        Integer scopedCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM usuario_puntos_venta
                        WHERE usuario_id = ? AND sociedad_id = ?
                        """,
                Integer.class,
                usuarioId,
                sociedadId
        );
        return scopedCount == null || scopedCount == 0;
    }

    private String buildPuntosLabel(List<UserPointScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "Todos los PV de sus sociedades";
        }
        List<String> parts = new ArrayList<>();
        for (UserPointScope scope : scopes) {
            if (scope == null || scope.sociedadId() == null) {
                continue;
            }
            if (scope.allPointsOfSociety() || scope.emissionPointId() == null) {
                String sociedad = jdbcTemplate.query(
                        "SELECT razon_social FROM sociedades WHERE id = ?",
                        (rs, rowNum) -> rs.getString("razon_social"),
                        scope.sociedadId()
                ).stream().findFirst().orElse(scope.sociedadId().toString());
                parts.add("★ Todos · " + sociedad);
            } else {
                String label = jdbcTemplate.query(
                        """
                                SELECT ep.codigo || ' - ' || ep.nombre || ' (' || s.razon_social || ')'
                                FROM emission_points ep
                                JOIN sociedades s ON s.id = ep.company_id
                                WHERE ep.id = ?
                                """,
                        (rs, rowNum) -> rs.getString(1),
                        scope.emissionPointId()
                ).stream().findFirst().orElse(scope.emissionPointId().toString());
                parts.add(label);
            }
        }
        if (parts.isEmpty()) {
            return "Todos los PV de sus sociedades";
        }
        if (parts.size() <= 3) {
            return String.join("; ", parts);
        }
        return parts.size() + " alcances: " + String.join("; ", parts.subList(0, 2)) + "…";
    }
}
