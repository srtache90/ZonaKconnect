package com.zonak.portal.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mail")
public class MailAppProperties {
    private boolean dispatchEnabled = true;
    private String host = "localhost";
    private int port = 1025;
    private String username = "";
    private String password = "";
    private String from = "no-reply@zonak.local";
    private boolean smtpAuth = false;
    private boolean smtpStarttls = false;
    private boolean receptionEnabled = true;
    private String receptionHost = "localhost";
    private int receptionPort = 1110;
    private String receptionProtocol = "pop3";
    private String receptionUsername = "recepcion";
    private String receptionPassword = "recepcion";

    public boolean isDispatchEnabled() {
        return dispatchEnabled;
    }

    public void setDispatchEnabled(boolean dispatchEnabled) {
        this.dispatchEnabled = dispatchEnabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public boolean isSmtpAuth() {
        return smtpAuth;
    }

    public void setSmtpAuth(boolean smtpAuth) {
        this.smtpAuth = smtpAuth;
    }

    public boolean isSmtpStarttls() {
        return smtpStarttls;
    }

    public void setSmtpStarttls(boolean smtpStarttls) {
        this.smtpStarttls = smtpStarttls;
    }

    public boolean isReceptionEnabled() {
        return receptionEnabled;
    }

    public void setReceptionEnabled(boolean receptionEnabled) {
        this.receptionEnabled = receptionEnabled;
    }

    public String getReceptionHost() {
        return receptionHost;
    }

    public void setReceptionHost(String receptionHost) {
        this.receptionHost = receptionHost;
    }

    public int getReceptionPort() {
        return receptionPort;
    }

    public void setReceptionPort(int receptionPort) {
        this.receptionPort = receptionPort;
    }

    public String getReceptionProtocol() {
        return receptionProtocol;
    }

    public void setReceptionProtocol(String receptionProtocol) {
        this.receptionProtocol = receptionProtocol;
    }

    public String getReceptionUsername() {
        return receptionUsername;
    }

    public void setReceptionUsername(String receptionUsername) {
        this.receptionUsername = receptionUsername;
    }

    public String getReceptionPassword() {
        return receptionPassword;
    }

    public void setReceptionPassword(String receptionPassword) {
        this.receptionPassword = receptionPassword;
    }
}
