package com.sujula.controller;

import com.sujula.service.invoice.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Serves an invoice PDF against a signed link.
 *
 * <p>Open by design, and the reason is the same one that shapes the tracking
 * page: a marketplace where the payer and the recipient are different people
 * has documents that legitimately travel between them. The buyer in Madrid
 * forwards the invoice to the person receiving the goods, or to a bank, or to
 * somebody reimbursing them — none of whom have an account here.
 *
 * <p>Ownership was proven once, when the link was minted on
 * {@code GET /orders/{id}/invoice}. From then on the signature is the
 * credential: it names one order, expires in minutes, and cannot be edited to
 * name another because the whole payload is covered by an HMAC. A link that
 * leaks is stale before it is useful.
 */
@RestController
@RequestMapping("/invoices")
@Tag(name = "orders", description = "A buyer's own orders: reads, tracking, cancellation and receipt")
public class InvoiceController {

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Download an invoice",
               description = "Takes the signed token issued by GET /orders/{id}/invoice. A "
                       + "tampered or expired token is rejected with the same message, so a probe "
                       + "cannot tell a stale link from a forged one.")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        InvoiceService.RenderedInvoice invoice = invoices.render(token);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(invoice.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                // Never in a shared cache: the document carries an address and a
                // phone number, and the URL is a bearer credential.
                .cacheControl(CacheControl.noStore())
                .body(invoice.content());
    }
}
