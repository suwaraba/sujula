package com.sujula.model.constant;

/**
 * Controls where a product can be delivered.
 *
 * REGIONAL;    – vendor's REGION only (default for Gambia launch)
 * NATIONAL – anywhere inside the vendor's country (same as LOCAL for now,
 *             reserved for future domestic multi-zone logic)
 * GLOBAL   – available for international delivery; always surfaced regardless
 *             of the buyer's delivery country
 */
public enum DeliveryScope {
    REGIIONAL,
    RECOGER,
    NATIONAL,
    GLOBAL
}
