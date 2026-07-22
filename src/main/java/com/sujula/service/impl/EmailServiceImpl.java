package com.sujula.service.impl;

import com.sujula.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;


    @Value("${app.mail.from:noreply@sujula.com}")
    private String fromAddress;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    @Override
    public void sendVerificationEmail(String toEmail, String fullName, String token) {
        String link = frontendUrl + "/auth/verify-email?token=" + token;
        send(toEmail,
                "Verify your Sujula account",
                "Hi " + fullName + ",\n\n"
                + "Welcome to Sujula! Please verify your email address by clicking the link below:\n\n"
                + link + "\n\n"
                + "This link expires in 24 hours.\n\n"
                + "If you did not create an account, you can safely ignore this email.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendRegistrationConfirmationEmail(String toEmail, String fullName) {
        send(toEmail,
                "Welcome to Sujula",
                "Hi " + fullName + ",\n\n"
                + "Your Sujula account has been created successfully.\n\n"
                + "You are signed in now, but please verify your email address to keep your account fully active.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String link = frontendUrl + "/auth/reset-password?token=" + token;
        send(toEmail,
                "Reset your Sujula password",
                "Hi " + fullName + ",\n\n"
                + "We received a request to reset your password. Click the link below:\n\n"
                + link + "\n\n"
                + "This link expires in 24 hour.\n\n"
                + "If you did not request a password reset, please ignore this email.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendOrderConfirmationEmail(String toEmail, String fullName, String orderNumber) {
        send(toEmail,
                "Order confirmed — " + orderNumber,
                "Hi " + fullName + ",\n\n"
                + "Your order " + orderNumber + " has been placed successfully.\n\n"
                + "You can track your order at: " + frontendUrl + "/orders/" + orderNumber + "\n\n"
                + "Thank you for shopping with Sujula!\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendVendorOrderNotification(String toEmail, String vendorName, String orderNumber) {
        send(toEmail,
                "New order received — " + orderNumber,
                "Hi " + vendorName + ",\n\n"
                + "You have received a new order (" + orderNumber + "). "
                + "Please log in to your vendor dashboard to confirm and process it.\n\n"
                + frontendUrl + "/vendor/orders\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendAdminPasswordResetEmail(String toEmail, String fullName, String newPassword) {
        send(toEmail,
                "Your Sujula password has been reset",
                "Hi " + fullName + ",\n\n"
                + "An administrator has reset your account password.\n\n"
                + "Your new temporary password is:\n\n"
                + "    " + newPassword + "\n\n"
                + "Please log in and change your password immediately:\n"
                + frontendUrl + "/login\n\n"
                + "If you did not expect this change, please contact support right away.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendVendorStatusChangeEmail(String toEmail, String storeName,
                                            String newStatus, String reason) {
        String subject = "Your Sujula store status has been updated — " + newStatus;
        String body = "Hi,\n\n"
                + "The status of your store \"" + storeName + "\" has been updated to: " + newStatus + ".\n\n"
                + "Reason: " + reason + "\n\n"
                + ("APPROVED".equalsIgnoreCase(newStatus)
                    ? "You can now manage your store at: " + frontendUrl + "/vendor\n\n"
                    : "If you believe this is in error, please contact support.\n\n")
                + "The Sujula Team";
        send(toEmail, subject, body);
    }

    @Async
    @Override
    public void sendDriverStatusChangeEmail(String toEmail, String fullName,
                                            String newStatus, String reason) {
        String subject = "Your Sujula driver account status has been updated — " + newStatus;
        String body = "Hi " + fullName + ",\n\n"
                + "Your driver account status has been updated to: " + newStatus + ".\n\n"
                + "Reason: " + reason + "\n\n"
                + ("APPROVED".equalsIgnoreCase(newStatus)
                    ? "You can now log in and start accepting deliveries: " + frontendUrl + "/login\n\n"
                    : "If you believe this is in error, please contact support.\n\n")
                + "The Sujula Team";
        send(toEmail, subject, body);
    }

    @Async
    @Override
    public void sendPickupPointStatusChangeEmail(String toEmail, String pointName,
                                                 String newStatus, String reason) {
        String subject = "Your Sujula pickup point status has been updated — " + newStatus;
        String body = "Hi,\n\n"
                + "The status of your pickup point \"" + pointName + "\" has been updated to: " + newStatus + ".\n\n"
                + "Reason: " + reason + "\n\n"
                + ("APPROVED".equalsIgnoreCase(newStatus)
                    ? "Your pickup point is now active on the Sujula platform. "
                        + "Log in to your operator dashboard to manage deliveries:\n"
                        + frontendUrl + "/pickup-operator\n\n"
                    : "If you believe this is in error, please contact support.\n\n")
                + "The Sujula Team";
        send(toEmail, subject, body);
    }

    @Async
    @Override
    public void sendPickupPointApplicationReceivedEmail(String toEmail, String pointName,
                                                        String managerName) {
        String greeting = (managerName != null && !managerName.isBlank())
                ? "Hi " + managerName : "Hi";
        send(toEmail,
                "Application received — " + pointName,
                greeting + ",\n\n"
                + "Thank you for applying to become a Sujula pickup point operator!\n\n"
                + "We have received your application for \"" + pointName + "\" "
                + "and it is currently under review by our team.\n\n"
                + "What happens next:\n"
                + "  1. Our team will verify the details you provided.\n"
                + "  2. You will receive an email once a decision has been made (typically within 2 business days).\n"
                + "  3. If approved, your pickup point will appear on the Sujula platform.\n\n"
                + "You can check the current status of your application at any time:\n"
                + frontendUrl + "/pickup-registration/status\n\n"
                + "If you have any questions, please contact support.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendPickupPointCreatedByAdminEmail(String toEmail, String pointName,
                                                   String managerName) {
        String greeting = (managerName != null && !managerName.isBlank())
                ? "Hi " + managerName : "Hi";
        send(toEmail,
                "Your pickup point is live on Sujula — " + pointName,
                greeting + ",\n\n"
                + "A Sujula administrator has created and activated your pickup point:\n\n"
                + "  Point name : " + pointName + "\n"
                + "  Status     : APPROVED\n\n"
                + "Your pickup point is now live on the platform and ready to receive packages.\n\n"
                + "Log in to your operator dashboard to get started:\n"
                + frontendUrl + "/pickup-operator\n\n"
                + "If you did not expect this notification, please contact support immediately.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendPickupPointProfileUpdatedEmail(String toEmail, String pointName,
                                                   String updatedBy) {
        send(toEmail,
                "Pickup point profile updated — " + pointName,
                "Hi,\n\n"
                + "The profile for your pickup point \"" + pointName + "\" has been updated by " + updatedBy + ".\n\n"
                + "If this change was expected, no action is needed.\n\n"
                + "You can review the current details of your pickup point at:\n"
                + frontendUrl + "/pickup-operator/profile\n\n"
                + "If you did not make this change and believe this was done in error, "
                + "please contact support immediately.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendAccountStatusChangeEmail(String toEmail, String fullName,
                                             boolean enabled, String reason) {
        String action  = enabled ? "enabled" : "disabled";
        String subject = "Your Sujula account has been " + action;
        String body = "Hi " + fullName + ",\n\n"
                + "Your account has been " + action + " by an administrator.\n\n"
                + "Reason: " + reason + "\n\n"
                + (enabled
                    ? "You may now log in at: " + frontendUrl + "/login\n\n"
                    : "If you believe this was done in error, please contact support.\n\n")
                + "The Sujula Team";
        send(toEmail, subject, body);
    }

    @Async
    @Override
    public void sendDriverWelcomeEmail(String toEmail, String fullName) {
        send(toEmail,
                "Welcome to Sujula — your driver account is ready",
                "Hi " + fullName + ",\n\n"
                + "Your driver account has been created and approved by our team.\n\n"
                + "You can now log in and start accepting deliveries:\n"
                + frontendUrl + "/login\n\n"
                + "If you have any questions, please contact support.\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendDriverDeliveryAssignedEmail(String toEmail, String fullName,
                                                String trackingNumber, BigDecimal earningAmount) {
        send(toEmail,
                "New delivery assigned — " + trackingNumber,
                "Hi " + fullName + ",\n\n"
                + "A new delivery has been assigned to you.\n\n"
                + "Tracking number : " + trackingNumber + "\n"
                + "Your earning    : " + earningAmount + "\n\n"
                + "Log in to your driver app to view the details and start the delivery:\n"
                + frontendUrl + "/login\n\n"
                + "The Sujula Team");
    }

    @Async
    @Override
    public void sendDriverDeliveryRemovedEmail(String toEmail, String fullName,
                                               String trackingNumber) {
        send(toEmail,
                "Delivery removed — " + trackingNumber,
                "Hi " + fullName + ",\n\n"
                + "The following delivery has been removed from your assignments:\n\n"
                + "Tracking number : " + trackingNumber + "\n\n"
                + "If you believe this was done in error, please contact your supervisor.\n\n"
                + "The Sujula Team");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void send(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
        } catch (Exception e) {
            // Log without the recipient address — email addresses are PII and must not
            // appear in log aggregators (Loki / Datadog / CloudWatch) where they are
            // retained, searchable, and potentially accessible to ops personnel.
            // Use a masked form (keep domain for routing-issue diagnosis) for traceability.
            String masked = maskEmail(to);
            log.error("Failed to send email to {} (subject: '{}'): {}", masked, subject, e.getMessage());
        }
    }

    /**
     * Masks the local-part of an email address to protect PII in logs.
     * Example: "john.doe@example.com" → "j***@example.com"
     */
    private static String maskEmail(String email) {
        if (email == null) return "(null)";
        int atIdx = email.indexOf('@');
        if (atIdx <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(atIdx);
    }
}
