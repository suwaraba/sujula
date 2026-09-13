package com.sujula.service.catalogue;

import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.geo.GeoLookupService;
import com.sujula.service.geo.GeocodingGateway;
import com.sujula.service.reference.CurrencyCatalogue;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Works out, for one catalogue read, where the goods are going and what the
 * prices should say — from two sources that never touch.
 *
 * <p><strong>This class is where C1 is enforced.</strong> Everything public in
 * the catalogue goes through it, and the two halves are resolved by two methods
 * that share no inputs:
 *
 * <ul>
 *   <li>{@link #resolveDelivery} reads only what the shopper said about the
 *       destination — a delivery context they established, or explicit delivery
 *       parameters. It never touches the request's IP.</li>
 *   <li>{@link #resolveDisplayCurrency} reads only the browser and the IP. It
 *       never touches the delivery country.</li>
 * </ul>
 *
 * <p>The separation is structural rather than a matter of care: neither method
 * is given the other's inputs, so neither can quietly start depending on them.
 * A future edit that wanted to collapse them would have to change a signature,
 * which is the point — the mistake becomes visible in review instead of being a
 * line inside a method nobody re-reads.
 *
 * <p>Why it matters concretely. Derive currency from the delivery country and
 * the Madrid buyer is quoted in dalasi for a parcel going to Serrekunda — a
 * price they cannot pay with a European card. Derive ranking from the payer's
 * IP and a phone sitting in Banjul, two miles from the recipient, sorts below
 * one in Spain that can never reach her.
 */
@Slf4j
@Component
public class BrowsingContextResolver {

    private final DeliveryContextService deliveryContexts;
    private final GeoLookupService geoLookup;
    private final CurrencyCatalogue currencies;

    public BrowsingContextResolver(DeliveryContextService deliveryContexts,
                                   GeoLookupService geoLookup, CurrencyCatalogue currencies) {
        this.deliveryContexts = deliveryContexts;
        this.geoLookup = geoLookup;
        this.currencies = currencies;
    }

    /**
     * Both halves, resolved independently and then carried together.
     *
     * @param request   the HTTP request — used <em>only</em> for the payer half
     * @param userId    the signed-in caller, or null. Needed to read a delivery
     *                  context bound to an account; irrelevant to currency
     */
    public BrowsingContext resolve(CatalogueLocationParams params, HttpServletRequest request,
                                   Long userId) {

        Delivery delivery = resolveDelivery(params, userId);
        // Deliberately not given `delivery`. The payer's currency cannot be
        // derived from where the parcel is going, and this signature is what
        // guarantees it.
        String currency = resolveDisplayCurrency(params.currency(), request);

        return new BrowsingContext(
                delivery.latitude, delivery.longitude, delivery.countryCode, delivery.confidence,
                delivery.contextId, currency, resolveLanguage(params.language(), request));
    }

    /**
     * Where the goods are going.
     *
     * <p>In descending order of what the shopper actually told us: a delivery
     * context they established, then explicit coordinates, then a delivery
     * country alone. Nothing here reads the request.
     *
     * <p>An unknown destination is not an error. Most first page views have one,
     * and a catalogue that refuses to render until someone names a recipient is
     * a catalogue nobody browses.
     */
    Delivery resolveDelivery(CatalogueLocationParams params, Long userId) {
        if (params.deliverableTo() != null && !params.deliverableTo().isBlank()) {
            // A context the shopper established. Resolved through the service so
            // its ownership and expiry rules apply here exactly as they do at
            // checkout — a context belonging to someone else is not found.
            DeliveryContext context = deliveryContexts.require(params.deliverableTo().trim(), userId);
            return new Delivery(
                    context.getLatitude(), context.getLongitude(),
                    upper(context.getCountryCode()), context.getGeocodeConfidence(),
                    context.getId());
        }

        if (GeocodingGateway.isValidCoordinate(params.deliveryLatitude(), params.deliveryLongitude())) {
            return new Delivery(params.deliveryLatitude(), params.deliveryLongitude(),
                    upper(params.deliveryCountry()), GeocodeConfidence.EXACT, null);
        }

        return new Delivery(null, null, upper(params.deliveryCountry()), null, null);
    }

    /**
     * What the prices should say.
     *
     * <p>An explicit choice wins — a shopper who picked euro meant it. Otherwise
     * the payer context: the CDN's country header, or the caller's IP. Never the
     * delivery country, which is why this method is not given it.
     *
     * <p>Always answers. An unresolvable caller gets the home currency rather
     * than an error, because a storefront that renders no prices until a lookup
     * succeeds is worse than one that opens in dalasi and lets them change it.
     */
    String resolveDisplayCurrency(String requested, HttpServletRequest request) {
        if (requested != null && !requested.isBlank()) {
            // Validated rather than trusted: an unsupported code is refused here
            // rather than producing a page of unconverted prices further down.
            return currencies.require(requested);
        }
        GeoResponses.ResolvedContext payer = geoLookup.resolveContext(request);
        String inferred = payer.currency();
        return currencies.isSupported(inferred) ? currencies.require(inferred)
                                                : currencies.baseCurrency();
    }

    private String resolveLanguage(String requested, HttpServletRequest request) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return geoLookup.resolveContext(request).language();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** The delivery half, before it is joined to the payer half. */
    record Delivery(Double latitude, Double longitude, String countryCode,
                    GeocodeConfidence confidence, String contextId) {}
}
