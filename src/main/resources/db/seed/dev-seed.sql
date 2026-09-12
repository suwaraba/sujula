-- ============================================================================
--  Sujula development seed
-- ============================================================================
--  Populates all 39 tables with one coherent, related dataset. Written to be
--  run by hand against a dev database:
--
--      mysql -u root -p sujula < src/main/resources/db/seed/dev-seed.sql
--
--  Deliberately NOT auto-loaded on startup. Seed data appearing in a database
--  by surprise is worse than having to type one command, and this file deletes
--  before it inserts.
--
--  ── Conventions ────────────────────────────────────────────────────────────
--
--  Every seeded row has an explicit id of 1000 or more. Application-created
--  rows start at 1, so the two never collide — the bootstrap administrator is
--  id 1 and survives a re-seed untouched. It also makes the whole thing
--  re-runnable: the DELETE block at the top removes exactly `id >= 1000` in
--  reverse foreign-key order, so running this twice is the same as once.
--
--  The ids encode their own table, which makes the joins readable without
--  looking anything up:
--
--      10xx  users            13xx  products          16xx  payments
--      11xx  vendors          14xx  orders            17xx  deliveries
--      12xx  catalogue        15xx  vendor orders     18xx  everything else
--
--  ── What the data represents ───────────────────────────────────────────────
--
--  Two sellers who settle in different currencies:
--
--      Kombo Electronics   Serekunda, The Gambia    settles GMD
--      Teranga Textiles    Ziguinchor, Senegal      settles XOF
--
--  Three buyers, one of them abroad:
--
--      Aminata Ceesay      Serekunda                shops in GMD
--      Oliver Bennett      London                   shops in GBP
--      Modou Sanneh        Brikama                  shops in GMD
--
--  And three orders chosen to exercise the parts that are easy to get wrong:
--
--      1401  Oliver, in GBP, from BOTH vendors. This is the interesting one —
--            a multivendor split across two settlement currencies, with each
--            product's delivery leg priced on its own distance and weight, and
--            each vendor's commission and payout frozen in that vendor's own
--            currency. Paid by card through the mock gateway.
--      1402  A guest order, no account, cash on delivery, still PENDING.
--      1403  Aminata, in GMD, single vendor, DELIVERED — carries the full
--            delivery chain (assignment, tracking, handover code, proof) and a
--            review, so the last-mile tables have something in them even
--            though no endpoint writes them yet.
--
--  All passwords are  Sujula123!  (a real BCrypt hash, verified against the
--  application's own encoder).
-- ============================================================================

SET NAMES utf8mb4;
SET @PW = '$2a$10$nRluET0D32C7aC1ANGfIZ.yyg2UmvHcZTSm2az36pyqS7sHvXcdmm';
SET @NOW = '2026-09-12 09:00:00.000000';

START TRANSACTION;

-- ── Clear previous seed ─────────────────────────────────────────────────────
-- Reverse foreign-key order. No FOREIGN_KEY_CHECKS=0 anywhere: if this order
-- is wrong the database says so, which is the point.

DELETE FROM handover_codes         WHERE id >= 1000;
DELETE FROM delivery_tracking      WHERE id >= 1000;
DELETE FROM proof_of_delivery      WHERE id >= 1000;
DELETE FROM deliveries             WHERE id >= 1000;
DELETE FROM delivery_routes        WHERE id >= 1000;
DELETE FROM variant_option_values  WHERE variant_id >= 1000;
DELETE FROM product_variant_values WHERE variant_id >= 1000;
DELETE FROM product_option_values  WHERE id >= 1000;
DELETE FROM order_items            WHERE id >= 1000;
DELETE FROM cart_items             WHERE id >= 1000;
DELETE FROM product_variants       WHERE id >= 1000;
DELETE FROM product_options        WHERE id >= 1000;
DELETE FROM product_images         WHERE id >= 1000;
DELETE FROM product_attributes     WHERE id >= 1000;
DELETE FROM order_status_history   WHERE id >= 1000;
DELETE FROM coupon_usages          WHERE id >= 1000;
DELETE FROM cart_coupons           WHERE id >= 1000;
DELETE FROM wishlist_items         WHERE id >= 1000;
DELETE FROM vendor_orders          WHERE id >= 1000;
DELETE FROM vendor_bank_accounts   WHERE id >= 1000;
DELETE FROM reviews                WHERE id >= 1000;
DELETE FROM payments               WHERE id >= 1000;
DELETE FROM orders                 WHERE id >= 1000;
DELETE FROM products               WHERE id >= 1000;
DELETE FROM pickup_points          WHERE id >= 1000;
DELETE FROM notifications          WHERE id >= 1000;
DELETE FROM drivers                WHERE id >= 1000;
DELETE FROM coupons                WHERE id >= 1000;
DELETE FROM carts                  WHERE id >= 1000;
DELETE FROM audit_logs             WHERE id >= 1000;
DELETE FROM addresses              WHERE id >= 1000;
DELETE FROM wishlists              WHERE id >= 1000;
DELETE FROM vendors                WHERE id >= 1000;
DELETE FROM vendor_payouts         WHERE id >= 1000;
DELETE FROM users                  WHERE id >= 1000;
DELETE FROM gift_cards             WHERE id >= 1000;
DELETE FROM exchange_rates         WHERE id >= 1000;
DELETE FROM categories             WHERE id >= 1000;
DELETE FROM brands                 WHERE id >= 1000;

-- ── brands ──────────────────────────────────────────────────────────────────

INSERT INTO brands (id, name, slug, description, website, logo_url, sort_order, active, created_at, updated_at) VALUES
 (1201, 'Samsung',   'samsung',   'Consumer electronics',                'https://samsung.com', NULL, 1, 1, @NOW, @NOW),
 (1202, 'Nokia',     'nokia',     'Handsets built for long battery life', 'https://nokia.com',   NULL, 2, 1, @NOW, @NOW),
 (1203, 'Tobaski',   'tobaski',   'Local homeware, made in Serekunda',    NULL,                  NULL, 3, 1, @NOW, @NOW);

-- ── categories (self-referencing: parents first) ────────────────────────────

INSERT INTO categories (id, name, slug, description, parent_id, image_url, sort_order, active, created_at) VALUES
 (1210, 'Electronics', 'electronics', 'Phones, audio and accessories', NULL,  NULL, 1, 1, @NOW),
 (1211, 'Home',        'home',        'Kitchen and household',         NULL,  NULL, 2, 1, @NOW),
 (1212, 'Fabrics',     'fabrics',     'Cloth sold by the yard',        NULL,  NULL, 3, 1, @NOW);

INSERT INTO categories (id, name, slug, description, parent_id, image_url, sort_order, active, created_at) VALUES
 (1213, 'Phones',        'phones',         'Smartphones and feature phones', 1210, NULL, 1, 1, @NOW),
 (1214, 'Audio',         'audio',          'Speakers and headphones',        1210, NULL, 2, 1, @NOW),
 (1215, 'Kitchen',       'kitchen',        'Cookware and small appliances',  1211, NULL, 1, 1, @NOW),
 (1216, 'Wax Prints',    'wax-prints',     'Printed cotton, six-yard pieces', 1212, NULL, 1, 1, @NOW);

-- ── exchange_rates ──────────────────────────────────────────────────────────
-- What the platform converts with. Both vendor currencies need a route to
-- every currency a buyer might shop in, or checkout is refused rather than
-- guessed at. Rates are indicative, not live.

INSERT INTO exchange_rates (id, from_currency, currency, country, rate, rate_date, created_at) VALUES
 (1220, 'GMD', 'GBP', 'GB', 0.01100000, '2026-09-12', @NOW),
 (1221, 'GMD', 'XOF', 'SN', 8.60000000, '2026-09-12', @NOW),
 (1222, 'GMD', 'USD', 'US', 0.01400000, '2026-09-12', @NOW),
 (1223, 'XOF', 'GBP', 'GB', 0.00128000, '2026-09-12', @NOW),
 (1224, 'XOF', 'GMD', 'GM', 0.11600000, '2026-09-12', @NOW),
 (1225, 'GBP', 'GMD', 'GM', 90.90000000, '2026-09-12', @NOW),
 (1226, 'GMD', 'GMD', 'GM', 1.00000000, '2026-09-12', @NOW),
 (1227, 'XOF', 'XOF', 'SN', 1.00000000, '2026-09-12', @NOW);

-- ── gift_cards ──────────────────────────────────────────────────────────────
-- The entity exists and Order carries gift_card_code / gift_card_discount, but
-- no service reads either. Seeded so the table is not empty when you look.

INSERT INTO gift_cards (id, code, currency, initial_amount, remaining_balance, issued_to_email, purchased_by_user_id, redeemed_by_user_id, expires_at, active, created_at) VALUES
 (1280, 'SJL-GIFT-5CQ2NA', 'GMD', 2000.00, 2000.00, 'aminata.ceesay@example.gm', NULL, NULL, '2027-09-12 00:00:00.000000', 1, @NOW),
 (1281, 'SJL-GIFT-7HW4KP', 'GMD', 1000.00,  350.00, 'modou.sanneh@example.gm',   NULL, NULL, '2027-03-12 00:00:00.000000', 1, @NOW);

-- ── users ───────────────────────────────────────────────────────────────────
-- Registration only ever creates a CUSTOMER; VENDOR and the staff roles are
-- reached by approval. Seeded at their end state.

INSERT INTO users
 (id, first_name, last_name, email, password, phone, role,
  enabled, email_verified, blocked, fraud, totp_enabled, totp_verified,
  failed_login_attempts, preferred_currency, preferred_language, detected_country_code,
  created_at, updated_at)
VALUES
 (1001, 'Fatou',  'Jallow',  'fatou.admin@sujula.gm',      @PW, '+2203100001', 'ADMIN',
  1, 1, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1002, 'Lamin',  'Touray',  'lamin.kombo@sujula.gm',      @PW, '+2203100002', 'VENDOR',
  1, 1, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1003, 'Awa',    'Diallo',  'awa.teranga@sujula.sn',      @PW, '+2217700003', 'VENDOR',
  1, 1, 0, 0, 0, 0, 0, 'XOF', 'fr', 'SN', @NOW, @NOW),
 (1004, 'Aminata','Ceesay',  'aminata.ceesay@example.gm',  @PW, '+2203100004', 'CUSTOMER',
  1, 1, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1005, 'Oliver', 'Bennett', 'oliver.bennett@example.co.uk',@PW,'+447700900005','CUSTOMER',
  1, 1, 0, 0, 0, 0, 0, 'GBP', 'en', 'GB', @NOW, @NOW),
 (1006, 'Modou',  'Sanneh',  'modou.sanneh@example.gm',    @PW, '+2203100006', 'CUSTOMER',
  1, 0, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1007, 'Ebrima', 'Bojang',  'ebrima.driver@sujula.gm',    @PW, '+2203100007', 'DELIVERY',
  1, 1, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1008, 'Isatou', 'Camara',  'isatou.pickup@sujula.gm',    @PW, '+2203100008', 'PICKUP_OPERATOR',
  1, 1, 0, 0, 0, 0, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1009, 'Sulayman','Gomez',  'sulayman.blocked@example.gm',@PW, '+2203100009', 'CUSTOMER',
  1, 1, 1, 1, 0, 0, 3, 'GMD', 'en', 'GM', @NOW, @NOW);

-- ── vendor_payouts ──────────────────────────────────────────────────────────
-- Hangs off users, not vendors. No service writes these: amounts owed are
-- frozen per vendor order (see vendor_orders.payout_native) and nothing pays
-- them out yet.

INSERT INTO vendor_payouts (id, user_id, amount, currency, status, reference, notes, processed_by, processed_at, created_at, updated_at) VALUES
 (1030, 1002, 4860.00, 'GMD', 'COMPLETED',  'PO-2026-08-KOMBO', 'August settlement',      1001, '2026-09-01 10:00:00.000000', @NOW, @NOW),
 (1031, 1002,  810.00, 'GMD', 'PENDING',    'PO-2026-09-KOMBO', 'September, in progress', NULL, NULL, @NOW, @NOW),
 (1032, 1003, 96000.00,'XOF', 'PROCESSING', 'PO-2026-09-TERANGA',NULL,                    NULL, NULL, @NOW, @NOW);

-- ── vendors ─────────────────────────────────────────────────────────────────
-- Coordinates matter: they are the despatch origin every delivery leg is
-- measured from. Without them pricing falls back to a flat per-scope distance.
-- The two settlement currencies are the point of this pair.

INSERT INTO vendors
 (id, user_id, store_name, store_slug, description, store_email, store_phone, website,
  address_street, address_city, address_state, address_postal_code, address_country_code,
  latitude, longitude, settlement_currency, status, default_commission_rate,
  balance, rating, total_reviews, total_sold, business_registration_number, tax_number,
  logo_url, banner_url, created_at, updated_at)
VALUES
 (1101, 1002, 'Kombo Electronics', 'kombo-electronics',
  'Phones, speakers and kettles on Kairaba Avenue since 2014.',
  'shop@kombo.gm', '+2204380001', NULL,
  '14 Kairaba Avenue', 'Serekunda', 'West Coast', NULL, 'GM',
  13.43830000, -16.67810000, 'GMD', 'APPROVED', 10.00,
  810.00, 4.60, 2, 14, 'GM-RC-884120', 'GM-TIN-55231',
  NULL, NULL, @NOW, @NOW),
 (1102, 1003, 'Teranga Textiles', 'teranga-textiles',
  'Wax prints and damask, cut to six yards. Ships from Ziguinchor.',
  'bonjour@teranga.sn', '+2213390002', NULL,
  '8 Rue de France', 'Ziguinchor', 'Ziguinchor', '27000', 'SN',
  12.56410000, -16.27190000, 'XOF', 'APPROVED', 12.50,
  96000.00, 4.80, 1, 6, 'SN-RC-220914', NULL,
  NULL, NULL, @NOW, @NOW);

-- ── wishlists ───────────────────────────────────────────────────────────────

INSERT INTO wishlists (id, user_id, created_at) VALUES
 (1040, 1004, @NOW),
 (1041, 1005, @NOW);

-- ── addresses ───────────────────────────────────────────────────────────────
-- `latitude`/`longitude` are set here as if the geocoder had placed them. The
-- application geocodes on save and exposes a `located` flag; a row with nulls
-- is a legitimate state and 1053 is seeded that way on purpose.

INSERT INTO addresses
 (id, user_id, label, full_name, phone, street, apartment_suite, city, state,
  postal_code, country_code, latitude, longitude, is_default, created_at)
VALUES
 (1050, 1004, 'Home',   'Aminata Ceesay', '+2203100004', '27 Sayerr Jobe Avenue', NULL,       'Serekunda', 'West Coast', NULL,     'GM', 13.43950000, -16.67520000, 1, @NOW),
 (1051, 1004, 'Shop',   'Aminata Ceesay', '+2203100004', 'Latrikunda Market',     'Stall 14', 'Serekunda', 'West Coast', NULL,     'GM', 13.42880000, -16.66900000, 0, @NOW),
 (1052, 1005, 'Home',   'Oliver Bennett', '+447700900005','221B Baker Street',    'Flat 2',   'London',    'Greater London','NW1 6XE','GB', 51.52370000, -0.15850000, 1, @NOW),
 (1053, 1006, 'Home',   'Modou Sanneh',   '+2203100006', 'Off Brikama Highway',   NULL,       'Brikama',   'West Coast', NULL,     'GM', NULL,          NULL,         1, @NOW);

-- ── audit_logs ──────────────────────────────────────────────────────────────
-- Append-only. The actor is recorded twice: by id, and as a name/email
-- snapshot that survives the account being renamed or deleted.

INSERT INTO audit_logs (id, action, actor_user_id, actor_email, actor_name, target_type, target_id, target_label, summary, details, ip_address, created_at) VALUES
 (1060, 'ADMIN_BOOTSTRAPPED', NULL, NULL, NULL, 'USER', 1, 'admin@sujula.gm',
  'First administrator created from configuration', NULL, NULL, '2026-09-12 07:04:29.000000'),
 (1061, 'VENDOR_STATUS_CHANGED', 1001, 'fatou.admin@sujula.gm', 'Fatou Jallow', 'VENDOR', 1101, 'Kombo Electronics',
  'Vendor approved after document check', 'PENDING -> APPROVED', '10.0.0.14', @NOW),
 (1062, 'VENDOR_STATUS_CHANGED', 1001, 'fatou.admin@sujula.gm', 'Fatou Jallow', 'VENDOR', 1102, 'Teranga Textiles',
  'Vendor approved after document check', 'PENDING -> APPROVED', '10.0.0.14', @NOW),
 (1063, 'USER_BLOCKED', 1001, 'fatou.admin@sujula.gm', 'Fatou Jallow', 'USER', 1009, 'sulayman.blocked@example.gm',
  'Account blocked after chargeback pattern', 'Three disputed card payments in seven days', '10.0.0.14', @NOW),
 (1064, 'USER_FRAUD_FLAGGED', 1001, 'fatou.admin@sujula.gm', 'Fatou Jallow', 'USER', 1009, 'sulayman.blocked@example.gm',
  'Account flagged for fraud review', NULL, '10.0.0.14', @NOW),
 (1065, 'PAYMENT_TRANSFER_CONFIRMED', 1001, 'fatou.admin@sujula.gm', 'Fatou Jallow', 'PAYMENT', 1601, 'PAY-SEED0000001',
  'Bank transfer matched against statement', 'Reference quoted correctly', '10.0.0.14', @NOW);

-- ── carts ───────────────────────────────────────────────────────────────────
-- 1070 belongs to a signed-in buyer and never expires. 1071 is a guest cart,
-- identified by the HttpOnly cookie the server issued, with a TTL.

INSERT INTO carts (id, user_id, session_id, display_currency, expires_at, created_at, updated_at) VALUES
 (1070, 1004, NULL, 'GMD', NULL, @NOW, @NOW),
 (1071, NULL, 'a7f3c1e2-9b84-4d55-8e10-2c6f7b0d91aa', 'GBP', '2026-09-19 09:00:00.000000', @NOW, @NOW);

-- ── coupons ─────────────────────────────────────────────────────────────────
-- Checkout honours these and applies vendor-scoped ones only to that vendor's
-- lines. No endpoint creates a coupon, so seeding is currently the only way to
-- get one.

INSERT INTO coupons
 (id, code, description, type, scope, vendor_id, value, currency,
  minimum_order_amount, maximum_discount_amount, usage_limit, per_user_limit,
  usage_count, starts_at, expires_at, active, created_at, updated_at)
VALUES
 (1080, 'TERANGA10', '10% off everything, platform funded', 'PERCENTAGE', 'PLATFORM', NULL,
  10.00, NULL, 500.00, 1500.00, 500, 1, 2, @NOW, '2026-12-31 23:59:59.000000', 1, @NOW, @NOW),
 (1081, 'KOMBO500', '500 dalasi off at Kombo Electronics', 'FIXED_AMOUNT', 'VENDOR', 1101,
  500.00, 'GMD', 3000.00, NULL, 100, 1, 0, @NOW, '2026-11-30 23:59:59.000000', 1, @NOW, @NOW),
 (1082, 'FREESHIP', 'Free delivery, platform funded', 'FREE_SHIPPING', 'PLATFORM', NULL,
  0.00, NULL, 2000.00, NULL, NULL, 1, 1, @NOW, '2026-10-31 23:59:59.000000', 1, @NOW, @NOW),
 (1083, 'EXPIRED20', 'Lapsed, kept so you can test rejection', 'PERCENTAGE', 'PLATFORM', NULL,
  20.00, NULL, NULL, NULL, 50, 1, 12, '2026-01-01 00:00:00.000000', '2026-06-30 23:59:59.000000', 0, @NOW, @NOW);

-- ── drivers ─────────────────────────────────────────────────────────────────
-- Modelled in full, with no service or controller behind it. `max_weight` is
-- in kilograms and is what a dispatcher would match a parcel against.

INSERT INTO drivers
 (id, user_id, phone, country_code, zone, license_number, vehicle_type, vehicle_model,
  vehicle_plate, vehicle_color, max_weight, available, status, commission_rate,
  current_latitude, current_longitude, last_location_at, total_deliveries,
  total_earnings, total_ratings, average_rating, avatar_url, admin_note, created_at, updated_at)
VALUES
 (1090, 1007, '+2203100007', 'GM', 'Kombo North', 'GM-DL-771204', 'MOTOR', 'Haojue HJ125',
  'BJL 4417 C', 'Red', 25, 1, 'APPROVED', 12.00,
  13.44120000, -16.67030000, @NOW, 37,
  9250.00, 31, 4.70, NULL, NULL, @NOW, @NOW);

-- ── notifications ───────────────────────────────────────────────────────────
-- The column is `is_read`, not `read`: `read` is reserved in MySQL and the
-- table would not create.

INSERT INTO notifications (id, user_id, title, message, type, reference_id, is_read, created_at) VALUES
 (1091, 1005, 'Order confirmed',  'Order SJL-SEED-0001 has been confirmed.',           'ORDER',   'SJL-SEED-0001', 1, @NOW),
 (1092, 1005, 'Payment received', 'We received your card payment for SJL-SEED-0001.',  'PAYMENT', 'SJL-SEED-0001', 0, @NOW),
 (1093, 1002, 'New order',        'You have a new order to prepare.',                  'VENDOR',  'SJL-SEED-0001', 0, @NOW),
 (1094, 1003, 'New order',        'You have a new order to prepare.',                  'VENDOR',  'SJL-SEED-0001', 0, @NOW),
 (1095, 1004, 'Order delivered',  'Order SJL-SEED-0003 has been delivered. Enjoy!',    'ORDER',   'SJL-SEED-0003', 1, @NOW);

-- ── pickup_points ───────────────────────────────────────────────────────────
-- An alternative to home delivery: a shorter, cheaper leg to a hub the buyer
-- collects from. Priced against the hub, not the buyer's address.
--
-- postal_code is NOT NULL on this table while addresses.postal_code is
-- nullable, which is inconsistent and wrong for this market: The Gambia has no
-- postal code system. Seeded as an empty string because that is the truth, but
-- the column should be nullable.

INSERT INTO pickup_points
 (id, operator_user_id, name, address_street, address_apartment, city, state, postal_code,
  country_code, latitude, longitude, contact_phone, contact_email, manager_name,
  opening_hours, active, status, total_transactions, monthly_deliveries, total_earnings,
  profile_image_url, admin_note, created_at, updated_at)
VALUES
 (1096, 1008, 'Westfield Junction Pickup', 'Westfield Junction', 'Unit 3', 'Serekunda', 'West Coast', '',
  'GM', 13.44290000, -16.67760000, '+2203100008', 'isatou.pickup@sujula.gm', 'Isatou Camara',
  'Mon-Sat 08:00-20:00', 1, 'APPROVED', 214, 46, 18400.00,
  NULL, NULL, @NOW, @NOW);

-- ── products ────────────────────────────────────────────────────────────────
-- `price_currency` and `country` are derived from the vendor at save time by
-- the application, never taken from a request — a seller cannot list in a
-- currency they do not settle in. Seeded consistently with that rule.
--
-- `weight_kg` and coordinates are what delivery pricing measures. A product
-- with neither is billed on a flat per-scope fallback, which 1306 demonstrates.

INSERT INTO products
 (id, vendor_id, category_id, brand_id, name, slug, short_description, description,
  sku, price, compare_at_price, price_currency, stock, low_stock_threshold,
  allow_backorder, active, featured, weight_kg, dimensions, country, delivery_scope,
  latitude, longitude, rating, total_reviews, total_sold, score, last_restocked_at,
  created_at, updated_at)
VALUES
 (1301, 1101, 1213, 1201, 'Samsung Galaxy A16', 'samsung-galaxy-a16',
  '6.7-inch screen, 5000mAh battery', 'Dual SIM, expandable storage, two-year local warranty.',
  'KOM-SGA16', 8500.00, 9750.00, 'GMD', 12, 3, 0, 1, 1, 0.195, '165x77x8 mm', 'GM', 'NATIONAL',
  13.43830000, -16.67810000, 4.50, 2, 9, 87, @NOW, @NOW, @NOW),
 (1302, 1101, 1213, 1202, 'Nokia 110 4G', 'nokia-110-4g',
  'Feature phone, torch, month-long standby', 'Keypad handset with FM radio and a removable battery.',
  'KOM-N110', 1450.00, NULL, 'GMD', 40, 10, 1, 1, 0, 0.085, '121x50x14 mm', 'GM', 'NATIONAL',
  13.43830000, -16.67810000, 4.20, 1, 3, 61, @NOW, @NOW, @NOW),
 (1303, 1101, 1215, 1203, 'Tobaski 1.8L Electric Kettle', 'tobaski-electric-kettle',
  'Stainless steel, auto shut-off', 'Boils 1.8 litres in four minutes. 240V.',
  'KOM-KET18', 1250.00, 1600.00, 'GMD', 22, 5, 0, 1, 1, 1.240, '220x160x240 mm', 'GM', 'REGIIONAL',
  13.43830000, -16.67810000, 4.70, 1, 2, 74, @NOW, @NOW, @NOW),
 (1304, 1101, 1214, NULL, 'Kombo Bluetooth Speaker', 'kombo-bluetooth-speaker',
  'Ten hours of playback', 'Splash-resistant, USB-C charging, carry strap.',
  'KOM-SPK10', 2100.00, NULL, 'GMD', 0, 4, 0, 1, 0, 0.540, '180x75x75 mm', 'GM', 'REGIIONAL',
  13.43830000, -16.67810000, NULL, 0, 0, 40, NULL, @NOW, @NOW),
 (1305, 1102, 1216, NULL, 'Wax Print — Six Yards, Indigo', 'wax-print-six-yards-indigo',
  'Hand-finished cotton, six-yard piece', 'Printed in Ziguinchor. Colour holds through cold washing.',
  'TER-WAX-IND', 14500.00, NULL, 'XOF', 18, 4, 0, 1, 1, 0.850, '6 yards', 'SN', 'GLOBAL',
  12.56410000, -16.27190000, 4.80, 1, 6, 80, @NOW, @NOW, @NOW),
 (1306, 1102, 1216, NULL, 'Damask Bazin — Three Yards', 'damask-bazin-three-yards',
  'Heavy damask, unbleached', 'Sold in three-yard cuts. Weight and origin not recorded by the seller.',
  'TER-BAZ-3Y', 9800.00, NULL, 'XOF', 7, 2, 0, 1, 0, NULL, NULL, 'SN', 'NATIONAL',
  NULL, NULL, NULL, 0, 0, 35, NULL, @NOW, @NOW),
 (1307, 1101, 1213, 1201, 'Samsung Galaxy A05 (withdrawn)', 'samsung-galaxy-a05',
  'Superseded model', 'Unpublished rather than deleted: order lines still point at it.',
  'KOM-SGA05', 6900.00, NULL, 'GMD', 0, 3, 0, 0, 0, 0.190, NULL, 'GM', 'NATIONAL',
  13.43830000, -16.67810000, NULL, 0, 4, 10, NULL, @NOW, @NOW);

-- ── reviews ─────────────────────────────────────────────────────────────────
-- Nothing writes these: Review is an entity with no service, and the
-- `rating` / `total_reviews` columns on products above are maintained by hand
-- here to match.

INSERT INTO reviews (id, product_id, user_id, rating, title, comment, verified, vendor_reply, vendor_replied_at, created_at) VALUES
 (1310, 1301, 1004, 5, 'Battery is the selling point', 'Two days between charges with normal use. Screen is bright enough outdoors.', 1, 'Thank you Aminata!', @NOW, @NOW),
 (1311, 1301, 1005, 4, 'Good, slow to charge',        'No complaints about the phone. The charger in the box is slow.', 1, NULL, NULL, @NOW),
 (1312, 1303, 1004, 5, 'Boils fast',                  'Four minutes as advertised. Handle stays cool.', 1, NULL, NULL, @NOW),
 (1313, 1302, 1006, 4, 'Does what it should',         'Bought it for the torch and the standby time.', 0, NULL, NULL, @NOW),
 (1314, 1305, 1005, 5, 'Colour held',                 'Washed cold three times, no bleeding at all.', 1, NULL, NULL, @NOW);

-- ── vendor_bank_accounts ────────────────────────────────────────────────────
-- Where a payout would go. MOBILE_MONEY is the account type that matters most
-- in this market, and is deliberately the default for one of the two.

INSERT INTO vendor_bank_accounts
 (id, vendor_id, account_holder_name, bank_name, account_number, account_type, currency,
  iban, swift_code, routing_number, mobile_money_phone, is_default, verified, created_at, updated_at)
VALUES
 (1320, 1101, 'Lamin Touray', 'Trust Bank Gambia', '0123456789', 'CHECKING',     'GMD',
  NULL, 'TBLGGMGM', NULL, NULL, 1, 1, @NOW, @NOW),
 (1321, 1101, 'Lamin Touray', 'Africell Money',    '+2203100002', 'MOBILE_MONEY', 'GMD',
  NULL, NULL, NULL, '+2203100002', 0, 1, @NOW, @NOW),
 (1322, 1102, 'Awa Diallo',   'Wave Senegal',      '+2217700003', 'MOBILE_MONEY', 'XOF',
  NULL, NULL, NULL, '+2217700003', 1, 0, @NOW, @NOW);

-- ── product options, values and variants ────────────────────────────────────
-- The phone is the only product with real variants: storage and colour, with
-- a surcharge on the larger storage. Every variant picks one value per option.

INSERT INTO product_options (id, product_id, code, name, sort_order, created_at) VALUES
 (1330, 1301, 'storage', 'Storage', 1, @NOW),
 (1331, 1301, 'colour',  'Colour',  2, @NOW),
 (1332, 1305, 'length',  'Length',  1, @NOW);

INSERT INTO product_option_values (id, option_id, value, display_value, extra_price, color_hex, image_url, sort_order) VALUES
 (1340, 1330, '128GB', '128 GB',   0.00,    NULL,      NULL, 1),
 (1341, 1330, '256GB', '256 GB',   1200.00, NULL,      NULL, 2),
 (1342, 1331, 'BLACK', 'Black',    0.00,    '#111111', NULL, 1),
 (1343, 1331, 'BLUE',  'Blue',     0.00,    '#1F4E9C', NULL, 2),
 (1344, 1332, '6Y',    'Six yards',0.00,    NULL,      NULL, 1);

INSERT INTO product_variants (id, product_id, sku, stock, price_override, active) VALUES
 (1350, 1301, 'KOM-SGA16-128-BLK', 5, NULL,    1),
 (1351, 1301, 'KOM-SGA16-128-BLU', 3, NULL,    1),
 (1352, 1301, 'KOM-SGA16-256-BLK', 4, 9700.00, 1),
 (1353, 1305, 'TER-WAX-IND-6Y',   18, NULL,    1);

-- Which option values each variant selects. Two join tables exist for this —
-- product_variant_values and variant_option_values — mapping the same pair.
-- Both are populated so either read path resolves; consolidating them is a
-- separate change.

INSERT INTO product_variant_values (variant_id, option_value_id) VALUES
 (1350, 1340), (1350, 1342),
 (1351, 1340), (1351, 1343),
 (1352, 1341), (1352, 1342),
 (1353, 1344);

INSERT INTO variant_option_values (variant_id, option_value_id) VALUES
 (1350, 1340), (1350, 1342),
 (1351, 1340), (1351, 1343),
 (1352, 1341), (1352, 1342),
 (1353, 1344);

-- ── product_attributes ──────────────────────────────────────────────────────
-- The column is `value`; reserved in H2 but legal in MySQL.

INSERT INTO product_attributes (id, product_id, name, value, sort_order) VALUES
 (1360, 1301, 'Screen',   '6.7 inch PLS LCD, 90Hz', 1),
 (1361, 1301, 'Battery',  '5000 mAh',               2),
 (1362, 1301, 'SIM',      'Dual nano-SIM',          3),
 (1363, 1303, 'Capacity', '1.8 litres',             1),
 (1364, 1303, 'Power',    '1500 W, 240 V',          2),
 (1365, 1305, 'Material', '100% cotton',            1),
 (1366, 1305, 'Care',     'Cold wash, iron inside out', 2);

-- ── product_images ──────────────────────────────────────────────────────────
-- Exactly one default per product — that is the card image. URLs point at a
-- bucket that does not exist in dev; presign refuses while storage is
-- unconfigured, so these are here to populate the column, not to load.

INSERT INTO product_images (id, product_id, image_url, alt_text, sort_order, is_default, created_at) VALUES
 (1370, 1301, 'https://media.example.invalid/products/sga16-front.jpg', 'Galaxy A16 front',   0, 1, @NOW),
 (1371, 1301, 'https://media.example.invalid/products/sga16-back.jpg',  'Galaxy A16 back',    1, 0, @NOW),
 (1372, 1302, 'https://media.example.invalid/products/n110.jpg',        'Nokia 110 4G',       0, 1, @NOW),
 (1373, 1303, 'https://media.example.invalid/products/kettle.jpg',      'Kettle',             0, 1, @NOW),
 (1374, 1304, 'https://media.example.invalid/products/speaker.jpg',     'Bluetooth speaker',  0, 1, @NOW),
 (1375, 1305, 'https://media.example.invalid/products/wax-indigo.jpg',  'Indigo wax print',   0, 1, @NOW),
 (1376, 1306, 'https://media.example.invalid/products/bazin.jpg',       'Damask bazin',       0, 1, @NOW);

-- ── wishlist_items ──────────────────────────────────────────────────────────

INSERT INTO wishlist_items (id, wishlist_id, product_id, added_at) VALUES
 (1380, 1040, 1304, @NOW),
 (1381, 1040, 1305, @NOW),
 (1382, 1041, 1301, @NOW);

-- ── cart_items ──────────────────────────────────────────────────────────────
-- `variant_key` is a sentinel, not the FK: 0 means "no variant". It exists
-- because SQL treats NULL as distinct from NULL, so a nullable variant_id
-- would let duplicate lines through for variant-less products. Unit prices are
-- held in each vendor's own listing currency; conversion happens at read time.

INSERT INTO cart_items
 (id, cart_id, product_id, variant_id, variant_key, vendor_id, quantity,
  unit_price, unit_price_currency, price_checked_at, created_at, updated_at)
VALUES
 (1390, 1070, 1301, 1350, 1350, 1101, 1, 8500.00,  'GMD', @NOW, @NOW, @NOW),
 (1391, 1070, 1303, NULL, 0,    1101, 2, 1250.00,  'GMD', @NOW, @NOW, @NOW),
 (1392, 1071, 1305, 1353, 1353, 1102, 1, 14500.00, 'XOF', @NOW, @NOW, @NOW),
 (1393, 1071, 1302, NULL, 0,    1101, 1, 1450.00,  'GMD', @NOW, @NOW, @NOW);

-- ── cart_coupons ────────────────────────────────────────────────────────────
-- Same sentinel idea: `vendor_key` 0 means platform-wide. A cart may carry one
-- platform coupon plus one vendor coupon per vendor, which is why this is a
-- row rather than a column on the cart.

INSERT INTO cart_coupons (id, cart_id, coupon_id, vendor_id, vendor_key, applied_at) VALUES
 (1395, 1070, 1080, NULL, 0,    @NOW),
 (1396, 1070, 1081, 1101, 1101, @NOW);

-- ── orders ──────────────────────────────────────────────────────────────────
-- Shipping coordinates are resolved once at checkout and stored, so delivery
-- is priced and driven against the same point even if the buyer later edits
-- the saved address it came from.
--
-- 1401  Oliver, GBP, two vendors. subtotal 179.87 + shipping 4.13 - 17.99
--       discount (TERANGA10, capped) = 166.01 GBP.
-- 1402  Guest, GMD, cash on delivery, unpaid.
-- 1403  Aminata, GMD, single vendor, delivered and paid in person.

INSERT INTO orders
 (id, order_number, customer_id, guest_name, guest_email, guest_phone, guest_session_id,
  status, subtotal, shipping_cost, tax_amount, discount, total, currency,
  coupon_id, coupon_code, payment_status, payment_method, paid_at,
  delivery_mode, pickup_point_id,
  shipping_full_name, shipping_phone, shipping_street, shipping_apartment,
  shipping_city, shipping_state, shipping_postal_code, shipping_country,
  shipping_latitude, shipping_longitude,
  billing_full_name, billing_street, billing_city, billing_state, billing_postal_code, billing_country,
  notes, internal_notes, delivery_instructions, contactless_delivery,
  scheduled_date, scheduled_time_slot,
  loyalty_points_earned, loyalty_points_redeemed, loyalty_discount,
  gift_card_code, gift_card_discount, created_at, updated_at)
VALUES
 (1401, 'SJL-SEED-0001', 1005, NULL, NULL, NULL, NULL,
  'CONFIRMED', 179.87, 4.13, 0.00, 17.99, 166.01, 'GBP',
  1080, 'TERANGA10', 'PAID', 'CARD', @NOW,
  'HOME_DELIVERY', NULL,
  'Oliver Bennett', '+447700900005', '221B Baker Street', 'Flat 2',
  'London', 'Greater London', 'NW1 6XE', 'GB',
  51.52370000, -0.15850000,
  'Oliver Bennett', '221B Baker Street', 'London', 'Greater London', 'NW1 6XE', 'GB',
  'Leave with the porter if out.', 'Two vendors, two settlement currencies.', 'Ring the bell twice.', 0,
  NULL, NULL,
  166, 0, 0.00,
  NULL, 0.00, @NOW, @NOW),

 (1402, 'SJL-SEED-0002', NULL, 'Binta Faal', 'binta.faal@example.gm', '+2203100010', 'c4d9e7a1-2f60-4b13-9a55-7e81d0c3b46f',
  'PENDING', 2900.00, 150.00, 0.00, 0.00, 3050.00, 'GMD',
  NULL, NULL, 'PENDING', 'PAY_ON_DELIVERY', NULL,
  'HOME_DELIVERY', NULL,
  'Binta Faal', '+2203100010', 'Bakau New Town', NULL,
  'Bakau', 'Kanifing', NULL, 'GM',
  13.47810000, -16.68200000,
  NULL, NULL, NULL, NULL, NULL, NULL,
  NULL, 'Guest checkout, no account.', NULL, 0,
  NULL, NULL,
  0, 0, 0.00,
  NULL, 0.00, @NOW, @NOW),

 (1403, 'SJL-SEED-0003', 1004, NULL, NULL, NULL, NULL,
  'DELIVERED', 2500.00, 110.00, 0.00, 0.00, 2610.00, 'GMD',
  NULL, NULL, 'PAID', 'CASH_IN_STORE', @NOW,
  'PICKUP_POINT', 1096,
  'Aminata Ceesay', '+2203100004', 'Westfield Junction', 'Unit 3',
  'Serekunda', 'West Coast', NULL, 'GM',
  13.44290000, -16.67760000,
  NULL, NULL, NULL, NULL, NULL, NULL,
  'Collecting Saturday morning.', NULL, NULL, 0,
  '2026-09-12', '09:00-12:00',
  26, 0, 0.00,
  NULL, 0.00, @NOW, @NOW);

-- ── payments ────────────────────────────────────────────────────────────────
-- One record per order, one settlement path whatever brought the money in.
-- `version` is the optimistic-lock column and must be set explicitly.
-- 1600 is the mock gateway settling inline — no callback ever arrives, so a
-- payment left PENDING here would never settle.

INSERT INTO payments
 (id, order_id, reference, method, status, amount, amount_refunded, currency,
  transaction_id, checkout_url, client_secret, gateway_response, instructions,
  confirmed_by_user_id, collection_reference, failure_reason, note,
  paid_at, refunded_at, cancelled_at, version, created_at, updated_at)
VALUES
 (1600, 1401, 'PAY-SEED0000000', 'CARD', 'PAID', 166.01, 0.00, 'GBP',
  'MOCK-8F2A41C7B9D34E60A5B1', NULL, NULL, '{"gateway":"mock","status":"succeeded"}', NULL,
  NULL, 'MOCK-8F2A41C7B9D34E60A5B1', NULL, 'Settled synchronously by mock',
  @NOW, NULL, NULL, 1, @NOW, @NOW),
 (1601, 1402, 'PAY-SEED0000001', 'PAY_ON_DELIVERY', 'PENDING', 3050.00, 0.00, 'GMD',
  NULL, NULL, NULL, NULL, 'Have 3050.00 ready for the driver in Bakau. Quote reference PAY-SEED0000001.',
  NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, 0, @NOW, @NOW),
 (1602, 1403, 'PAY-SEED0000002', 'CASH_IN_STORE', 'PAID', 2610.00, 0.00, 'GMD',
  NULL, NULL, NULL, NULL, 'Pay 2610.00 at handover. Quote reference PAY-SEED0000002.',
  1008, 'TILL-2026-09-12-0044', NULL, 'Collected at Westfield Junction',
  @NOW, NULL, NULL, 2, @NOW, @NOW);

-- ── vendor_orders ───────────────────────────────────────────────────────────
-- One vendor's slice of an order. Amounts appear twice: `*_native` in the
-- vendor's own settlement currency (what they are actually owed, never
-- converted) and the plain columns in the buyer's display currency.
--
-- commission_native = total_native x commission_rate / 100
-- payout_native     = total_native - commission_native
-- delivery_native   = the slice's delivery, converted at the same rate the
--                     goods were, and deliberately NOT part of the payout:
--                     the platform arranges delivery and keeps it.
--
-- Both slices of 1401 stop at SHIPPED. Nothing can set DELIVERED — a vendor is
-- refused, correctly, and the delivery module that should confirm it has no
-- endpoints. 1503 is DELIVERED only because this file writes it directly.

INSERT INTO vendor_orders
 (id, order_id, vendor_id, status, native_currency,
  subtotal_native, discount_native, total_native,
  commission_rate, commission_native, delivery_native, payout_native,
  subtotal, discount, total, coupon_id, coupon_code, cancelled_at, created_at, updated_at)
VALUES
 (1501, 1401, 1101, 'SHIPPED', 'GMD',
  11000.00, 1100.00, 9900.00,
  10.00, 990.00, 209.09, 8910.00,
  121.00, 12.10, 108.90, NULL, NULL, NULL, @NOW, @NOW),
 (1502, 1401, 1102, 'CONFIRMED', 'XOF',
  14500.00, 1450.00, 13050.00,
  12.50, 1631.25, 1953.13, 11418.75,
  58.87, 5.89, 52.98, NULL, NULL, NULL, @NOW, @NOW),
 (1503, 1402, 1101, 'PENDING', 'GMD',
  2900.00, 0.00, 2900.00,
  10.00, 290.00, 150.00, 2610.00,
  2900.00, 0.00, 2900.00, NULL, NULL, NULL, @NOW, @NOW),
 (1504, 1403, 1101, 'DELIVERED', 'GMD',
  2500.00, 0.00, 2500.00,
  10.00, 250.00, 110.00, 2250.00,
  2500.00, 0.00, 2500.00, NULL, NULL, NULL, @NOW, @NOW);

-- ── order_items ─────────────────────────────────────────────────────────────
-- `unit_price` / `total_price` are in the vendor's listing currency and are
-- never converted in place; the `*_converted` columns hold the same amounts in
-- the order's display currency, frozen at checkout. `delivery_cost` is this
-- one product's own leg, in the display currency — the order's shipping_cost
-- is the sum of these, not a figure computed separately.
--
-- Product name, SKU and image are snapshotted so a later edit, or an
-- unpublish, cannot change what the buyer sees they bought. 1408 points at the
-- withdrawn product 1307 to prove that.

INSERT INTO order_items
 (id, order_id, vendor_order_id, product_id, variant_id, vendor_id, quantity,
  unit_price, total_price, currency, unit_price_converted, total_price_converted,
  delivery_cost, product_name, product_sku, variant_sku, selected_options, product_image_url)
VALUES
 (1405, 1401, 1501, 1301, 1352, 1101, 1,
  9700.00, 9700.00, 'GMD', 106.70, 106.70,
  1.84, 'Samsung Galaxy A16', 'KOM-SGA16', 'KOM-SGA16-256-BLK', 'Storage: 256 GB, Colour: Black',
  'https://media.example.invalid/products/sga16-front.jpg'),
 (1406, 1401, 1501, 1303, NULL, 1101, 1,
  1300.00, 1300.00, 'GMD', 14.30, 14.30,
  0.46, 'Tobaski 1.8L Electric Kettle', 'KOM-KET18', NULL, NULL,
  'https://media.example.invalid/products/kettle.jpg'),
 (1407, 1401, 1502, 1305, 1353, 1102, 1,
  14500.00, 14500.00, 'XOF', 18.56, 18.56,
  1.83, 'Wax Print — Six Yards, Indigo', 'TER-WAX-IND', 'TER-WAX-IND-6Y', 'Length: Six yards',
  'https://media.example.invalid/products/wax-indigo.jpg'),
 (1408, 1402, 1503, 1307, NULL, 1101, 1,
  2900.00, 2900.00, 'GMD', 2900.00, 2900.00,
  150.00, 'Samsung Galaxy A05', 'KOM-SGA05', NULL, NULL,
  NULL),
 (1409, 1403, 1504, 1303, NULL, 1101, 2,
  1250.00, 2500.00, 'GMD', 1250.00, 2500.00,
  110.00, 'Tobaski 1.8L Electric Kettle', 'KOM-KET18', NULL, NULL,
  'https://media.example.invalid/products/kettle.jpg');

-- ── order_status_history ────────────────────────────────────────────────────
-- Append-only trail. `from_status` is null on the first row of each order.

INSERT INTO order_status_history (id, order_id, from_status, to_status, notes, changed_by, changed_at) VALUES
 (1410, 1401, NULL,        'PENDING',   'Order placed',                NULL, @NOW),
 (1411, 1401, 'PENDING',   'CONFIRMED', 'Card payment settled',        NULL, @NOW),
 (1412, 1402, NULL,        'PENDING',   'Guest order placed',          NULL, @NOW),
 (1413, 1403, NULL,        'PENDING',   'Order placed',                NULL, @NOW),
 (1414, 1403, 'PENDING',   'CONFIRMED', 'Paid at the pickup point',    1008, @NOW),
 (1415, 1403, 'CONFIRMED', 'PROCESSING','Vendor packing',              1002, @NOW),
 (1416, 1403, 'PROCESSING','SHIPPED',   'Handed to Westfield Junction',1002, @NOW),
 (1417, 1403, 'SHIPPED',   'DELIVERED', 'Collected by the buyer',      1008, @NOW);

-- ── coupon_usages ───────────────────────────────────────────────────────────
-- One row per redemption, which is what enforces per-user limits.

INSERT INTO coupon_usages (id, coupon_id, user_id, order_id, used_at) VALUES
 (1420, 1080, 1005, 1401, @NOW),
 (1421, 1080, 1004, NULL, @NOW),
 (1422, 1082, 1004, NULL, @NOW);

-- ── deliveries ──────────────────────────────────────────────────────────────
-- Keyed to an order ITEM, not an order: each product in a multivendor basket
-- travels on its own. Seven tables model this module and no service or
-- controller reads any of them, so every row below is written by this file and
-- by nothing else in the system.

INSERT INTO deliveries
 (id, order_item_id, driver_id, pickup_point_id, tracking_number, status,
  recipient_name, recipient_phone, distance_km, earning_amount,
  contactless_requested, notes,
  assigned_at, picked_up_at, arrived_at_pickup_point_at, out_for_delivery_at,
  delivered_at, actual_delivered_at, estimated_delivery_at, estimated_delivery_date,
  delivery_proof_image_url, recipient_signature_url, created_at, updated_at)
VALUES
 (1700, 1409, 1090, 1096, 'SJL-DLV-0000001', 'DELIVERED',
  'Aminata Ceesay', '+2203100004', 1.80, 216.00,
  0, 'Collected at the counter.',
  @NOW, @NOW, @NOW, NULL,
  @NOW, @NOW, @NOW, '2026-09-12',
  'https://media.example.invalid/pod/dlv-0000001.jpg', NULL, @NOW, @NOW),
 (1701, 1405, 1090, NULL, 'SJL-DLV-0000002', 'IN_TRANSIT',
  'Oliver Bennett', '+447700900005', 4812.40, 0.00,
  0, 'International leg, courier handover pending.',
  @NOW, @NOW, NULL, NULL,
  NULL, NULL, '2026-09-19 12:00:00.000000', '2026-09-19',
  NULL, NULL, @NOW, @NOW),
 (1702, 1408, NULL, NULL, 'SJL-DLV-0000003', 'PENDING',
  'Binta Faal', '+2203100010', NULL, NULL,
  0, 'Awaiting driver assignment.',
  NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, NULL,
  NULL, NULL, @NOW, @NOW);

-- ── delivery_tracking ───────────────────────────────────────────────────────

INSERT INTO delivery_tracking (id, delivery_id, status, description, latitude, longitude, recorded_by, recorded_at) VALUES
 (1710, 1700, 'PENDING',          'Order received by the vendor',      13.43830000, -16.67810000, 1002, @NOW),
 (1711, 1700, 'PREPARED',         'Packed and labelled',               13.43830000, -16.67810000, 1002, @NOW),
 (1712, 1700, 'ASSIGNED',         'Assigned to Ebrima Bojang',         13.43830000, -16.67810000, 1001, @NOW),
 (1713, 1700, 'PICKED_UP',        'Collected from the vendor',         13.43830000, -16.67810000, 1007, @NOW),
 (1714, 1700, 'AT_PICKUP_POINT',  'Dropped at Westfield Junction',     13.44290000, -16.67760000, 1007, @NOW),
 (1715, 1700, 'DELIVERED',        'Handed to the buyer, code verified',13.44290000, -16.67760000, 1008, @NOW),
 (1716, 1701, 'ASSIGNED',         'Assigned for the outbound leg',     13.43830000, -16.67810000, 1001, @NOW),
 (1717, 1701, 'IN_TRANSIT',       'With the international courier',    13.43830000, -16.67810000, 1007, @NOW);

-- ── proof_of_delivery ───────────────────────────────────────────────────────

INSERT INTO proof_of_delivery (id, delivery_id, image_url, signature_url, latitude, longitude, notes, submitted_at, created_at) VALUES
 (1720, 1700, 'https://media.example.invalid/pod/dlv-0000001.jpg', NULL,
  13.44290000, -16.67760000, 'Photographed at the counter with the buyer present.', @NOW, @NOW);

-- ── handover_codes ──────────────────────────────────────────────────────────
-- The short code the receiving party reads out. `version` is an optimistic
-- lock, so two people cannot burn the same code concurrently.

INSERT INTO handover_codes (id, delivery_id, code, code_type, used, used_at, used_by_user_id, expires_at, version, created_at) VALUES
 (1730, 1700, '418302', 'VENDOR_TO_DRIVER',    1, @NOW, 1007, '2026-09-12 18:00:00.000000', 1, @NOW),
 (1731, 1700, '905177', 'DRIVER_TO_PICKUP',    1, @NOW, 1008, '2026-09-12 18:00:00.000000', 1, @NOW),
 (1732, 1700, '234861', 'PICKUP_TO_CUSTOMER',  1, @NOW, 1004, '2026-09-12 20:00:00.000000', 1, @NOW),
 (1733, 1701, '660419', 'VENDOR_TO_DRIVER',    1, @NOW, 1007, '2026-09-13 18:00:00.000000', 1, @NOW),
 (1734, 1701, '773025', 'DRIVER_TO_CUSTOMER',  0, NULL, NULL, '2026-09-20 18:00:00.000000', 0, @NOW);

-- ── delivery_routes ─────────────────────────────────────────────────────────
-- A driver's run for one day. delivery_ids and optimized_order are TEXT lists,
-- not foreign keys, so nothing enforces that the ids below exist.

INSERT INTO delivery_routes (id, driver_id, route_date, delivery_ids, optimized_order, total_estimated_distance_km, estimated_duration_minutes, created_at, updated_at) VALUES
 (1740, 1090, '2026-09-12', '1700,1701', '1700,1701', 12.40, 55, @NOW, @NOW);

COMMIT;

-- ── What you now have ───────────────────────────────────────────────────────
--
--   Sign in as any of these, password  Sujula123!
--
--     fatou.admin@sujula.gm          ADMIN
--     lamin.kombo@sujula.gm          VENDOR    Kombo Electronics, settles GMD
--     awa.teranga@sujula.sn          VENDOR    Teranga Textiles, settles XOF
--     aminata.ceesay@example.gm      CUSTOMER  Serekunda, shops in GMD
--     oliver.bennett@example.co.uk   CUSTOMER  London, shops in GBP
--     ebrima.driver@sujula.gm        DELIVERY
--     isatou.pickup@sujula.gm        PICKUP_OPERATOR
--     sulayman.blocked@example.gm    blocked and fraud-flagged — sign-in is refused
--
--   Worth looking at first:
--
--     GET /api/vendor/orders            as Lamin, then as Awa. Same order 1401,
--                                       two slices, each priced only in that
--                                       vendor's own currency. Neither response
--                                       contains GBP, London, or Oliver.
--     GET /api/products/search?q=wax&userLat=51.52&userLng=-0.16&currency=GBP
--                                       location-ranked, converted prices.
--     GET /api/admin/orders/1401        the buyer's side of the same order.
--
--   And one thing you cannot do: move a vendor order to DELIVERED through the
--   API. Order 1403 is delivered only because this file wrote it that way.
-- ============================================================================
