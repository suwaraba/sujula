package com.sujula.dto;

public class GeoAddress {

    String address;
    private final double latitude;
    private final double longitude;
    private final String country;      // e.g. "Spain"
    private final String countryCode;  // ISO 3166-1 alpha-2, e.g. "ES"
    private final String city;

    public GeoAddress(String address, double latitude, double longitude,
                      String country, String countryCode, String city) {
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.country = country;
        this.countryCode = countryCode;
        this.city = city;
    }

    public String getAddress() { return address; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getCountry() { return country; }
    public String getCountryCode() { return countryCode; }
    public String getCity() { return city; }

}
