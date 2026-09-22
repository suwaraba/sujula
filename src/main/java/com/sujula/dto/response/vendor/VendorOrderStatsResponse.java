package com.sujula.dto.response.vendor;

import com.sujula.model.constant.VendorOrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The seller's dashboard figures, in the seller's own currency.
 *
 * <p>Earnings are payouts — after commission — because that is the number a
 * vendor can act on. The buyer-facing totals these were derived from are not
 * here, and neither is any other currency.
 */
@Data
@Builder
public class VendorOrderStatsResponse {

    private String currency;

    /** Every status, including the ones with no orders, so a dashboard renders consistently. */
    private Map<VendorOrderStatus, Long> ordersByStatus;

    /** Orders waiting on the vendor to do something: pending, confirmed or processing. */
    private long awaitingAction;

    /** Payout across delivered orders — money earned and no longer at risk of cancellation. */
    private BigDecimal earnedToDate;

    /** Payout across orders placed but not yet delivered. */
    private BigDecimal inFlight;
}
