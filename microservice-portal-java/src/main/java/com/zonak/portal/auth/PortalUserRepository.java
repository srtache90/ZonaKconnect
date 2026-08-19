package com.zonak.portal.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PortalUserRepository {
    private static final UUID LOCAL_SOCIEDAD_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbcTemplate;

    public PortalUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthenticatedUser> findByUsername(String username) {
        List<AuthenticatedUser> users = jdbcTemplate.query(
                """
                        SELECT id, username, password_hash, rol
                        FROM usuarios
                        WHERE username = ?
                        """,
                (rs, rowNum) -> mapUser(rs),
                username
        );

        return users.stream().findFirst();
    }

    private AuthenticatedUser mapUser(ResultSet rs) throws SQLException {
        UUID userId = rs.getObject("id", UUID.class);
        return new AuthenticatedUser(
                userId,
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("rol"),
                findTenantIds(userId)
        );
    }

    private List<UUID> findTenantIds(UUID userId) {
        Boolean hasGlobalAccess = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM usuario_sociedades
                            WHERE usuario_id = ?
                              AND sociedad_id IS NULL
                        )
                        """,
                Boolean.class,
                userId
        );

        if (Boolean.TRUE.equals(hasGlobalAccess)) {
            return jdbcTemplate.query(
                    """
                            SELECT id
                            FROM sociedades
                            ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, razon_social ASC
                            """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    LOCAL_SOCIEDAD_ID
            );
        }

        return jdbcTemplate.query(
                """
                        SELECT sociedad_id
                        FROM usuario_sociedades
                        WHERE usuario_id = ?
                          AND sociedad_id IS NOT NULL
                        ORDER BY creado_at ASC
                        """,
                (rs, rowNum) -> rs.getObject("sociedad_id", UUID.class),
                userId
        );
    }
}
