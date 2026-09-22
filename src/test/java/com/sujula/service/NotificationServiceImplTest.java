package com.sujula.service;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Notification;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.notification.PushDeviceRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.impl.NotificationServiceImpl;
import com.sujula.service.notification.NotificationPreferences;
import com.sujula.service.notification.PushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What lands in a user's inbox, and what one user may do to another's. */
class NotificationServiceImplTest {

    private NotificationRepository notificationRepository;
    private NotificationPreferences preferences;
    private PushDeviceRepository devices;
    private PushSender push;
    private EmailService email;
    private NotificationServiceImpl service;
    private User recipient;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        preferences = mock(NotificationPreferences.class);
        devices = mock(PushDeviceRepository.class);
        push = mock(PushSender.class);
        email = mock(EmailService.class);

        recipient = new User();
        recipient.setId(5L);
        recipient.setEmail("aminata@example.gm");
        recipient.setFirstName("Aminata");

        when(userRepository.findById(5L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        // The inbox on, everything else off, unless a test says otherwise.
        when(preferences.isEnabled(any(), any(), eq(NotificationChannel.IN_APP))).thenReturn(true);
        when(devices.findLiveForUser(any())).thenReturn(java.util.List.of());

        service = new NotificationServiceImpl(notificationRepository, userRepository,
                preferences, devices, push, email);
    }

    @Test
    void filesANotificationAgainstItsRecipient() {
        Notification saved = service.send(5L, " Payment Received ", " We have your money. ",
                NotificationEvent.ORDER_UPDATE, "SJL-ABC123");

        assertEquals(recipient, saved.getUser());
        assertEquals("Payment Received", saved.getTitle());
        assertEquals("We have your money.", saved.getMessage());
        assertEquals("SJL-ABC123", saved.getReferenceId());
        assertFalse(saved.isRead());
    }

    @Test
    void anEventWithNoValueFallsIntoTheBucketRatherThanBeingNull() {
        // The type used to be free text, which meant "ORDER" and "ORDER_UPDATE"
        // were different things nobody could hold a preference about. It is a
        // value now, and the only defaulting left is the absent case.
        assertEquals(NotificationEvent.ORDER_UPDATE,
                service.send(5L, "Title", "Body", NotificationEvent.ORDER_UPDATE, null).getEvent());
        assertEquals(NotificationEvent.GENERAL,
                service.send(5L, "Title", "Body", null, null).getEvent());
    }

    @Test
    void aSwitchedOffEventWritesNothingAtAll() {
        when(preferences.isEnabled(5L, NotificationEvent.PROMOTION, NotificationChannel.IN_APP))
                .thenReturn(false);

        // Not an empty row, not a row marked suppressed — nothing. The user said
        // they did not want to hear about offers.
        assertNull(service.send(5L, "Half price", "Everything must go",
                NotificationEvent.PROMOTION, null));
        verify(notificationRepository, never()).save(any());
        verify(email, never()).sendNotificationEmail(any(), any(), any(), any(), any());
    }

    @Test
    void theChannelsItActuallyWentOutOnAreOnTheRow() {
        when(preferences.isEnabled(5L, NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.EMAIL)).thenReturn(true);

        Notification saved = service.send(5L, "Delivered", "Your parcel has arrived.",
                NotificationEvent.PARCEL_DELIVERED, "SHP-1");

        verify(email).sendNotificationEmail(eq("aminata@example.gm"), eq("Aminata"),
                eq("Delivered"), eq("Your parcel has arrived."), eq("SHP-1"));
        // "Did he get the email" is the first thing support asks, and answering
        // it from today's preferences would be answering a different question.
        assertEquals("IN_APP,EMAIL", saved.getSentOn());
    }

    @Test
    void anEmailThatFailsDoesNotLoseTheNotification() {
        when(preferences.isEnabled(5L, NotificationEvent.ORDER_PLACED, NotificationChannel.EMAIL))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(email).sendNotificationEmail(any(), any(), any(), any(), any());

        Notification saved = service.send(5L, "Order placed", "Thank you.",
                NotificationEvent.ORDER_PLACED, "SJL-1");

        // The inbox row is the record. A send that failed is a support problem.
        assertEquals("IN_APP", saved.getSentOn());
    }

    @Test
    void pushWithNoProviderLeavesTheChannelOffTheRowRatherThanClaimingIt() {
        when(preferences.isEnabled(5L, NotificationEvent.PARCEL_OUT_FOR_DELIVERY,
                NotificationChannel.PUSH)).thenReturn(true);
        when(push.send(any(), any(), any(), any())).thenReturn(0);

        Notification saved = service.send(5L, "Out for delivery", "Today.",
                NotificationEvent.PARCEL_OUT_FOR_DELIVERY, "SHP-1");

        // Reached nobody, so it does not say it did. An operator reading sentOn
        // is reading what happened rather than what was attempted.
        assertEquals("IN_APP", saved.getSentOn());
    }

    @Test
    void refusesToFileWithoutARecipientOrATitle() {
        assertThrows(BadRequestException.class,
                () -> service.send(null, "Title", "Body", NotificationEvent.ORDER_UPDATE, null));
        assertThrows(BadRequestException.class,
                () -> service.send(5L, " ", "Body", NotificationEvent.ORDER_UPDATE, null));
        assertThrows(ResourceNotFoundException.class,
                () -> service.send(404L, "Title", "Body", NotificationEvent.ORDER_UPDATE, null));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void marksOneRead() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification(recipient, false)));

        assertTrue(service.markRead(1L, 5L).isRead());
    }

    @Test
    void markingAnAlreadyReadOneIsAQuietNoOp() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification(recipient, true)));

        assertTrue(service.markRead(1L, 5L).isRead());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void anotherUsersNotificationIsNotEvenAcknowledgedToExist() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification(recipient, false)));

        // Not found rather than forbidden: a stranger learns nothing about what exists.
        assertThrows(ResourceNotFoundException.class, () -> service.markRead(1L, 99L));
        verify(notificationRepository, never()).save(any());
    }

    private static Notification notification(User user, boolean read) {
        return Notification.builder()
                .id(1L).user(user).title("Order Shipped").message("On its way.")
                .event(NotificationEvent.ORDER_UPDATE).referenceId("SJL-ABC123").read(read)
                .build();
    }
}
