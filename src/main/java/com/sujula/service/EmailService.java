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

    /**
     * Sends the recipient's collection code to the person who paid.
     *
     * <p>To the buyer, not the recipient, and deliberately. The person receiving
     * the parcel may have no account, no app and no email — she is the sister in
     * Serrekunda, and she did not sign up for anything (C5). The person who paid
     * has all three, and passing on a number is exactly what somebody sending
     * money home already does.
     *
     * @param recipientName who the parcel is for, so the buyer knows which one
     * @param code          the six digits the recipient reads to the driver
     */
    void sendRecipientReleaseCode(String toEmail, String buyerName, String recipientName,
                                  String orderNumber, String code,
                                  java.time.LocalDateTime expiresAt);

    /**
     * The code that lets the recipient change something about her own parcel.
     *
     * <p>Deliberately not the delivery code, and the message says so in as many
     * words. The delivery code is what she reads to the driver at the door; this
     * one is what she types on the tracking page to send the parcel to a counter
     * instead, or to ask for a different day. Somebody who confused the two would
     * either be unable to collect or would type the delivery code into a web page,
     * and a delivery code typed into a web page is one somebody can be talked into
     * giving away.
     *
     * <p>Goes to the person who paid, for the same reason the release code does:
     * she may have no account, no app and no email, and he has all three.
     *
     * @param recipientName her first name, so the buyer knows which parcel
     * @param trackingCode  what her link says, so he can tell her which one
     */
    void sendParcelAccessCode(String toEmail, String buyerName, String recipientName,
                              String trackingCode, String code,
                              java.time.LocalDateTime expiresAt);

    /**
     * The email half of a notification the user has asked to be emailed about.
     *
     * <p>Generic on purpose. Every other method here is a specific message with
     * its own wording, because each one has to say something particular; this is
     * the one that carries whatever the notification already said, so the inbox
     * and the email cannot drift into telling somebody two different things.
     *
     * @param reference the order number or parcel reference it is about, or null
     */
    void sendNotificationEmail(String toEmail, String firstName, String title, String body,
                               String reference);

    /**
     * Tells somebody an account has been opened for them.
     *
     * <p>Deliberately not {@link #sendAdminPasswordResetEmail}, which prints its
     * argument as a temporary password. There is no password to print here: the
     * account is created with a random secret nobody has, and this sends the
     * person to set their own — because a password an administrator chose is one
     * an administrator knows, and an account whose creator knows the password is
     * one they can act as without leaving an impersonation row.
     *
     * @param createdBy who opened it, so an unexpected message can be questioned
     */
    void sendAccountSetupEmail(String toEmail, String firstName, String role, String createdBy);
}
