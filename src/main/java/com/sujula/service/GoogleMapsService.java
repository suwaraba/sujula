package com.sujula.service;

import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.GeocodingApiRequest;
import com.google.maps.model.AddressComponent;
import com.google.maps.model.AddressComponentType;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.sujula.dto.GeoAddress;
import com.sujula.exceptions.GeocodingException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;


@Service
public class GoogleMapsService {
    private final GeoApiContext context;

    public GoogleMapsService(GeoApiContext context) {
        this.context = context;
    }

    private static final AddressComponentType[] CITY_FALLBACK = {
            AddressComponentType.LOCALITY,
            AddressComponentType.POSTAL_TOWN,
            AddressComponentType.ADMINISTRATIVE_AREA_LEVEL_2,
            AddressComponentType.ADMINISTRATIVE_AREA_LEVEL_1
    };

    // ---------- Public API ----------
    public GeoAddress getAddress(double latitude, double longitude, String languageCode) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "Invalid coordinates: " + latitude + "," + longitude);
        }
        String desc = "coordinates " + latitude + "," + longitude;
        GeocodingResult result = fetchFirst(
                GeocodingApi.reverseGeocode(context, new LatLng(latitude, longitude)), desc, languageCode);
        return toGeoAddress(result, desc);
    }


    public GeoAddress getCoordinates(String address, String languageCode) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Address must not be empty");
        }
        String desc = "address '" + address + "'";
        GeocodingResult result = fetchFirst(
                GeocodingApi.geocode(context, address.trim()), desc, languageCode);
        return toGeoAddress(result, desc);
    }

    // ---------- Shared pipeline ----------

    /** Single request/error path for both directions. */
    private GeocodingResult fetchFirst(GeocodingApiRequest request, String desc, String languageCode) {
        GeocodingResult[] results;
        try {
            results = request.language(languageCode).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeocodingException("Geocoding interrupted for " + desc, e);
        } catch (Exception e) {
            throw new GeocodingException("Geocoding failed for " + desc, e);
        }
        if (results == null || results.length == 0) {
            throw new GeocodingException("No results for " + desc);
        }
        return results[0];
    }

    /** Single extraction policy for both directions. */
    private GeoAddress toGeoAddress(GeocodingResult result, String desc) {
        Map<AddressComponentType, AddressComponent> index = indexComponents(result);

        AddressComponent country = index.get(AddressComponentType.COUNTRY);
        if (country == null) {
            throw new GeocodingException("No country resolved for " + desc);
        }

        return new GeoAddress(
                result.formattedAddress,
                result.geometry.location.lat,
                result.geometry.location.lng,
                country.longName,        // "Spain"
                country.shortName,       // "ES"
                firstOf(index, CITY_FALLBACK)
        );
    }

    /** One pass over components instead of repeated nested scans. */
    private Map<AddressComponentType, AddressComponent> indexComponents(GeocodingResult result) {
        Map<AddressComponentType, AddressComponent> index =
                new EnumMap<>(AddressComponentType.class);
        for (AddressComponent c : result.addressComponents) {
            for (AddressComponentType t : c.types) {
                index.putIfAbsent(t, c);
            }
        }
        return index;
    }

    private String firstOf(Map<AddressComponentType, AddressComponent> index,
                           AddressComponentType... priority) {
        for (AddressComponentType t : priority) {
            AddressComponent c = index.get(t);
            if (c != null) {
                return c.longName;
            }
        }
        return null;
    }

    // ---------- Cache keys (public: referenced from @Cacheable SpEL) ----------

    /** ~11 m precision so nearby lookups share a cache entry. */
    public String coordinateKey(double latitude, double longitude) {
        return String.format("%.4f,%.4f", latitude, longitude);
    }

    public String normalizeAddress(String address) {
        return address.trim().toLowerCase().replaceAll("\\s+", " ");
    }

//    public GeoAddress getCoordinates(String address) throws Exception {
//        GeocodingResult[] results = GeocodingApi.geocode(context, address).await();
//        if (results != null && results.length > 0){
//        String add = results[0].formattedAddress;
//        LatLng latlng = results[0].geometry.location;
//        return new GeoAddress(add, latlng.lat, latlng.lng);
//        } else
//                throw new Exception("No coordinate found for a given address : " + address);
//    }

//    public GeoAddress getAddress(double latitude, double longitude) throws Exception {
//
//        LatLng location = new LatLng(latitude, longitude);
//        GeocodingResult[] results = GeocodingApi.reverseGeocode(context, location).await();
//
//        if (results != null && results.length > 0) {
//            return new GeoAddress(results[0].formattedAddress, results[0].geometry.location.lat, results[0].geometry.location.lng);
//        } else throw new Exception("No address found for a given coordinates : " + latitude + "," + longitude);
//    }



}