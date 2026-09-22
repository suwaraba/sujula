package com.sujula.service.fulfilment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Settings for the parcel label and the signed QR printed on it. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.parcel-label")
public class ParcelLabelProperties {

    /**
     * Key the QR payload is signed with.
     *
     * <p>Blank means one is generated at startup, which makes every label
     * printed before a restart unscannable. Fine on one dev machine, wrong
     * anywhere a parcel outlives a deployment — which is everywhere.
     */
    private String signingSecret = "";

    /**
     * How long a label's QR stays valid.
     *
     * <p>Measured in weeks rather than minutes, unlike the invoice link, because
     * the two are opposite problems. An invoice link leaks from a browser and
     * should be stale by the time it does; a label is printed, taped to a box,
     * and has to still scan when the parcel reaches a hub in another country a
     * fortnight later.
     */
    private Duration qrTtl = Duration.ofDays(30);

    /** Trading name printed at the head of the label. */
    private String issuerName = "Sujula";

    /** Where a scanner is sent. The token is appended as the last path segment. */
    private String scanPath = "/parcels";

    /** Absolute base for the scan URL, e.g. https://sujula.com. Blank yields a relative one. */
    private String baseUrl = "";
}
