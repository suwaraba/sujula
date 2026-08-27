package com.sujula.service.delivery;

import com.sujula.model.Address;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.Order;

/**
 * Where a parcel has to reach.
 *
 * <p>Coordinates are what pricing actually needs; the written address is kept
 * as the fallback the geocoder works from when a buyer checked out without
 * them, which is the common case for a typed-in address.
 *
 * @param latitude    null when the address was never geocoded
 * @param longitude   null when the address was never geocoded
 * @param addressLine street and apartment, as written
 */
public record DeliveryDestination(Double latitude,
                                  Double longitude,
                                  String addressLine,
                                  String city,
                                  String state,
                                  String postalCode,
                                  String countryCode) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /** A single line for the geocoder; null when there is nothing to geocode. */
    public String toSearchText() {
        StringBuilder text = new StringBuilder();
        append(text, addressLine);
        append(text, city);
        append(text, state);
        append(text, postalCode);
        append(text, countryCode);
        return text.isEmpty() ? null : text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (part != null && !part.isBlank()) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(part.trim());
        }
    }

    public static DeliveryDestination of(Address address) {
        return new DeliveryDestination(
                address.getLatitude(), address.getLongitude(),
                joinStreet(address.getStreet(), address.getApartmentSuite()),
                address.getCity(), address.getState(), address.getPostalCode(), address.getCountryCode());
    }

    public static DeliveryDestination of(PickupPoint point) {
        return new DeliveryDestination(
                point.getLatitude(), point.getLongitude(),
                joinStreet(point.getAddressStreet(), point.getAddressApartment()),
                point.getCity(), point.getState(), point.getPostalCode(), point.getCountryCode());
    }

    /** Built from the order's own shipping snapshot, so a placed order can be re-quoted. */
    public static DeliveryDestination of(Order order) {
        return new DeliveryDestination(
                null, null,
                joinStreet(order.getShippingStreet(), order.getShippingApartment()),
                order.getShippingCity(), order.getShippingState(),
                order.getShippingPostalCode(), order.getShippingCountry());
    }

    private static String joinStreet(String street, String apartment) {
        if (street == null || street.isBlank()) {
            return apartment;
        }
        return (apartment == null || apartment.isBlank()) ? street : street + " " + apartment;
    }
}
