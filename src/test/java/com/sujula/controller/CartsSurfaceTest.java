package com.sujula.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opening a basket, and coming back to it.
 *
 * <p>This is the round trip, and it was broken. POST /carts answered with a
 * numeric {@code cartId} and no token, while every later request on the surface
 * is addressed by token — so a guest who opened a basket could not add a line
 * to it, price it, or pay for it. The endpoint's own description said "returns
 * the cart with its token".
 *
 * <p>Nothing caught it because nothing tested this surface at all: there was no
 * test anywhere that opened a cart through the API and then used the answer. The
 * seeded carts all have tokens written by hand in the seed file, so every other
 * check on the surface started from a token it already knew.
 *
 * <p>Which is the argument for this test being a round trip rather than a field
 * assertion. Asserting that the DTO has a token would pass the day somebody
 * stops populating it; using the answer to make the next request cannot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartsSurfaceTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void theTokenAnOpenedCartComesBackWithIsTheOneThatReadsItAgain() throws Exception {
        MvcResult opened = mvc.perform(post("/carts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"GMD\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode cart = json.readTree(opened.getResponse().getContentAsString());
        String token = cart.path("token").asString(null);

        assertNotNull(token, "POST /carts returned no token, so this cart cannot be addressed again");
        assertTrue(token.length() >= 32,
                "a cart token is the whole credential and must not be guessable; got "
                        + token.length() + " characters");
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "expected base64url, got " + token);

        // The assertion that matters: the value handed back is usable.
        MvcResult read = mvc.perform(get("/carts/" + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode again = json.readTree(read.getResponse().getContentAsString());
        assertEquals(cart.path("cartId").asLong(), again.path("cartId").asLong(),
                "the token resolved to a different cart");
        assertEquals(token, again.path("token").asString(null),
                "reading a cart back should report the same token");
    }

    @Test
    void twoCartsOpenedInARowDoNotShareAToken() throws Exception {
        String first = tokenOfANewCart();
        String second = tokenOfANewCart();

        assertTrue(!first.equals(second), "two baskets came back with the same credential");
    }

    @Test
    void anIdThatIsNotATokenDoesNotResolveACart() throws Exception {
        // The shape of the bug this test file exists for: a client that used the
        // numeric id, because that was the only identifier in the response.
        MvcResult opened = mvc.perform(post("/carts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(opened.getResponse().getContentAsString()).path("cartId").asLong();

        mvc.perform(get("/carts/" + id)).andExpect(status().isNotFound());
    }

    private String tokenOfANewCart() throws Exception {
        MvcResult opened = mvc.perform(post("/carts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(opened.getResponse().getContentAsString()).path("token").asString(null);
    }
}
