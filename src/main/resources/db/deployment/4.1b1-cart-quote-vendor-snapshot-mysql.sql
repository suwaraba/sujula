-- Task 4.1B1: immutable vendor discount evidence for authoritative cart quotes.
-- MySQL 8. Run once before deploying the matching application version.

ALTER TABLE cart_quotes
    ADD COLUMN platform_coupon_id BIGINT NULL,
    ADD COLUMN platform_coupon_code VARCHAR(50) NULL;

CREATE TABLE cart_quote_vendor_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    quote_id VARCHAR(64) NOT NULL,
    vendor_id BIGINT NOT NULL,
    discount_display DECIMAL(12, 2) NOT NULL,
    discount_native DECIMAL(12, 2) NOT NULL,
    platform_discount_share_display DECIMAL(12, 2) NOT NULL,
    vendor_coupon_id BIGINT NULL,
    vendor_coupon_code VARCHAR(50) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cart_quote_vendor_snapshot UNIQUE (quote_id, vendor_id),
    CONSTRAINT fk_quote_vendor_snapshot_quote
        FOREIGN KEY (quote_id) REFERENCES cart_quotes (id) ON DELETE CASCADE
);

-- vendor_id and both coupon ids are immutable audit identities, matching the
-- scalar ids on cart_quote_lines. They deliberately have no live-row FK: quote
-- evidence must remain readable if a catalogue/coupon row is later removed.

CREATE INDEX idx_quote_vendor_snapshot_quote
    ON cart_quote_vendor_snapshots (quote_id);

CREATE INDEX idx_quote_vendor_snapshot_vendor
    ON cart_quote_vendor_snapshots (vendor_id);
