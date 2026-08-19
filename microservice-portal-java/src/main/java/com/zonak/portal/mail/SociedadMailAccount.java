package com.zonak.portal.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SociedadMailAccount(
        UUID sociedadId,
        String razonSocial,
        String correoEmision,
        String correoRecepcion,
        String hostSmtp,
        Integer puertoSmtp,
        String usuarioSmtp,
        String passwordSmtp,
        String hostImap,
        Integer puertoImap,
        String usuarioImap,
        String passwordImap
) {
    public boolean hasIncomingMail() {
        return hasText(hostImap)
                && puertoImap != null
                && puertoImap > 0
                && hasText(usuarioImap)
                && hasText(passwordImap);
    }

    public boolean hasAnyIncomingAttempt() {
        return hasText(hostImap)
                || (puertoImap != null && puertoImap > 0)
                || hasText(usuarioImap)
                || hasText(passwordImap);
    }

    public boolean hasOutgoingMail() {
        return hasText(hostSmtp)
                && puertoSmtp != null
                && puertoSmtp > 0
                && hasText(usuarioSmtp)
                && hasText(passwordSmtp);
    }

    public String describeMissingIncoming() {
        List<String> missing = new ArrayList<>();
        if (!hasText(hostImap)) {
            missing.add("host IMAP");
        }
        if (puertoImap == null || puertoImap <= 0) {
            missing.add("puerto IMAP");
        }
        if (!hasText(usuarioImap)) {
            missing.add("usuario IMAP");
        }
        if (!hasText(passwordImap)) {
            missing.add("contraseña IMAP");
        }
        if (missing.isEmpty()) {
            return "completo";
        }
        return "falta " + String.join(", ", missing);
    }

    public String incomingLabel() {
        if (!hasIncomingMail()) {
            return "No configurado";
        }
        return hostImap + ":" + puertoImap;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
