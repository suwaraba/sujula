package com.sujula.config.seed;

/**
 * One slice of the sample dataset.
 *
 * <p>Split by domain rather than by entity, because the interesting part of this
 * data is the relationships — an order with its lines, its per-vendor sub-orders,
 * its payment and its status history is one thing to write, and writing it as
 * five independent entity seeders would mean five passes re-finding the same
 * rows. Stages run in a fixed order and may read anything an earlier stage left
 * in the {@link SeedCatalogue}; nothing may read forward.
 */
interface SeedStage {

    /** What this stage is called in the startup log. */
    String name();

    void seed(SeedCatalogue catalogue);
}
