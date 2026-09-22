package com.sujula.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.notification.NotificationResponses;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.service.notification.NotificationInboxService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inbox surface, and the thing it must never publish.
 *
 * <p>Two claims. Nothing here is open, and no path carries a user id — every
 * endpoint takes its subject from the session, so there is no parameter to
 * change to reach somebody else's inbox. And a push token never leaves the
 * server: anybody holding one can send that handset a message dressed as ours.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationRoutingTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private NotificationInboxService inbox;

    @Test
    void theInboxNeedsAnAccount() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications/read-all").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications/devices").with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(inbox, never()).inbox(any(), anyBoolean(), any());
    }

    @Test
    void everyEndpointTakesItsSubjectFromTheSession() throws Exception {
        Authentication caller = auth(900L);
        when(inbox.inbox(eq(900L), eq(false), any())).thenReturn(emptyInbox());
        when(inbox.preferences(900L)).thenReturn(emptyPreferences(null));
        when(inbox.markAllRead(900L)).thenReturn(
                new NotificationResponses.AllRead(3, "3 notifications marked as read."));
        when(inbox.markRead(900L, 7L)).thenReturn(item());

        mvc.perform(get("/notifications").with(authentication(caller)))
                .andExpect(status().isOk())
                // Private: a shared cache holding somebody's inbox would serve
                // it to the next person through the same proxy.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
        mvc.perform(get("/notifications/preferences").with(authentication(caller)))
                .andExpect(status().isOk());
        mvc.perform(post("/notifications/read-all").with(authentication(caller)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(3));
        mvc.perform(post("/notifications/7/read").with(authentication(caller)).with(csrf()))
                .andExpect(status().isOk());

        // There is no user id in any of these paths, so there is nothing to
        // tamper with — a stronger guarantee than a check, which can be missing
        // from the one endpoint that matters.
        verify(inbox).inbox(eq(900L), eq(false), any());
        verify(inbox).preferences(900L);
        verify(inbox).markAllRead(900L);
        verify(inbox).markRead(900L, 7L);
    }

    @Test
    void registeringADeviceAnswersCreatedAndNeverEchoesTheToken() throws Exception {
        when(inbox.registerDevice(eq(900L), any())).thenReturn(
                new NotificationResponses.Device(4L, "ANDROID", "Aminata's Infinix",
                        LocalDateTime.now(), LocalDateTime.now(), false, "Registered."));

        mvc.perform(post("/notifications/devices")
                        .with(authentication(auth(900L))).with(csrf())
                        .contentType("application/json")
                        .content("{\"token\":\"tok-secret-value\",\"platform\":\"ANDROID\","
                                + "\"label\":\"Aminata's Infinix\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Aminata's Infinix"))
                // The one thing this surface must not publish.
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void aDeviceOfAnUnknownKindIsRefusedBeforeTheServiceSeesIt() throws Exception {
        mvc.perform(post("/notifications/devices")
                        .with(authentication(auth(900L))).with(csrf())
                        .contentType("application/json")
                        .content("{\"token\":\"tok\",\"platform\":\"PIGEON\"}"))
                .andExpect(status().isBadRequest());

        verify(inbox, never()).registerDevice(any(), any());
    }

    @Test
    void aDeviceWithNoTokenIsRefused() throws Exception {
        mvc.perform(post("/notifications/devices")
                        .with(authentication(auth(900L))).with(csrf())
                        .contentType("application/json")
                        .content("{\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest());

        verify(inbox, never()).registerDevice(any(), any());
    }

    @Test
    void removingADeviceIsScopedToTheCaller() throws Exception {
        when(inbox.removeDevice(900L, 4L)).thenReturn(
                new NotificationResponses.DeviceRemoved(4L, "That device will not be sent "
                        + "notifications any more."));

        mvc.perform(delete("/notifications/devices/4")
                        .with(authentication(auth(900L))).with(csrf()))
                .andExpect(status().isOk());

        verify(inbox).removeDevice(900L, 4L);
    }

    @Test
    void anEmptySetOfChangesIsRefused() throws Exception {
        mvc.perform(put("/notifications/preferences")
                        .with(authentication(auth(900L))).with(csrf())
                        .contentType("application/json").content("{\"changes\":[]}"))
                .andExpect(status().isBadRequest());

        verify(inbox, never()).updatePreferences(any(), any());
    }

    @Test
    void aChangeMissingItsChannelNeverReachesTheService() throws Exception {
        mvc.perform(put("/notifications/preferences")
                        .with(authentication(auth(900L))).with(csrf())
                        .contentType("application/json")
                        .content("{\"changes\":[{\"event\":\"PROMOTION\",\"enabled\":true}]}"))
                .andExpect(status().isBadRequest());

        verify(inbox, never()).updatePreferences(any(), any());
    }

    @Test
    void thePreferencesPageCarriesItsChannelsSoAClientDoesNotHardCodeThem() throws Exception {
        when(inbox.preferences(900L)).thenReturn(emptyPreferences(
                "Push notifications are not switched on for this installation yet."));

        mvc.perform(get("/notifications/preferences").with(authentication(auth(900L))))
                .andExpect(status().isOk())
                // The day an SMS sender exists, the page grows a column without
                // being rebuilt.
                .andExpect(jsonPath("$.channels[0]").value("IN_APP"))
                .andExpect(jsonPath("$.note").exists());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private static PagedResponse<NotificationResponses.Item> emptyInbox() {
        return PagedResponse.<NotificationResponses.Item>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true)
                .build();
    }

    private static NotificationResponses.Preferences emptyPreferences(String note) {
        return new NotificationResponses.Preferences(
                List.of(NotificationChannel.values()), List.of(), 0, note);
    }

    private static NotificationResponses.Item item() {
        return new NotificationResponses.Item(7L, NotificationEvent.ORDER_UPDATE, "Orders",
                "Order shipped", "On its way.", "SJL-1", true, List.of("IN_APP"),
                LocalDateTime.now());
    }

    private static Authentication auth(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("user" + userId + "@sujula.gm");
        user.setRole(UserRole.CUSTOMER);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
