package com.sujula.service;

import java.math.BigDecimal;

public interface EmailService {

    void sendVerificationEmail(String toEmail, String fullName, String token);

    void sendRegistrationConfirmationEmail(String toEmail, String fullName);

    void sendPasswordResetEmail(String toEmail, String fullName, String token);

    /**
     * Warns the owner that sign-in is being attempted and failing, and points
     * them at password recovery. Carries no token: at this point we do not know
     * whether the person failing to sign in is the owner at all.
     *
     * @param remainingAttempts attempts left before the account locks
     */
    void sendFailedSignInWarningEmail(String toEmail, String fullName, int attempts, int remainingAttempts);

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
