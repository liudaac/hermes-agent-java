package com.nousresearch.hermes.business.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Simple SMTP email sender used by {@link BusinessApprovalNotifier} for
 * {@code EMAIL} notification targets.
 *
 * <p>Configuration (env vars or -D system properties):
 * <ul>
 *   <li>{@code NOTIFY_SMTP_HOST} / {@code notify.smtp.host} — default smtp.163.com</li>
 *   <li>{@code NOTIFY_SMTP_PORT} / {@code notify.smtp.port} — default 465</li>
 *   <li>{@code NOTIFY_EMAIL_SENDER} / {@code notify.email.sender} — from address</li>
 *   <li>{@code NOTIFY_EMAIL_PASSWORD} / {@code notify.email.password} — SMTP auth</li>
 *   <li>{@code NOTIFY_EMAIL_SSL} / {@code notify.email.ssl} — default true</li>
 * </ul>
 *
 * <p>Falls back to the ALERT_SMTP_* / alert.smtp.* / alert.email.* env vars used by
 * {@link com.nousresearch.hermes.tenant.metrics.EmailAlertChannel} if NOTIFY_* vars
 * are not set, so operators only need to configure one SMTP account.
 */
final class EmailSender {
    private static final Logger logger = LoggerFactory.getLogger(EmailSender.class);

    private final String smtpHost;
    private final int smtpPort;
    private final String sender;
    private final String password;
    private final boolean useSsl;
    private final boolean configured;

    EmailSender() {
        this.smtpHost = env("NOTIFY_SMTP_HOST", "ALERT_SMTP_HOST", "notify.smtp.host", "alert.smtp.host", "smtp.163.com");
        this.smtpPort = Integer.parseInt(env("NOTIFY_SMTP_PORT", "ALERT_SMTP_PORT", "notify.smtp.port", "alert.smtp.port", "465"));
        this.sender = env("NOTIFY_EMAIL_SENDER", "ALERT_EMAIL_SENDER", "notify.email.sender", "alert.email.sender", "");
        this.password = env("NOTIFY_EMAIL_PASSWORD", "ALERT_EMAIL_PASSWORD", "notify.email.password", "alert.email.password", "");
        this.useSsl = Boolean.parseBoolean(env("NOTIFY_EMAIL_SSL", "ALERT_EMAIL_SSL", "notify.email.ssl", "alert.email.ssl", "true"));
        this.configured = !sender.isEmpty() && !password.isEmpty();
    }

    boolean isConfigured() { return configured; }

    /**
     * Send a plain-text email.
     *
     * @param to      recipient address
     * @param subject subject line
     * @param body    plain-text body
     * @return true if sent successfully
     */
    boolean send(String to, String subject, String body) {
        if (!configured) {
            logger.debug("Email notifier not configured (set NOTIFY_EMAIL_SENDER/PASSWORD or ALERT_EMAIL_*)");
            return false;
        }
        if (to == null || to.isBlank()) {
            logger.warn("Email send skipped: no recipient");
            return false;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", smtpPort);
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.timeout", "8000");
            if (useSsl) {
                props.put("mail.smtp.socketFactory.port", smtpPort);
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            }
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(sender, password);
                }
            });
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(sender));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            msg.setSubject(subject, "UTF-8");
            msg.setText(body, "UTF-8");
            Transport.send(msg);
            logger.info("Notification email sent to {}: {}", to, subject);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to send notification email to {}: {}", to, e.getMessage());
            return false;
        }
    }

    /** Look up first non-empty env/system property, falling back to def. */
    private static String env(String env1, String env2, String prop1, String prop2, String def) {
        String v = System.getenv(env1);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(env2);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(prop1);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(prop2);
        if (v != null && !v.isBlank()) return v;
        return def;
    }
}
