package com.zonak.portal.mail;

import com.zonak.portal.security.SensitiveDataCryptoService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SociedadMailAccountRepository {
    private final JdbcTemplate jdbcTemplate;
    private final SensitiveDataCryptoService cryptoService;

    public SociedadMailAccountRepository(JdbcTemplate jdbcTemplate, SensitiveDataCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    public Optional<SociedadMailAccount> findBySociedadId(UUID sociedadId) {
        if (sociedadId == null) {
            return Optional.empty();
        }
        List<SociedadMailAccount> rows = jdbcTemplate.query(
                """
                        SELECT id, razon_social, correo_emision, correo_recepcion,
                               host_smtp, puerto_smtp, usuario_smtp, password_smtp_enc,
                               host_imap, puerto_imap, usuario_imap, password_imap_enc
                        FROM sociedades
                        WHERE id = ?
                        """,
                (rs, rowNum) -> new SociedadMailAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("razon_social"),
                        rs.getString("correo_emision"),
                        rs.getString("correo_recepcion"),
                        rs.getString("host_smtp"),
                        rs.getObject("puerto_smtp", Integer.class),
                        rs.getString("usuario_smtp"),
                        cryptoService.decryptToString(rs.getString("password_smtp_enc")),
                        rs.getString("host_imap"),
                        rs.getObject("puerto_imap", Integer.class),
                        rs.getString("usuario_imap"),
                        cryptoService.decryptToString(rs.getString("password_imap_enc"))
                ),
                sociedadId
        );
        return rows.stream().findFirst();
    }
}
