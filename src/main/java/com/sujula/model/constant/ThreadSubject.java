package com.sujula.model.constant;

/**
 * What a message thread is about.
 *
 * <p>There is no third value, and that is the rule rather than an omission. A
 * thread must hang off an order or a product, so there is no way to open a
 * channel to a stranger on this platform: the only people who can start a
 * conversation with a seller are somebody who bought from them and somebody
 * looking at something they are selling.
 */
public enum ThreadSubject {

    /** About something already bought. The seller can see which order. */
    ORDER,

    /** About something on sale. A question before buying. */
    PRODUCT;

    /** Whether the buyer has to have actually ordered to open this. */
    public boolean requiresAPurchase() {
        return this == ORDER;
    }
}
