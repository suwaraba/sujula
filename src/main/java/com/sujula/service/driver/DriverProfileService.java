package com.sujula.service.driver;

import java.time.LocalDate;

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;

import org.springframework.data.domain.Pageable;

/**
 * A driver's own account, availability and work queue.
 *
 * <p>Everything resolves the driver from the authenticated user. No method takes
 * a driver id, so there is no parameter to change to answer somebody else's
 * offers or read their earnings.
 *
 * <p>An offer carries a town, a distance and what it pays — never an address.
 * The same leg is frequently offered to several drivers and only one of them
 * goes; showing a home address at offer time would hand it to all the others.
 */
public interface DriverProfileService {

    /** Applying to carry goods. Creates a profile in PENDING with KYC to review. */
    DriverResponses.Profile apply(Long userId, DriverRequests.Apply request);

    DriverResponses.Profile myProfile(Long userId);

    /** Vehicle and zone. Nothing here changes the platform's view of the driver. */
    DriverResponses.Profile updateProfile(Long userId, DriverRequests.UpdateProfile request);

    /**
     * Going online or off.
     *
     * <p>A driver's own decision, and separate from whether the platform has
     * approved them. Going online while suspended changes nothing about what
     * they are offered.
     */
    DriverResponses.AvailabilitySet setAvailability(Long userId,
                                                    DriverRequests.SetAvailability request);

    /**
     * Where the driver is.
     *
     * <p>Refused while offline — a platform that tracked drivers who had
     * finished for the day would be tracking people rather than parcels — and
     * throttled, because a phone pinging every second is a flat battery by
     * eleven.
     */
    DriverResponses.LocationAccepted ping(Long userId, DriverRequests.Ping request);

    /** Offers waiting on an answer, and work already taken. */
    DriverResponses.Assignments assignments(Long userId);

    /** Taking a leg, if the offer has not lapsed. */
    DriverResponses.AssignmentAnswered accept(Long userId, Long legId);

    /** Turning one down, with the reason that moves the score. */
    DriverResponses.AssignmentAnswered decline(Long userId, Long legId,
                                               DriverRequests.Decline request);

    /** What the driver has earned, per currency, over a window. */
    DriverResponses.Earnings earnings(Long userId, LocalDate from, LocalDate to);

    /** Finished jobs. Towns rather than addresses. */
    DriverResponses.History history(Long userId, Pageable pageable);
}
