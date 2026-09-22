package com.sujula.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.dto.response.recipient.RecipientResponses;
import com.sujula.service.recipient.RecipientParcelService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one surface that must work for somebody who cannot sign in.
 *
 * <p>These assertions are about the security wiring rather than the logic: if a
 * route here ever starts demanding authentication, the sister in Serrekunda
 * stops being able to say where her own parcel should go, and the decision moves
 * to whoever has an account — which is the person on the other continent who
 * does not know whether she will be at home.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecipientParcelRoutingTest {

    private static final String CODE = "PARCQ4T8NHRW6JZY";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RecipientParcelService parcels;

    // ── Open, every one of them ──────────────────────────────────────────────

    @Test
    void readingTheParcelPageNeedsNoAccount() throws Exception {
        when(parcels.parcel(CODE)).thenReturn(page());

        mvc.perform(get("/parcels/" + CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forName").value("Fatou"))
                // Private, because a shared cache would serve one recipient's
                // parcel to the next person through the same proxy — and on
                // these networks that proxy exists.
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void askingForACodeNeedsNoAccountAndNoCsrfSession() throws Exception {
        when(parcels.requestCode(CODE)).thenReturn(new RecipientResponses.CodeSent(
                true, "o•••n@example.es", LocalDateTime.now().plusMinutes(15), 2, "sent"));

        mvc.perform(post("/parcels/" + CODE + "/request-code").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));

        verify(parcels).requestCode(CODE);
    }

    @Test
    void theThreeInstructionsAreOpenToo() throws Exception {
        when(parcels.choosePickupPoint(eq(CODE), any())).thenReturn(recorded());
        when(parcels.reschedule(eq(CODE), any())).thenReturn(recorded());
        when(parcels.authoriseSafeDrop(eq(CODE), any())).thenReturn(recorded());

        mvc.perform(post("/parcels/" + CODE + "/choose-pickup-point").with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\",\"pickupPointId\":1096}"))
                .andExpect(status().isOk());

        mvc.perform(post("/parcels/" + CODE + "/reschedule").with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\",\"from\":\"2026-09-20T09:00:00\","
                                + "\"until\":\"2026-09-20T13:00:00\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/parcels/" + CODE + "/authorise-safe-drop").with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"123456\",\"person\":\"Ndey\"}"))
                .andExpect(status().isOk());
    }

    // ── What the binder refuses before the service is troubled ───────────────

    @Test
    void aCodeOfTheWrongShapeNeverReachesTheService() throws Exception {
        mvc.perform(post("/parcels/" + CODE + "/reschedule").with(csrf())
                        .contentType("application/json")
                        .content("{\"code\":\"12ab\",\"from\":\"2026-09-20T09:00:00\","
                                + "\"until\":\"2026-09-20T13:00:00\"}"))
                .andExpect(status().isBadRequest());

        verify(parcels, never()).reschedule(any(), any());
    }

    @Test
    void anInstructionWithNoCodeIsRefused() throws Exception {
        mvc.perform(post("/parcels/" + CODE + "/choose-pickup-point").with(csrf())
                        .contentType("application/json")
                        .content("{\"pickupPointId\":1096}"))
                .andExpect(status().isBadRequest());

        verify(parcels, never()).choosePickupPoint(any(), any());
    }

    /**
     * The security property of the whole surface, asserted at the boundary.
     *
     * <p>A phone number or an email in the body is not ignored — there is no
     * field for one to bind to, so the request cannot express "send it here" at
     * all. That is stronger than validating it away: a future field added to
     * this request would have to be added deliberately, and this test is what
     * would notice.
     */
    @Test
    void thereIsNoWayToAskForSomebodyElsesCodeToBeSentToYou() throws Exception {
        when(parcels.requestCode(CODE)).thenReturn(new RecipientResponses.CodeSent(
                true, "o•••n@example.es", LocalDateTime.now().plusMinutes(15), 2, "sent"));

        mvc.perform(post("/parcels/" + CODE + "/request-code").with(csrf())
                        .contentType("application/json")
                        .content("{\"phone\":\"+2203109999\",\"email\":\"attacker@example.invalid\"}"))
                .andExpect(status().isOk());

        // The service is called with the tracking code and nothing else. It has
        // no second parameter for a destination, so there is nowhere for one to
        // arrive.
        verify(parcels).requestCode(CODE);
    }

    private static RecipientResponses.Parcel page() {
        return new RecipientResponses.Parcel(
                CODE, "OUT_FOR_DELIVERY", "Out for delivery today.", "Fatou",
                "Serrekunda", "GM", 1, null, null, LocalDateTime.now(), null,
                new RecipientResponses.Standing(null, null, null, null, false, null, null, null),
                new RecipientResponses.Actions(true, true, true, null),
                List.of());
    }

    private static RecipientResponses.InstructionRecorded recorded() {
        return new RecipientResponses.InstructionRecorded(
                CODE, "RESCHEDULE", LocalDateTime.now(),
                new RecipientResponses.Standing(null, null, null, null, false, null, null, null),
                "done");
    }
}
