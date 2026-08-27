package com.sujula.service;

import com.sujula.dto.request.delivery.DeliveryQuoteRequest;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.order.OrderItem;
import com.sujula.service.delivery.DeliveryDestination;
import com.sujula.service.delivery.DeliveryItem;
import com.sujula.service.delivery.DeliveryQuote;

import java.util.List;

/**
 * Prices delivery per product, from distance and weight.
 *
 * <p>On a multivendor order there is no single shipping cost to compute: each
 * product leaves from its own vendor's location, weighs what it weighs, and
 * ships under its own delivery scope. Every product therefore gets its own leg —
 * {@code base + per-km beyond the included distance + per-kg beyond the included
 * weight}, scaled for scope and for how the buyer is receiving the goods — and
 * the order's shipping cost is the sum of those legs, with each product's share
 * kept on its own order line.
 *
 * <p>Legs are priced against the rate card's own currency and converted once
 * into the currency the buyer is shopping in, so the same parcel over the same
 * distance costs the same whichever vendor is sending it.
 */
public interface DeliveryPricingService {

    /**
     * Prices a basket that has not been ordered yet — a cart or checkout preview.
     *
     * @param destination     where it is going; coordinates are used when present
     *                        and the written address is geocoded when they are not
     * @param mode            how the buyer receives the goods
     * @param displayCurrency currency to quote in; null falls back to the rate card's
     * @return legs in the same order as {@code items}
     */
    DeliveryQuote quote(List<DeliveryItem> items,
                        DeliveryDestination destination,
                        DeliveryMode mode,
                        String displayCurrency);

    /**
     * Prices a basket straight from a client request: resolves the products, the
     * pickup point when one is named, and the destination, then quotes.
     *
     * @return legs in the same order as the request's items
     */
    DeliveryQuote quote(DeliveryQuoteRequest request);

    /**
     * Prices the lines of an order being placed, using each line's own frozen
     * value for the free-delivery threshold.
     *
     * @return legs in the same order as {@code items}
     */
    DeliveryQuote quoteOrderItems(List<OrderItem> items,
                                  DeliveryDestination destination,
                                  DeliveryMode mode,
                                  String displayCurrency);
}
