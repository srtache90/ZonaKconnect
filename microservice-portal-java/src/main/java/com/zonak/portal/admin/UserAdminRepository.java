package com.zonak.portal.admin;

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
                (rs, rowNum) -> new AdminUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("rol"),
                        parseUuidList(rs.getString("sociedad_ids")),
                        rs.getString("sociedades_label")
                )
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
                (rs, rowNum) -> new AdminUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("rol"),
                        parseUuidList(rs.getString("sociedad_ids")),
                        ""
                ),
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
            String sociedadesLabel
    ) {
        public String sociedadIdsCsv() {
            if (sociedadIds == null || sociedadIds.isEmpty()) {
                return "";
            }
            return sociedadIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        }
    }
}
