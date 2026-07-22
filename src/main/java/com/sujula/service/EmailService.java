package com.sujula.service;

import java.math.BigDecimal;

public interface EmailService {

    void sendVerificationEmail(String toEmail, String fullName, String token);

    void sendRegistrationConfirmationEmail(String toEmail, String fullName);

    void sendPasswordResetEmail(String toEmail, String fullName, String token);

    void sendOrderConfirmationEmail(String toEmail, String fullName, String orderNumber);

    void sendVendorOrderNotification(String toEmail, String vendorName, String orderNumber);

    void sendAdminPasswordResetEmail(String toEmail, String fullName, String newPassword);

    void sendAccountStatusChangeEmail(String toEmail, String fullName, boolean enabled, String reason);

    void sendVendorStatusChangeEmail(String toEmail, String storeName, String newStatus, String reason);

    void sendDriverStatusChangeEmail(String toEmail, String fullName, String newStatus, String reason);

    void sendPickupPointStatusChangeEmail(String toEmail, String pointName, String newStatus, String reason);

    void sendPickupPointApplicationReceivedEmail(String toEmail, String pointName, String managerName);

    void sendPickupPointCreatedByAdminEmail(String toEmail, String pointName, String managerName);

    void sendPickupPointProfileUpdatedEmail(String toEmail, String pointName, String updatedBy);

    void sendDriverWelcomeEmail(String toEmail, String fullName);

    void sendDriverDeliveryAssignedEmail(String toEmail, String fullName, String trackingNumber, BigDecimal earningAmount);

    void sendDriverDeliveryRemovedEmail(String toEmail, String fullName, String trackingNumber);
}
