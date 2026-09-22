package com.sujula.dto.response.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.GeocodeConfidence;

import java.time.LocalDateTime;

/**
 * A resolved destination, and the handle everything else refers to it by.
 *
 * @param id        the handle — and, for a guest, the whole of their claim to
 *                  it. Treat it as a secret: it is long and random for that
 *                  reason, and it expires
 * @param expiresAt when the handle stops working
 * @param guest     true when nobody is signed in, so possession of the id is
 *                  what identifies the holder
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliveryContextResponse(
        String id,
        boolean guest,
        Double latitude,
        Double longitude,
        String addressLine,
        String city,
        String state,
        String postalCode,
        String countryCode,
        GeocodeConfidence confidence,
        boolean needsPinConfirmation,
        boolean deliverable,
        DeliveryMode mode,
        Long pickupPointId,
        Long addressId,
        String currency,
        String language,
        String timezone,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {}
