package com.sujula.model.constant;

/**
 * How somebody is told something.
 *
 * <p>Three, and the omission is the interesting one: there is no SMS. It would
 * be the obvious channel for this market and it is deliberately absent, because
 * a channel that nothing actually sends on is a preference somebody switches on
 * and then waits for a message that never comes. When there is a sender, there
 * will be a value here.
 */
public enum NotificationChannel {

    /**
     * The inbox in the app. Always written for anything worth keeping.
     *
     * <p>The one channel that cannot be switched off for an important event,
     * because it is the record rather than the delivery: somebody who turned
     * everything off still needs to be able to find out what happened when they
     * next open the app.
     */
    IN_APP,

    /**
     * Electronic mail.
     *
     * <p>The channel that reaches the person who paid rather than the person
     * receiving, which on this platform are usually different people on
     * different continents — and the one the release codes travel on.
     */
    EMAIL,

    /**
     * A push notification to a registered device.
     *
     * <p>Useless without a device registered, which is why registering one is
     * an endpoint rather than an assumption. A driver on a round and an operator
     * behind a counter are the two people this actually matters to.
     */
    PUSH;

    /**
     * Whether somebody may turn this channel off for an event that matters.
     *
     * <p>False for exactly one. The inbox is the record, not a message, and a
     * user who switched it off would have events happening to their orders with
     * nowhere to read about them afterwards.
     */
    public boolean isOptionalForImportantEvents() {
        return this != IN_APP;
    }
}
