package com.sujula.service.notification;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.dto.request.notification.NotificationRequests;
import com.sujula.dto.response.notification.NotificationResponses;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;
import com.sujula.model.notification.PushDevice;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.notification.NotificationPreferenceRepository;
import com.sujula.repository.notification.PushDeviceRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.impl.NotificationServiceImpl;
import com.sujula.service.notification.impl.LoggedPushSender;
import com.sujula.service.notification.impl.NotificationInboxServiceImpl;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What somebody is told, and what they may switch off.
 *
 * <p>The sharpest claims are that absence of a row means "no opinion" rather
 * than "off", that the five mandatory events cannot be suppressed by any route,
 * and that a token re-registered by a second account is revoked on the first —
 * the case where a sold phone would otherwise keep receiving somebody's parcel
 * codes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({NotificationPreferences.class, NotificationInboxServiceImpl.class,
         NotificationServiceImpl.class, LoggedPushSender.class})
class NotificationPreferencesTest {

    @Autowired private NotificationPreferences preferences;
    @Autowired private NotificationInboxServiceImpl inbox;
    @Autowired private NotificationServiceImpl notifications;
    @Autowired private NotificationPreferenceRepository stored;
    @Autowired private NotificationRepository rows;
    @Autowired private PushDeviceRepository devices;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private EmailService email;

    private User aminata;
    private User ebrima;

    @BeforeEach
    void setUp() {
        aminata = user("aminata@example.gm", "Aminata");
        ebrima = user("ebrima@example.gm", "Ebrima");
        entityManager.flush();
    }

    private User user(String address, String firstName) {
        User person = new User();
        person.setEmail(address);
        person.setPassword("x");
        person.setFirstName(firstName);
        person.setLastName("Person");
        person.setRole(UserRole.CUSTOMER);
        return users.save(person);
    }

    // ── Three states, not two ────────────────────────────────────────────────

    @Test
    void aUserWhoHasNeverOpenedTheSettingsPageHasNoRowsAndStillGetsTold() {
        assertTrue(stored.findByUserId(aminata.getId()).isEmpty());

        // Writing the whole matrix on registration would be a hundred rows each
        // and would freeze today's defaults into every account ever created.
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.IN_APP));
    }

    @Test
    void offersAreTheOneThingThatStartsOff() {
        // An inbox full of offers is one nobody reads, and the things in it that
        // matter are the ones that get missed.
        assertFalse(preferences.isEnabled(aminata.getId(), NotificationEvent.PROMOTION,
                NotificationChannel.IN_APP));
        assertFalse(preferences.isEnabled(aminata.getId(), NotificationEvent.PROMOTION,
                NotificationChannel.EMAIL));
    }

    @Test
    void settingSomethingBackToItsDefaultRemovesTheRowRatherThanPinningIt() {
        preferences.set(aminata, NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.EMAIL, false);
        entityManager.flush();
        assertEquals(1, stored.findByUserId(aminata.getId()).size());

        preferences.set(aminata, NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.EMAIL, true);
        entityManager.flush();

        // Back to having no opinion, rather than an opinion that happens to
        // match today's default and would survive the default being improved.
        assertTrue(stored.findByUserId(aminata.getId()).isEmpty());
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.EMAIL));
    }

    @Test
    void switchingSomethingOffIsRemembered() {
        preferences.set(aminata, NotificationEvent.MESSAGE_RECEIVED,
                NotificationChannel.EMAIL, false);
        entityManager.flush();

        assertFalse(preferences.isEnabled(aminata.getId(), NotificationEvent.MESSAGE_RECEIVED,
                NotificationChannel.EMAIL));
        // And only that one. Turning off email about messages must not turn off
        // the inbox copy of the same thing.
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.MESSAGE_RECEIVED,
                NotificationChannel.IN_APP));
        // Nor anybody else's.
        assertTrue(preferences.isEnabled(ebrima.getId(), NotificationEvent.MESSAGE_RECEIVED,
                NotificationChannel.EMAIL));
    }

    // ── The five that cannot be switched off ─────────────────────────────────

    @Test
    void aMandatoryEventCannotBeSwitchedOffByAnyRoute() {
        for (NotificationEvent event : NotificationEvent.values()) {
            if (!event.isMandatory()) {
                continue;
            }
            for (NotificationChannel channel : NotificationChannel.values()) {
                preferences.set(aminata, event, channel, false);
            }
        }
        entityManager.flush();

        // Not stored, and not honoured if it somehow were.
        assertTrue(stored.findByUserId(aminata.getId()).isEmpty());
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.PARCEL_CODE,
                NotificationChannel.IN_APP));
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.SECURITY_ALERT,
                NotificationChannel.EMAIL));
        assertTrue(preferences.isEnabled(aminata.getId(), NotificationEvent.PAYOUT_FAILED,
                NotificationChannel.EMAIL));
    }

    @Test
    void everyMandatoryEventIsOneThatCostsSomebodySomethingIfItIsMissed() {
        // The list is deliberately short. Anything else being here would be the
        // platform deciding what somebody has to read.
        List<NotificationEvent> mandatory = java.util.Arrays.stream(NotificationEvent.values())
                .filter(NotificationEvent::isMandatory).toList();

        assertEquals(5, mandatory.size(), mandatory.toString());
        assertTrue(mandatory.contains(NotificationEvent.PARCEL_CODE));
        assertTrue(mandatory.contains(NotificationEvent.DISPUTE_UPDATE));
        assertTrue(mandatory.contains(NotificationEvent.PAYOUT_FAILED));
        assertTrue(mandatory.contains(NotificationEvent.SECURITY_ALERT));
        assertTrue(mandatory.contains(NotificationEvent.REFUND_ISSUED));
        assertFalse(mandatory.contains(NotificationEvent.PROMOTION));
    }

    @Test
    void theSettingsPageMarksTheLockedSwitchesAndSaysWhy() {
        NotificationResponses.Preferences page = inbox.preferences(aminata.getId());

        NotificationResponses.Setting locked = settingFor(page, NotificationEvent.PARCEL_CODE,
                NotificationChannel.EMAIL);
        assertTrue(locked.locked());
        assertTrue(locked.enabled());
        // A switch that silently does nothing is worse than one that will not
        // move and says why.
        assertNotNull(locked.lockedReason());
        assertTrue(locked.lockedReason().contains("nobody can collect it"));

        NotificationResponses.Setting free = settingFor(page, NotificationEvent.PROMOTION,
                NotificationChannel.EMAIL);
        assertFalse(free.locked());
        assertNull(free.lockedReason());
    }

    @Test
    void theWholeMatrixIsReturnedRatherThanTheStoredOverrides() {
        NotificationResponses.Preferences page = inbox.preferences(aminata.getId());

        int rendered = page.groups().stream().mapToInt(g -> g.events().size()).sum();
        assertEquals(NotificationEvent.values().length, rendered,
                "a settings screen showing nothing is one that looks broken");
        assertEquals(List.of(NotificationChannel.values()), page.channels());
    }

    @Test
    void updatingSaysHowManySwitchesActuallyMoved() {
        NotificationResponses.Preferences after = inbox.updatePreferences(aminata.getId(),
                new NotificationRequests.UpdatePreferences(List.of(
                        // One real change.
                        new NotificationRequests.Switch(NotificationEvent.PROMOTION,
                                NotificationChannel.EMAIL, true),
                        // One that is already the default.
                        new NotificationRequests.Switch(NotificationEvent.PARCEL_DELIVERED,
                                NotificationChannel.IN_APP, true),
                        // And one that cannot move. Ignored rather than failing
                        // the call: a client sending back a screenful should not
                        // fail wholesale because one row in it is locked.
                        new NotificationRequests.Switch(NotificationEvent.SECURITY_ALERT,
                                NotificationChannel.EMAIL, false))));

        assertEquals(1, after.changed());
        assertTrue(settingFor(after, NotificationEvent.PROMOTION,
                NotificationChannel.EMAIL).enabled());
        assertTrue(settingFor(after, NotificationEvent.SECURITY_ALERT,
                NotificationChannel.EMAIL).enabled());
    }

    @Test
    void thePageSaysWhenPushWouldReachNobody() {
        // No provider is configured in this installation, and saying so is the
        // point: a switch that is on and reaches nothing is worse than one that
        // is off, because the user believes they are covered.
        assertNotNull(inbox.preferences(aminata.getId()).note());
        assertTrue(inbox.preferences(aminata.getId()).note().contains("not switched on"));
    }

    // ── Everything off ───────────────────────────────────────────────────────
    //
    // Asserted against the resolver rather than by sending, because
    // NotificationService.send runs in its own transaction on purpose — a
    // checkout that cannot write a notification is still a valid checkout — and
    // a transaction that commits separately cannot see rows this test has not
    // committed. What gets written is covered in NotificationServiceImplTest,
    // against the same resolver.

    @Test
    void aUserWhoTurnsOffEverythingTheyCanStillGetsTheFiveThatMatter() {
        for (NotificationEvent event : NotificationEvent.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                preferences.set(aminata, event, channel, false);
            }
        }
        entityManager.flush();

        for (NotificationEvent event : NotificationEvent.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                boolean expected = event.isMandatory();
                assertEquals(expected,
                        preferences.isEnabled(aminata.getId(), event, channel),
                        event + " on " + channel);
            }
        }
    }

    @Test
    void andEverythingElseReallyIsOff() {
        preferences.set(aminata, NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.IN_APP, false);
        entityManager.flush();

        // The inbox can be switched off for an ordinary event. It is only
        // non-negotiable for the five where silence costs something.
        assertFalse(preferences.isEnabled(aminata.getId(), NotificationEvent.PARCEL_DELIVERED,
                NotificationChannel.IN_APP));
    }

    // ── Devices ──────────────────────────────────────────────────────────────

    @Test
    void registeringTheSameTokenTwiceIsARefreshRatherThanASecondPhone() {
        NotificationResponses.Device first = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-abc", "android", "Infinix"));
        NotificationResponses.Device again = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-abc", "ANDROID", "Aminata's Infinix"));
        entityManager.flush();

        // The app re-registers on every start. Without this a user ends up with
        // forty of one phone.
        assertEquals(first.id(), again.id());
        assertEquals("Aminata's Infinix", again.label());
        assertEquals(1, devices.findLiveForUser(aminata.getId()).size());
    }

    @Test
    void aTokenReRegisteredByAnotherAccountIsRevokedOnTheFirst() {
        Long aminatasDevice = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-shared", "ANDROID", "Infinix")).id();
        entityManager.flush();

        inbox.registerDevice(ebrima.getId(),
                new NotificationRequests.RegisterDevice("tok-shared", "ANDROID", "My phone"));
        entityManager.flush();

        // Operating systems reassign tokens. Leaving the old registration live
        // would send Aminata's parcel codes to whoever has the phone now.
        PushDevice hers = devices.findById(aminatasDevice).orElseThrow();
        assertNotNull(hers.getRevokedAt());
        assertTrue(devices.findLiveForUser(aminata.getId()).isEmpty());
        assertEquals(1, devices.findLiveForUser(ebrima.getId()).size());
    }

    @Test
    void theTokenIsNeverReturned() {
        NotificationResponses.Device device = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-secret-value", "IOS", "iPhone"));

        // Anybody holding a token can send that handset a message dressed as
        // ours, so no endpoint hands one back.
        assertFalse(device.toString().contains("tok-secret-value"));
        assertFalse(device.deliverable(), "no provider is configured here");
        assertNotNull(device.deliveryNote());
    }

    @Test
    void removingAPhoneIsSoftAndSaysItDoesNotSignItOut() {
        Long deviceId = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-lost", "ANDROID", "Old phone")).id();

        NotificationResponses.DeviceRemoved removed =
                inbox.removeDevice(aminata.getId(), deviceId);
        entityManager.flush();

        assertTrue(devices.findLiveForUser(aminata.getId()).isEmpty());
        // The row survives: "this phone was removed on the 4th" is what a stolen
        // handset makes worth keeping.
        assertNotNull(devices.findById(deviceId).orElseThrow().getRevokedAt());
        assertTrue(removed.message().contains("does not sign it out"));
    }

    @Test
    void somebodyElsesPhoneIsNotFound() {
        Long hers = inbox.registerDevice(aminata.getId(),
                new NotificationRequests.RegisterDevice("tok-hers", "ANDROID", "Infinix")).id();

        assertThrows(com.sujula.exceptions.ResourceNotFoundException.class,
                () -> inbox.removeDevice(ebrima.getId(), hers));
    }

    private static NotificationResponses.Setting settingFor(
            NotificationResponses.Preferences page, NotificationEvent event,
            NotificationChannel channel) {
        return page.groups().stream()
                .flatMap(group -> group.events().stream())
                .filter(row -> row.event() == event)
                .flatMap(row -> row.settings().stream())
                .filter(setting -> setting.channel() == channel)
                .findFirst().orElseThrow();
    }
}
