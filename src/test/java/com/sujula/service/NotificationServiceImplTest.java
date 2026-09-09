package com.sujula.service;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Notification;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What lands in a user's inbox, and what one user may do to another's. */
class NotificationServiceImplTest {

    private NotificationRepository notificationRepository;
    private NotificationServiceImpl service;
    private User recipient;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        recipient = new User();
        recipient.setId(5L);

        when(userRepository.findById(5L)).thenReturn(Optional.of(recipient));
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        service = new NotificationServiceImpl(notificationRepository, userRepository);
    }

    @Test
    void filesANotificationAgainstItsRecipient() {
        Notification saved = service.send(5L, " Payment Received ", " We have your money. ",
                "order", "SJL-ABC123");

        assertEquals(recipient, saved.getUser());
        assertEquals("Payment Received", saved.getTitle());
        assertEquals("We have your money.", saved.getMessage());
        assertEquals("SJL-ABC123", saved.getReferenceId());
        assertFalse(saved.isRead());
    }

    @Test
    void normalisesTheTypeSoTheInboxCanGroupOnIt() {
        assertEquals("ORDER", service.send(5L, "Title", "Body", "order", null).getType());
        assertEquals("GENERAL", service.send(5L, "Title", "Body", "  ", null).getType());
        assertEquals("GENERAL", service.send(5L, "Title", "Body", null, null).getType());
    }

    @Test
    void refusesToFileWithoutARecipientOrATitle() {
        assertThrows(BadRequestException.class, () -> service.send(null, "Title", "Body", "ORDER", null));
        assertThrows(BadRequestException.class, () -> service.send(5L, " ", "Body", "ORDER", null));
        assertThrows(ResourceNotFoundException.class, () -> service.send(404L, "Title", "Body", "ORDER", null));
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
                .type("ORDER").referenceId("SJL-ABC123").read(read)
                .build();
    }
}
