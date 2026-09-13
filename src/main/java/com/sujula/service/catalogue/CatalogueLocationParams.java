package com.sujula.service.catalogue;

/**
 * The location-shaped query parameters every public catalogue read accepts.
 *
 * <p>One record rather than six repeated parameters on fourteen endpoints, and
 * named so that C1 is legible at the call site. There is no {@code userLat}
 * here and there never should be: the coordinates a catalogue ranks against are
 * the recipient's, and a parameter named for the person holding the phone is an
 * invitation to send the wrong ones.
 *
 * @param deliverableTo     a delivery context id. The best answer, because it is
 *                          the destination the cart and checkout will also price
 *                          against, so the catalogue cannot disagree with them
 * @param deliveryLatitude  an explicit destination, for a client that has a pin
 *                          but has not established a context yet
 * @param deliveryCountry   a destination country, for a client that has nothing
 *                          more precise
 * @param currency          an explicit display currency. Overrides what the
 *                          payer's IP suggests, because a shopper who chose euro
 *                          meant it
 */
public record CatalogueLocationParams(
        String deliverableTo,
        Double deliveryLatitude,
        Double deliveryLongitude,
        String deliveryCountry,
        String currency,
        String language) {

    public static CatalogueLocationParams of(String deliverableTo,
                                             Double deliveryLatitude, Double deliveryLongitude,
                                             String deliveryCountry, String currency,
                                             String language) {
        return new CatalogueLocationParams(deliverableTo, deliveryLatitude, deliveryLongitude,
                deliveryCountry, currency, language);
    }

    /** Nothing about where the goods go. */
    public static CatalogueLocationParams none() {
        return new CatalogueLocationParams(null, null, null, null, null, null);
    }
}
