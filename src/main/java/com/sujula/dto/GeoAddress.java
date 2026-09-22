package com.sujula.dto;

import com.sujula.model.constant.GeocodeConfidence;

/**
 * What a geocoder resolved, and how much of it to believe.
 */
public class GeoAddress {

    String address;
    private final double latitude;
    private final double longitude;
    private final String country;      // e.g. "Spain"
    private final String countryCode;  // ISO 3166-1 alpha-2, e.g. "ES"
    private final String city;

    /**
     * How precise the pin is.
     *
     * <p>Carried alongside the coordinates rather than dropped, because
     * coordinates alone cannot be judged: the centre of a town and the door of a
     * house look identical once they are two numbers, and delivery is priced from
     * the distance between them.
     */
    private final GeocodeConfidence confidence;

    public GeoAddress(String address, double latitude, double longitude,
                      String country, String countryCode, String city) {
        this(address, latitude, longitude, country, countryCode, city, GeocodeConfidence.APPROXIMATE);
    }

    public GeoAddress(String address, double latitude, double longitude,
                      String country, String countryCode, String city,
                      GeocodeConfidence confidence) {
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.country = country;
        this.countryCode = countryCode;
        this.city = city;
        this.confidence = confidence == null ? GeocodeConfidence.APPROXIMATE : confidence;
    }

    public String getAddress() { return address; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getCountry() { return country; }
    public String getCountryCode() { return countryCode; }
    public String getCity() { return city; }
    public GeocodeConfidence getConfidence() { return confidence; }
}
