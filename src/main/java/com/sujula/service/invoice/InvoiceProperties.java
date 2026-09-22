package com.sujula.service.invoice;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings for the invoice document and the signed link that serves it.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.invoice")
public class InvoiceProperties {

    /**
     * Key the download link is signed with. Blank means one is generated at
     * startup: links then stop working when the application restarts, and stop
     * working across instances of it, which is fine for a single dev machine
     * and wrong for anything else. Set it in the environment for a deployment.
     */
    private String signingSecret = "";

    /**
     * How long a link stays valid. An invoice carries a home address and a
     * phone number, so the link is short-lived by design — the buyer opens it
     * from the order page, and a copy that leaks is already stale.
     */
    private Duration linkTtl = Duration.ofMinutes(15);

    /** Path the signed link points at. Must match the controller mapping. */
    private String downloadPath = "/invoices";

    /** Absolute base for the link, e.g. https://sujula.com. Blank yields a relative URL. */
    private String baseUrl = "";

    /** Trading name printed at the head of the document. */
    private String issuerName = "Sujula";

    /** Free-text issuer address block, newline-separated. */
    private String issuerAddress = "";

    /** Tax identifier printed under the issuer, when the business has one. */
    private String issuerTaxId = "";

    /** Footer line, e.g. support contact. */
    private String footerNote = "";
}
