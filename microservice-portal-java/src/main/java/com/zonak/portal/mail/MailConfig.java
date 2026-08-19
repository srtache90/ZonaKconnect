package com.zonak.portal.mail;

import java.util.Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(MailAppProperties.class)
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(MailAppProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        if (StringUtils.hasText(properties.getUsername())) {
            sender.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            sender.setPassword(properties.getPassword());
        }

        Properties mailProps = sender.getJavaMailProperties();
        mailProps.put("mail.transport.protocol", "smtp");
        mailProps.put("mail.smtp.auth", Boolean.toString(properties.isSmtpAuth()));
        mailProps.put("mail.smtp.starttls.enable", Boolean.toString(properties.isSmtpStarttls()));
        return sender;
    }
}
