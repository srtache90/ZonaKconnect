package com.zonak.portal.mail;

import java.util.Locale;
import java.util.Properties;
import org.springframework.util.StringUtils;

public record IncomingMailbox(
        String host,
        int port,
        String protocol,
        String username,
        String password,
        String sourceLabel
) {
    public static IncomingMailbox fromSociedad(SociedadMailAccount account) {
        String protocol = resolveProtocol(account.hostImap(), account.puertoImap(), "imap");
        return new IncomingMailbox(
                account.hostImap().trim(),
                account.puertoImap(),
                protocol,
                account.usuarioImap().trim(),
                account.passwordImap(),
                account.incomingLabel()
        );
    }

    public static IncomingMailbox fromGlobal(MailAppProperties properties) {
        String protocol = resolveProtocol(
                properties.getReceptionHost(),
                properties.getReceptionPort(),
                defaultProtocol(properties.getReceptionProtocol())
        );
        return new IncomingMailbox(
                properties.getReceptionHost(),
                properties.getReceptionPort(),
                protocol,
                properties.getReceptionUsername(),
                properties.getReceptionPassword(),
                "buzón global (" + properties.getReceptionHost() + ":" + properties.getReceptionPort() + ")"
        );
    }

    public Properties toSessionProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".connectiontimeout", "15000");
        props.put("mail." + protocol + ".timeout", "20000");
        if (protocol.endsWith("s")) {
            props.put("mail." + protocol + ".ssl.enable", "true");
            props.put("mail." + protocol + ".ssl.trust", "*");
        } else {
            props.put("mail." + protocol + ".starttls.enable", "true");
        }
        return props;
    }

    static String resolveProtocol(String host, Integer port, String fallback) {
        if (port != null) {
            if (port == 993) {
                return "imaps";
            }
            if (port == 995) {
                return "pop3s";
            }
            if (port == 143) {
                return "imap";
            }
            if (port == 110 || port == 1110) {
                return "pop3";
            }
        }

        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains("pop")) {
            return port != null && port == 995 ? "pop3s" : "pop3";
        }
        if (normalizedHost.contains("imap")) {
            return port != null && port == 993 ? "imaps" : "imap";
        }
        return defaultProtocol(fallback);
    }

    private static String defaultProtocol(String protocol) {
        if (!StringUtils.hasText(protocol)) {
            return "imap";
        }
        return protocol.trim().toLowerCase(Locale.ROOT);
    }
}
