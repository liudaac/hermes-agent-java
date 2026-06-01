package com.nousresearch.hermes.tenant.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import javax.mail.*;
import javax.mail.internet.*;

/**
 * 邮件告警渠道
 * 
 * 使用 SMTP 发送告警邮件。
 * 配置来源：环境变量或系统属性
 */
public class EmailAlertChannel implements AlertChannel {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailAlertChannel.class);
    
    private final String smtpHost;
    private final int smtpPort;
    private final String sender;
    private final String password;
    private final String recipient;
    private final boolean useSsl;
    private final boolean enabled;
    
    public EmailAlertChannel() {
        this.smtpHost = System.getenv().getOrDefault("ALERT_SMTP_HOST", 
            System.getProperty("alert.smtp.host", "smtp.163.com"));
        this.smtpPort = Integer.parseInt(System.getenv().getOrDefault("ALERT_SMTP_PORT", 
            System.getProperty("alert.smtp.port", "465")));
        this.sender = System.getenv().getOrDefault("ALERT_EMAIL_SENDER", 
            System.getProperty("alert.email.sender", ""));
        this.password = System.getenv().getOrDefault("ALERT_EMAIL_PASSWORD", 
            System.getProperty("alert.email.password", ""));
        this.recipient = System.getenv().getOrDefault("ALERT_EMAIL_RECIPIENT", 
            System.getProperty("alert.email.recipient", ""));
        this.useSsl = Boolean.parseBoolean(System.getenv().getOrDefault("ALERT_EMAIL_SSL", 
            System.getProperty("alert.email.ssl", "true")));
        this.enabled = !sender.isEmpty() && !password.isEmpty() && !recipient.isEmpty();
    }
    
    @Override
    public boolean send(MetricsCollector.AlertLevel level, String tenantId, String type, String message) {
        if (!enabled) {
            logger.debug("Email alert channel not configured");
            return false;
        }
        
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", smtpPort);
            
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
            
            MimeMessage email = new MimeMessage(session);
            email.setFrom(new InternetAddress(sender));
            email.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            
            String subject = String.format("[Hermes Alert] [%s] %s - Tenant: %s", 
                level, type, tenantId);
            email.setSubject(subject);
            
            String body = buildEmailBody(level, tenantId, type, message);
            email.setText(body);
            
            Transport.send(email);
            
            logger.info("Alert email sent: {} -> {}", subject, recipient);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to send alert email: {}", e.getMessage());
            return false;
        }
    }
    
    private String buildEmailBody(MetricsCollector.AlertLevel level, String tenantId, 
                                   String type, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hermes Agent Alert\n");
        sb.append("==================\n\n");
        sb.append("Level: ").append(level).append("\n");
        sb.append("Tenant: ").append(tenantId).append("\n");
        sb.append("Type: ").append(type).append("\n");
        sb.append("Time: ").append(java.time.Instant.now()).append("\n\n");
        sb.append("Message:\n");
        sb.append(message).append("\n\n");
        sb.append("---\n");
        sb.append("Sent by Hermes Agent Java\n");
        return sb.toString();
    }
    
    @Override
    public String getName() {
        return "email";
    }
    
    @Override
    public boolean isAvailable() {
        return enabled;
    }
}
