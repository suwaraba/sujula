-- ============================================================================
--  Sujula development seed
-- ============================================================================
--  Populates all 50 tables with one coherent, related dataset. Run it by hand;
--  it is deliberately NOT auto-loaded on startup, because seed data appearing
--  in a database by surprise is worse than typing one command, and this file
--  deletes before it inserts.
--
--  ── How to run it ──────────────────────────────────────────────────────────
--
--  IntelliJ (easiest, and no shell quoting to get wrong)
--      Open this file, pick the sujula data source in the top bar, Ctrl+Enter.
--      Or: Database tool window > right-click the schema > Run SQL Script.
--
--  Windows PowerShell
--      Get-Content src\main\resources\db\seed\dev-seed.sql | mysql -u root -p sujula
--
--      NOT  mysql ... < file.sql  — PowerShell has no input redirection and
--      answers "The '<' operator is reserved for future use."
--
--  Windows cmd.exe
--      mysql -u root -p sujula < src\main\resources\db\seed\dev-seed.sql
--
--  macOS / Linux
--      mysql -u root -p sujula < src/main/resources/db/seed/dev-seed.sql
--
--  ── If it fails on the first DELETE ────────────────────────────────────────
--
--  Every "doesn't exist" and "unknown column" here has the same cause and the
--  same fix: the database is older than the code. Hibernate's ddl-auto adds what
--  is missing, so start the application once on current code, then re-run this.
--
--      "Table 'sujula.user_sessions' doesn't exist"
--      — and the same for mfa_recovery_codes, oauth_accounts,
--      phone_verifications or account_data_requests. The database predates the
--      /auth and /me layers; those five tables are new.
--
--      "Unknown column 'phone_verified' in 'field list'"
--      — the users table predates phone verification.
--
--      "Table 'sujula.delivery_contexts' doesn't exist", or "Unknown column
--      'geocode_confidence'" / "'deleted_at'" / "'shipping_address_id'"
--      — the database predates the addresses and delivery-context layer.
--
--      "Table 'sujula.fx_quotes' doesn't exist"
--      — the database predates held exchange rates.
--
--      "Table 'sujula.product_questions' doesn't exist", or "Unknown column
--      'product_condition'"
--      — the database predates the public catalogue layer.
--
--      "Table 'sujula.cart_quotes' doesn't exist", or "Unknown column
--      'token'" / "'delivery_context_id'" on carts
--      — the database predates addressable carts and held quotes.
--
--      "Table 'sujula.notifications' doesn't exist"
--      — the schema predates the `read` -> `is_read` fix. `read` is reserved in
--      MySQL, so that CREATE TABLE failed, and Hibernate logged it and carried
--      on rather than stopping.
--
--  To see what is actually there:
--
--      SELECT table_name FROM information_schema.tables
--       WHERE table_schema = 'sujula' ORDER BY table_name;   -- expect 50
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
--      12xx  catalogue        15xx  vendor orders     18xx  sessions, MFA,
--                                                           linked accounts,
--                                                           data requests
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
--  ── And what the accounts are in the middle of ─────────────────────────────
--
--  The auth tables are seeded with state rather than with placeholders, because
--  half of what /auth and /me do is only observable when something is already
--  in progress:
--
--      Aminata   two live devices, an authenticator enrolled, three recovery
--                codes of which one is already spent, phone proved
--      Oliver    signs in with both Google and Apple, no password he has ever
--                chosen, no phone — and one session that has already rotated
--                its refresh token, so the spent one is a demonstrable replay
--      Modou     mid-verification: neither email nor phone proved yet, and a
--                live phone challenge waiting for its code
--      Sulayman  blocked, five failed verification attempts behind him, and an
--                erasure request in flight
--
--  All passwords are  Sujula123!  (a real BCrypt hash, verified against the
--  application's own encoder). The refresh tokens, recovery codes and phone
--  codes seeded below are equally real — the hashes stored are the hashes the
--  application itself would compute — and the values are printed in the notes
--  at the end of this file.
-- ============================================================================

SET NAMES utf8mb4;
SET @PW = '$2a$10$nRluET0D32C7aC1ANGfIZ.yyg2UmvHcZTSm2az36pyqS7sHvXcdmm';
SET @NOW = '2026-09-12 09:00:00.000000';

-- Two relative clocks, for the rows whose whole point is that they are still
-- open when you run this. A fixed timestamp is right for history — an order was
-- placed when it was placed — but wrong for anything with a deadline: a phone
-- challenge seeded to expire ten minutes after a date in the file is expired by
-- the time anyone runs the seed, and the code printed in the notes below would
-- be refused rather than accepted. These two are the difference between data
-- that reads correctly and data that works.
--
-- Standard interval syntax, valid in both MySQL and H2.
--   @SOON    an open challenge, still answerable
--   @FUTURE  a live session, and a download link that has not lapsed
--   @LAPSED  a session past its refresh window, which must be refused
--   @TOMORROW / @TOMORROW_LATE  a delivery window a recipient asked for, which
--                the reschedule rule must still accept as being in the future
SET @SOON   = CURRENT_TIMESTAMP + INTERVAL '30' MINUTE;
SET @FUTURE = CURRENT_TIMESTAMP + INTERVAL '30' DAY;
SET @LAPSED = CURRENT_TIMESTAMP - INTERVAL '7' DAY;
SET @TOMORROW      = CURRENT_TIMESTAMP + INTERVAL '1' DAY;
SET @TOMORROW_LATE = CURRENT_TIMESTAMP + INTERVAL '1' DAY + INTERVAL '4' HOUR;

START TRANSACTION;

-- ── Clear previous seed ─────────────────────────────────────────────────────
-- Reverse foreign-key order. No FOREIGN_KEY_CHECKS=0 anywhere: if this order
-- is wrong the database says so, which is the point.
--
-- Every table this file inserts into is cleared here. That is not tidiness: a
-- table that is inserted into and never cleared makes the file run exactly once
-- and then fail on a duplicate key, which is the opposite of what it is for.
DELETE FROM catalogue_job_errors    WHERE job_id >= 1000;
DELETE FROM catalogue_jobs          WHERE id >= 1000;
DELETE FROM promotion_categories    WHERE promotion_id >= 1000;
DELETE FROM promotion_products      WHERE promotion_id >= 1000;
DELETE FROM promotions              WHERE id >= 1000;
DELETE FROM store_staff_permissions WHERE staff_id >= 1000;
DELETE FROM store_staff             WHERE id >= 1000;
DELETE FROM store_operating_hours   WHERE id >= 1000;
DELETE FROM kyc_documents           WHERE id >= 1000;
DELETE FROM product_translations    WHERE id >= 1000;
-- refund_requests references both an order and a vendor_order, so it clears
-- before either of them.
-- The ledger references vendor_orders, payouts and users, so it clears before
-- any of them.
-- Custody events reference shipments and legs; legs reference shipments and
-- drivers; handover codes now reference legs as well as deliveries. Children
-- first, as everywhere else here.
-- After-sales. Threads and messages reference orders and products; disputes
-- reference vendor orders and returns, and returns reference disputes back, so
-- the link is cleared before either row goes.
UPDATE return_requests SET dispute_id = NULL WHERE id >= 1000;
DELETE FROM thread_messages         WHERE id >= 1000;
DELETE FROM message_threads         WHERE id >= 1000;
DELETE FROM review_reports          WHERE id >= 1000;
DELETE FROM dispute_evidence        WHERE id >= 1000;
DELETE FROM dispute_messages        WHERE id >= 1000;
DELETE FROM return_lines            WHERE id >= 1000;
DELETE FROM disputes                WHERE id >= 1000;
DELETE FROM return_requests         WHERE id >= 1000;
DELETE FROM handover_codes          WHERE id >= 1000;
DELETE FROM custody_events          WHERE id >= 1000;
DELETE FROM recipient_instructions  WHERE id >= 1000;
DELETE FROM parcel_access_codes     WHERE id >= 1000;
DELETE FROM shipment_legs           WHERE id >= 1000;
DELETE FROM shipments               WHERE id >= 1000;
DELETE FROM vendor_ledger_entries   WHERE id >= 1000;
DELETE FROM product_view_stats      WHERE id >= 1000;
DELETE FROM refund_requests         WHERE id >= 1000;
-- imei_units references an order_item; stock_movements reference products and
-- variants. Both clear before those do.
DELETE FROM imei_units              WHERE id >= 1000;
DELETE FROM stock_movements         WHERE id >= 1000;

DELETE FROM cart_quote_lines       WHERE id >= 1000;
DELETE FROM cart_quotes            WHERE id LIKE 'seed-%';
DELETE FROM product_questions      WHERE id >= 1000;
DELETE FROM fx_quotes              WHERE id LIKE 'seed-%';
DELETE FROM idempotency_records    WHERE id >= 1000;
DELETE FROM delivery_contexts      WHERE id LIKE 'seed-%';
DELETE FROM account_data_requests  WHERE id >= 1000;
DELETE FROM phone_verifications    WHERE id >= 1000;
DELETE FROM oauth_accounts         WHERE id >= 1000;
DELETE FROM mfa_recovery_codes     WHERE id >= 1000;
DELETE FROM user_sessions          WHERE id >= 1000;
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
DELETE FROM push_devices           WHERE id >= 1000;
DELETE FROM notification_preferences WHERE id >= 1000;
DELETE FROM notifications          WHERE id >= 1000;
-- Coverage before drivers and before zones, because it is the join between
-- them and holds a foreign key into each. A rate card before its zone for the
-- same reason.
DELETE FROM driver_zone_coverage   WHERE driver_id >= 1000 OR zone_id >= 1000;
DELETE FROM drivers                WHERE id >= 1000;
DELETE FROM delivery_rate_cards    WHERE id >= 1000;
DELETE FROM delivery_zones         WHERE id >= 1000;
DELETE FROM coupons                WHERE id >= 1000;
DELETE FROM carts                  WHERE id >= 1000;
UPDATE sanctions        SET moderation_case_id = NULL WHERE id >= 1000;
UPDATE moderation_cases SET sanction_id = NULL         WHERE id >= 1000;
DELETE FROM sanctions              WHERE id >= 1000;
DELETE FROM moderation_cases       WHERE id >= 1000;
DELETE FROM audit_logs             WHERE id >= 1000;
DELETE FROM addresses              WHERE id >= 1000;
DELETE FROM wishlists              WHERE id >= 1000;
-- Payouts now carry a vendor, so they go before vendors do. They used to sit
-- after, which was correct only while the column did not exist.
DELETE FROM vendor_payouts         WHERE id >= 1000;
-- Batches after the payouts that point at them, and exports and spreads
-- wherever: nothing references either.
DELETE FROM payout_batches         WHERE id >= 1000;
DELETE FROM report_exports         WHERE id >= 1000;
DELETE FROM fx_spreads             WHERE id >= 1000;
-- Callbacks before the disputes they point at; announcements, flags and job
-- runs reference nothing.
DELETE FROM callback_requests      WHERE id >= 1000;
DELETE FROM announcements          WHERE id >= 1000;
DELETE FROM feature_flags          WHERE id >= 1000;
DELETE FROM job_runs               WHERE id >= 1000;
DELETE FROM webhook_events         WHERE id >= 1000;
DELETE FROM vendors                WHERE id >= 1000;
DELETE FROM users                  WHERE id >= 1000;
DELETE FROM gift_cards             WHERE id >= 1000;
DELETE FROM exchange_rates         WHERE id >= 1000;
-- categories references itself, so the children go before the parents. One
-- flat delete is refused, and rightly - a parent row is still referenced at the
-- moment it is removed.
DELETE FROM categories             WHERE id >= 1000 AND parent_id IS NOT NULL;
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
  enabled, email_verified, phone_verified, blocked, fraud,
  totp_enabled, totp_verified, totp_secret,
  failed_login_attempts, preferred_currency, preferred_language, detected_country_code,
  created_at, updated_at)
VALUES
 (1001, 'Fatou',  'Jallow',  'fatou.admin@sujula.gm',      @PW, '+2203100001', 'ADMIN',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1002, 'Lamin',  'Touray',  'lamin.kombo@sujula.gm',      @PW, '+2203100002', 'VENDOR',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1003, 'Awa',    'Diallo',  'awa.teranga@sujula.sn',      @PW, '+2217700003', 'VENDOR',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'XOF', 'fr', 'SN', @NOW, @NOW),
 (1004, 'Aminata','Ceesay',  'aminata.ceesay@example.gm',  @PW, '+2203100004', 'CUSTOMER',
  1, 1, 1, 0, 0, 1, 1, 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1005, 'Oliver', 'Bennett', 'oliver.bennett@example.co.uk',@PW,'+447700900005','CUSTOMER',
  1, 1, 0, 0, 0, 0, 0, NULL, 0, 'GBP', 'en', 'GB', @NOW, @NOW),
 (1006, 'Modou',  'Sanneh',  'modou.sanneh@example.gm',    @PW, '+2203100006', 'CUSTOMER',
  1, 0, 0, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1007, 'Ebrima', 'Bojang',  'ebrima.driver@sujula.gm',    @PW, '+2203100007', 'DELIVERY',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1008, 'Isatou', 'Camara',  'isatou.pickup@sujula.gm',    @PW, '+2203100008', 'PICKUP_OPERATOR',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1009, 'Sulayman','Gomez',  'sulayman.blocked@example.gm',@PW, '+2203100009', 'CUSTOMER',
  1, 1, 0, 1, 1, 0, 0, NULL, 3, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1010, 'Mariama','Jarju',   'mariama.jarju@example.gm',   @PW, '+2203100010', 'VENDOR',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 (1011, 'Ndeye',  'Sarr',    'ndeye.sarr@example.sn',      @PW, '+2217700011', 'CUSTOMER',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'XOF', 'fr', 'SN', @NOW, @NOW),
 -- Support, not an administrator, and the difference is the point. 1012 reads
 -- every queue on /admin and can decide nothing on it: no suspension, no role
 -- change, no impersonation, no refund. Written as a role rather than as a
 -- permission on ADMIN so the default for a new member of staff is the safe
 -- one — a system where the safe option has to be remembered is a system where
 -- everybody ends up an administrator.
 (1012, 'Binta',  'Njie',    'binta.support@sujula.gm',    @PW, '+2203100012', 'SUPPORT',
  1, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW),
 -- Locked out, and NOT by a flag. users.enabled is 0 here because sanction
 -- 2200 below says so; SanctionRegistry recomputes the column from the rows,
 -- and nothing else in the codebase can set it. An account that cannot sign in
 -- always has a row saying who decided that and why — which is what somebody
 -- asks for when it is their livelihood.
 (1013, 'Alieu',  'Manneh',  'alieu.suspended@example.gm', @PW, '+2203100013', 'VENDOR',
  0, 1, 1, 0, 0, 0, 0, NULL, 0, 'GMD', 'en', 'GM', @NOW, @NOW);

-- ── vendors ─────────────────────────────────────────────────────────────────
-- Coordinates matter: they are the despatch origin every delivery leg is
-- measured from. Without them pricing falls back to a flat per-scope distance.
-- The two settlement currencies are the point of this pair.

-- Three stores, one per rung of the onboarding ladder: APPROVED and trading,
-- APPROVED with documents that came back refused, and PENDING_KYC with nothing
-- sent yet.
--
-- `settlement_currency` and `address_country_code` are the two independent
-- answers on this table, and 1103 is the row that proves they are independent:
-- Mariama's shop stands in Dakar and settles in GMD, because she banks in
-- Banjul. A system that read the currency off the address would have
-- re-denominated her whole business without telling her.
--
-- `geocode_confidence` is what delivery prices from. 1103 is NONE — a market
-- stall with no street number that no geocoder knows — and the store still
-- exists, because most of the region looks like that and refusing would shut
-- out the sellers this marketplace is mainly for. Until she drops a pin, a
-- collection from her is priced from a scope fallback rather than a distance.

INSERT INTO vendors
 (id, user_id, store_name, store_slug, description, store_email, store_phone, website,
  address_street, address_city, address_state, address_postal_code, address_country_code,
  latitude, longitude, geocode_confidence, geocoded_at,
  pickup_street, pickup_city, pickup_state, pickup_postal_code, pickup_country_code,
  pickup_latitude, pickup_longitude, pickup_geocode_confidence, pickup_instructions,
  settlement_currency, status, default_commission_rate,
  balance, rating, total_reviews, total_sold, business_registration_number, tax_number,
  return_policy, shipping_policy, store_policy, handling_days,
  vacation_mode, vacation_message,
  logo_url, banner_url, created_at, updated_at)
VALUES
 (1101, 1002, 'Kombo Electronics', 'kombo-electronics',
  'Phones, speakers and kettles on Kairaba Avenue since 2014.',
  'shop@kombo.gm', '+2204380001', NULL,
  '14 Kairaba Avenue', 'Serekunda', 'West Coast', NULL, 'GM',
  13.43830000, -16.67810000, 'EXACT', @NOW,
  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
  'GMD', 'APPROVED', 10.00,
  810.00, 4.60, 2, 14, 'GM-RC-884120', 'GM-TIN-55231',
  'Fourteen days for anything unopened. Phones with a broken seal are exchange only.',
  'Collected from the shop on Kairaba Avenue. We do not post.',
  NULL, 1,
  0, NULL,
  NULL, NULL, @NOW, @NOW),
 (1102, 1003, 'Teranga Textiles', 'teranga-textiles',
  'Wax prints and damask, cut to six yards. Ships from Ziguinchor.',
  'bonjour@teranga.sn', '+2213390002', NULL,
  '8 Rue de France', 'Ziguinchor', 'Ziguinchor', '27000', 'SN',
  12.56410000, -16.27190000, 'CENTROID', @NOW,
  -- Cloth is cut at the shop and stored in a depot two streets away, which is
  -- where a driver actually goes. Pricing a collection from the shop front
  -- would be wrong on every order they take.
  'Entrepôt Boucotte, Rue 14', 'Ziguinchor', 'Ziguinchor', '27000', 'SN',
  12.55980000, -16.27540000, 'APPROXIMATE',
  'Blue gate behind the mosque. Ask for Awa.',
  'XOF', 'APPROVED', 12.50,
  96000.00, 4.80, 1, 6, 'SN-RC-220914', NULL,
  'Cut cloth cannot be returned. Faults reported within 48 hours are replaced.',
  NULL, 'Six yards minimum on wax prints.', 3,
  0, NULL,
  NULL, NULL, @NOW, @NOW),
 (1103, 1010, 'Jarju Provisions', 'jarju-provisions',
  'Rice, oil and tinned goods, wholesale and retail.',
  NULL, '+2203100010', NULL,
  'Étal 214, Marché Sandaga', 'Dakar', 'Dakar', NULL, 'SN',
  NULL, NULL, 'NONE', NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
  'GMD', 'PENDING_KYC', 10.00,
  0.00, 0.00, 0, 0, NULL, NULL,
  NULL, NULL, NULL, 2,
  0, NULL,
  NULL, NULL, @NOW, @NOW);

-- ── wishlists ───────────────────────────────────────────────────────────────

INSERT INTO wishlists (id, user_id, created_at) VALUES
 (1040, 1004, @NOW),
 (1041, 1005, @NOW);

-- ── addresses ───────────────────────────────────────────────────────────────
-- `latitude`/`longitude` are set here as if the geocoder had placed them, and
-- `geocode_confidence` records how much each pin is worth — which is the whole
-- point of the column. A geocoder always returns coordinates; what varies is
-- whether they are the building or the middle of the town, and delivery is
-- priced from the distance between them.
--
-- All four states are represented, because the client behaves differently for
-- each:
--
--   USER_CONFIRMED  the resident moved the pin themselves. Outranks anything a
--                   geocoder says later, and an edit will not move it.
--   EXACT           the building. Dispatchable as it stands.
--   CENTROID        the right area, not the right door — needsPinConfirmation
--   NONE            never placed at all. A legitimate state: most of the
--                   country has no street numbering, and refusing to save an
--                   address nobody can find would make the book useless for
--                   the buyers it is mainly for. Delivery prices from a scope
--                   fallback until someone drops a pin.

INSERT INTO addresses
 (id, user_id, label, full_name, phone, street, apartment_suite, city, state,
  postal_code, country_code, latitude, longitude,
  geocode_confidence, geocoded_at, pin_confirmed_at, deleted_at,
  is_default, created_at)
VALUES
 -- Aminata moved the pin onto her own compound. Nothing the geocoder returns
 -- later will move it back.
 (1050, 1004, 'Home',   'Aminata Ceesay', '+2203100004', '27 Sayerr Jobe Avenue', NULL,       'Serekunda', 'West Coast', NULL,     'GM', 13.43950000, -16.67520000,
  'USER_CONFIRMED', @NOW, @NOW, NULL, 1, @NOW),

 -- A market stall the geocoder placed in the right block but not at the right
 -- unit. needsPinConfirmation comes back true, so a client shows a map.
 (1051, 1004, 'Shop',   'Aminata Ceesay', '+2203100004', 'Latrikunda Market',     'Stall 14', 'Serekunda', 'West Coast', NULL,     'GM', 13.42880000, -16.66900000,
  'CENTROID', @NOW, NULL, NULL, 0, @NOW),

 -- London: street-numbered, fully mapped, resolved to the building.
 (1052, 1005, 'Home',   'Oliver Bennett', '+447700900005','221B Baker Street',    'Flat 2',   'London',    'Greater London','NW1 6XE','GB', 51.52370000, -0.15850000,
  'EXACT', @NOW, NULL, NULL, 1, @NOW),

 -- Off the highway, on no street map. Saved anyway, and priced from a scope
 -- fallback until Modou drops a pin — which is what confirm-pin is for.
 (1053, 1006, 'Home',   'Modou Sanneh',   '+2203100006', 'Off Brikama Highway',   NULL,       'Brikama',   'West Coast', NULL,     'GM', NULL,          NULL,
  'NONE', NULL, NULL, NULL, 1, @NOW),

 -- A tombstone: deleted by its owner, kept because order 1403 was placed
 -- against it. It is gone from the address book — GET /me/addresses does not
 -- list it and GET /me/addresses/1054 is a 404 — and the order it belongs to
 -- still resolves.
 (1054, 1004, 'Old flat','Aminata Ceesay', '+2203100004', '4 Kotu Stream Road',   NULL,       'Kotu',      'West Coast', NULL,     'GM', 13.45100000, -16.70200000,
  'EXACT', @NOW, NULL, @NOW, 0, '2026-06-01 09:00:00.000000');

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

-- `token` is how /carts/{token} addresses a basket, and for a guest it is the
-- whole of their claim to it: a cart holds a destination, a list of what
-- somebody is buying and for whom, and what they are about to spend. Real ones
-- are 256 bits of base64url; the readable ones here exist so the DELETE block
-- can find them.
--
-- `delivery_context_id` is where the goods go, and it is deliberately separate
-- from `display_currency`. Cart 1071 is the case this marketplace exists for:
-- priced in sterling because the payer holds a UK card, delivering to
-- Serrekunda because that is where the parcel is going. Neither field may be
-- derived from the other.

INSERT INTO carts (id, user_id, session_id, token, display_currency, delivery_context_id,
                   expires_at, created_at, updated_at) VALUES
 (1070, 1004, NULL, 'seed-cart-aminata', 'GMD', 'seed-ctx-aminata-home',
  NULL, @NOW, @NOW),
 (1071, NULL, 'a7f3c1e2-9b84-4d55-8e10-2c6f7b0d91aa', 'seed-cart-guest-gbp', 'GBP',
  'seed-ctx-guest-serrekunda', '2026-09-19 09:00:00.000000', @NOW, @NOW);

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

-- A driver holds goods worth more than they earn in a month and turns up at
-- buyers' families' homes, which is why an application is reviewed rather than
-- accepted. The KYC columns are what somebody reviews.
--
-- acceptance_score is derived from the offer counters beside it rather than
-- nudged: 41 of 46 is 89.13%, and recomputing from the counts gives the same
-- number back. A score that could drift from the offers behind it would be a
-- number a driver is judged by and nobody can check.
--
-- online_since is null and available is 1, which is the ordinary state of a row
-- written by a seed rather than by somebody tapping "go online".

INSERT INTO drivers
 (id, user_id, phone, country_code, zone, license_number, vehicle_type, vehicle_model,
  vehicle_plate, vehicle_color, max_weight, available, status, commission_rate,
  current_latitude, current_longitude, last_location_at, total_deliveries,
  total_earnings, total_ratings, average_rating, avatar_url, admin_note,
  id_document_number, id_document_type, id_document_url, license_document_url,
  license_expires_on, next_of_kin_name, next_of_kin_phone,
  kyc_submitted_at, kyc_reviewed_at, kyc_rejection_reason,
  acceptance_score, offers_received, offers_accepted, offers_declined,
  online_since, created_at, updated_at)
VALUES
 (1090, 1007, '+2203100007', 'GM', 'Kombo North', 'GM-DL-771204', 'MOTOR', 'Haojue HJ125',
  'BJL 4417 C', 'Red', 25, 1, 'APPROVED', 12.00,
  13.44120000, -16.67030000, @NOW, 37,
  9250.00, 31, 4.70, NULL, NULL,
  'GM-ID-4471203', 'NATIONAL_ID', 'https://media.example.invalid/kyc/drv-1090-id.jpg',
  'https://media.example.invalid/kyc/drv-1090-licence.jpg',
  '2028-03-31', 'Mariama Touray', '+2203100017',
  '2026-08-02 09:15:00.000000', '2026-08-03 11:40:00.000000', NULL,
  89.13, 46, 41, 5,
  NULL, @NOW, @NOW);

-- ── notifications ───────────────────────────────────────────────────────────
-- The column is `is_read`, not `read`: `read` is reserved in MySQL and the
-- table would not create.

-- `type` is an enum now rather than free text. It used to hold whatever the
-- calling code typed — 'ORDER' here, 'VENDOR' there, 'PAYMENT' somewhere else —
-- which was harmless until preferences arrived: a user cannot hold an opinion
-- about a category that half the code spells differently. The column keeps its
-- name so existing rows keep working.
--
-- sent_on records which channels each one actually went out on, which is not
-- the same question as what the user's settings say today. "Did he get the
-- email" is the first thing support asks, and answering it from current
-- preferences would be answering something else.

INSERT INTO notifications (id, user_id, title, message, type, reference_id, sent_on, is_read, created_at) VALUES
 (1091, 1005, 'Order confirmed',  'Order SJL-SEED-0001 has been confirmed.',           'ORDER_PLACED',     'SJL-SEED-0001', 'IN_APP,EMAIL', 1, @NOW),
 (1092, 1005, 'Payment received', 'We received your card payment for SJL-SEED-0001.',  'ORDER_UPDATE',     'SJL-SEED-0001', 'IN_APP',       0, @NOW),
 -- The seller's copy is a different event from the buyer's: one of them wants
 -- to know their money went, the other has something to pack, and they are
 -- switched on and off separately.
 (1093, 1002, 'New order',        'You have a new order to prepare.',                  'ORDER_TO_FULFIL',  'SJL-SEED-0001', 'IN_APP,EMAIL', 0, @NOW),
 (1094, 1003, 'New order',        'You have a new order to prepare.',                  'ORDER_TO_FULFIL',  'SJL-SEED-0001', 'IN_APP,EMAIL', 0, @NOW),
 (1095, 1004, 'Order delivered',  'Order SJL-SEED-0003 has been delivered. Enjoy!',    'PARCEL_DELIVERED', 'SJL-SEED-0003', 'IN_APP,EMAIL', 1, @NOW),
 -- Sent even though Lamin has switched his inbox off for it (2100 below). It is
 -- one of the five that cannot be suppressed: money held still on a dispute is
 -- something he would otherwise discover from his balance.
 (1096, 1002, 'A dispute was raised', 'Money on SJL-SEED-0001 is held while we look at it.',
  'DISPUTE_UPDATE', 'DSP-9MRT4XKQ2B', 'IN_APP,EMAIL', 0, @NOW),
 -- Nobody has push. sent_on says IN_APP alone rather than claiming a channel
 -- that reached nothing, because no provider is configured in this
 -- installation and LoggedPushSender reports that honestly.
 (1097, 1005, 'Out for delivery', 'Your parcel is out for delivery today.',
  'PARCEL_OUT_FOR_DELIVERY', 'PARCQ4T8NHRW6JZY', 'IN_APP', 0, @NOW);

-- ── notification_preferences ────────────────────────────────────────────────
-- Only the answers that differ from the default are stored, which is why there
-- are four rows here and not four hundred. A user with no rows has no opinion
-- and gets whatever NotificationEvent says — so the absence of a row is
-- meaningful rather than missing, and a default nobody had a view about can be
-- improved later without rewriting anybody's settings.
--
-- Note what is NOT here and could not be: nothing switching off PARCEL_CODE,
-- DISPUTE_UPDATE, PAYOUT_FAILED, SECURITY_ALERT or REFUND_ISSUED. The service
-- refuses to store those, and the resolver would ignore them if they somehow
-- existed — each one is a case where silence costs somebody something they
-- cannot get back.

INSERT INTO notification_preferences
 (id, user_id, event, channel, enabled, created_at, updated_at)
VALUES
 -- Lamin runs a shop and does not want his inbox full of delivery steps.
 (2100, 1002, 'PARCEL_COLLECTED',    'IN_APP', FALSE, @NOW, @NOW),
 (2101, 1002, 'PARCEL_OUT_FOR_DELIVERY', 'EMAIL', FALSE, @NOW, @NOW),
 -- Oliver wants the offers everybody else has off by default.
 (2102, 1005, 'PROMOTION',           'EMAIL',  TRUE,  @NOW, @NOW),
 -- Aminata reads everything in the app and wants no email about messages.
 (2103, 1004, 'MESSAGE_RECEIVED',    'EMAIL',  FALSE, @NOW, @NOW);

-- ── push_devices ────────────────────────────────────────────────────────────
-- Handsets that have asked to be told things. The token is the address: anybody
-- holding one can send that phone a message dressed as ours, so no endpoint
-- ever returns it and it is not logged.
--
-- 2111 is revoked, and the reason matters. Operating systems reassign push
-- tokens: the same token turning up under a second account means the phone
-- changed hands or the app was reinstalled by somebody else. Leaving the old
-- registration live would send the previous owner's delivery codes to whoever
-- has it now, which on this platform is a parcel handed to a stranger.

INSERT INTO push_devices
 (id, user_id, token, platform, label, last_seen_at, revoked_at, revoked_reason, created_at)
VALUES
 (2110, 1004, 'fcm-seed-aminata-infinix-x669c', 'ANDROID', 'Aminata''s Infinix',
  @NOW, NULL, NULL, @NOW),
 (2111, 1005, 'fcm-seed-reassigned-handset',    'ANDROID', 'Old Android',
  @LAPSED, @NOW, 'Token re-registered by another account', @NOW),
 (2112, 1007, 'fcm-seed-ebrima-driver-phone',   'ANDROID', 'Ebrima''s phone',
  @NOW, NULL, NULL, @NOW);

-- ── pickup_points ───────────────────────────────────────────────────────────
-- An alternative to home delivery: a shorter, cheaper leg to a hub the buyer
-- collects from. Priced against the hub, not the buyer's address.
--
-- postal_code is NOT NULL on this table while addresses.postal_code is
-- nullable, which is inconsistent and wrong for this market: The Gambia has no
-- postal code system. Seeded as an empty string because that is the truth, but
-- the column should be nullable.

-- A counter here is a shop with a back room, not a depot. capacity is a real
-- physical limit and stored_parcels is RECOUNTED from the parcels actually held
-- rather than nudged up and down - a number that drifts drifts into accepting
-- parcels there is no room for.
--
-- postal_code is NULL rather than ''. It used to be NOT NULL, which forced every
-- Gambian counter to store an empty string to satisfy a constraint no real row
-- could meet; the empty string in this file was the tell. Latitude and longitude
-- carry the weight instead, which is why an application requires them and not a
-- postcode: the position is what a driver navigates to and what exists here.
--
-- 1097 is closed for the week. It is deliberately in the data because the public
-- search has to NOT return it: sending a shopper to a shuttered counter is worse
-- than showing them nothing. Its status is APPROVED and active is 1 - being shut
-- for a funeral is not the same as being suspended, and all three are separate
-- questions.

INSERT INTO pickup_points
 (id, operator_user_id, name, address_street, address_apartment, city, state, postal_code,
  country_code, latitude, longitude, contact_phone, contact_email, manager_name,
  opening_hours, active, status, total_transactions, monthly_deliveries, total_earnings,
  capacity, stored_parcels, storage_days, commission_per_parcel, commission_currency,
  closed_until, closure_reason,
  profile_image_url, admin_note, created_at, updated_at)
VALUES
 (1096, 1008, 'Westfield Junction Pickup', 'Westfield Junction', 'Unit 3', 'Serekunda', 'West Coast', NULL,
  'GM', 13.44290000, -16.67760000, '+2203100008', 'isatou.pickup@sujula.gm', 'Isatou Camara',
  'Mon-Sat 08:00-20:00', 1, 'APPROVED', 214, 46, 18400.00,
  40, 1, 7, 25.00, 'GMD',
  NULL, NULL,
  NULL, NULL, @NOW, @NOW),

 (1097, 1008, 'Latrikunda Sabiji Counter', 'Sabiji Road', NULL, 'Latrikunda', 'Kanifing', NULL,
  'GM', 13.41200000, -16.68900000, '+2203100018', 'latrikunda@sujula.gm', 'Isatou Camara',
  'Mon-Fri 09:00-18:00', 1, 'APPROVED', 31, 4, 2100.00,
  25, 0, 5, 25.00, 'GMD',
  @FUTURE, 'Closed for a family funeral. Back next week.',
  NULL, NULL, @NOW, @NOW);

-- ── products ────────────────────────────────────────────────────────────────
-- product_condition, not condition: CONDITION is a reserved word in MySQL. H2
-- does not reserve it, so the obvious column name would have passed every test
-- here and failed on the real database — the same asymmetry that hid
-- notifications.read.
--
-- 1304 is OPEN_BOX and 1307 REFURBISHED so the condition filter and its facet
-- have more than one value to return. On a marketplace where a phone costs a
-- month's income, and where the buyer is choosing for someone else and cannot
-- inspect it, that distinction is most of the buying decision.
-- `price_currency` and `country` are derived from the vendor at save time by
-- the application, never taken from a request — a seller cannot list in a
-- currency they do not settle in. Seeded consistently with that rule.
--
-- `weight_kg` and coordinates are what delivery pricing measures. A product
-- with neither is billed on a flat per-scope fallback, which 1306 demonstrates.

--
-- `status` is the authority and `active` is a mirror of it: active is true if
-- and only if status is PUBLISHED. Every public catalogue query filters on
-- active, and ProductLifecycle is the only thing allowed to write it. Two
-- fields describing one fact drift the moment something sets one without the
-- other, and the way they drift here is a listing moderation pulled that keeps
-- on selling. The verifier checks the pair on every row.
--
-- `approved_content_hash` is a digest of the fields a moderator actually looked
-- at - the name, the text, the price, the category, the brand, the condition -
-- taken when approval was granted. Editing any of them stops matching, and the
-- listing goes back into the queue and comes off sale; restocking does not.

INSERT INTO products
 (id, vendor_id, category_id, brand_id, name, slug, short_description, description,
  sku, price, compare_at_price, price_currency, stock, low_stock_threshold,
  allow_backorder, active, featured, weight_kg, dimensions, country, delivery_scope,
  product_condition,
  latitude, longitude, rating, total_reviews, total_sold, score, last_restocked_at,
  status, submitted_for_review_at, reviewed_by_user_id, reviewed_at, rejection_reason,
  published_at, unpublished_at, archived_at, approved_content_hash,
  created_at, updated_at)
VALUES
 (1301, 1101, 1213, 1201, 'Samsung Galaxy A16', 'samsung-galaxy-a16',
  '6.7-inch screen, 5000mAh battery', 'Dual SIM, expandable storage, two-year local warranty.',
  'KOM-SGA16', 8500.00, 9750.00, 'GMD', 12, 3, 0, 1, 1, 0.195, '165x77x8 mm', 'GM', 'NATIONAL',
  'NEW',
  13.43830000, -16.67810000, 4.50, 2, 9, 87, @NOW,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, '8dd9093b17919f57a877c6aea14587792b21ab9c221af890eb4da1d03b02c78a', @NOW, @NOW),
 (1302, 1101, 1213, 1202, 'Nokia 110 4G', 'nokia-110-4g',
  'Feature phone, torch, month-long standby', 'Keypad handset with FM radio and a removable battery.',
  'KOM-N110', 1450.00, NULL, 'GMD', 40, 10, 1, 1, 0, 0.085, '121x50x14 mm', 'GM', 'NATIONAL',
  'NEW',
  13.43830000, -16.67810000, 4.20, 1, 3, 61, @NOW,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, 'b183bd82c74b77b022c49f51545bb922fd54f10a25d267c81fbd08dc7d441c89', @NOW, @NOW),
 (1303, 1101, 1215, 1203, 'Tobaski 1.8L Electric Kettle', 'tobaski-electric-kettle',
  'Stainless steel, auto shut-off', 'Boils 1.8 litres in four minutes. 240V.',
  'KOM-KET18', 1250.00, 1600.00, 'GMD', 22, 5, 0, 1, 1, 1.240, '220x160x240 mm', 'GM', 'REGIIONAL',
  'NEW',
  13.43830000, -16.67810000, 4.70, 1, 2, 74, @NOW,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, '1e7e84fa14b79342223a8825a105b5cd04934b6b1a338867a8b3e9e06c58afbd', @NOW, @NOW),
 (1304, 1101, 1214, NULL, 'Kombo Bluetooth Speaker', 'kombo-bluetooth-speaker',
  'Ten hours of playback', 'Splash-resistant, USB-C charging, carry strap.',
  'KOM-SPK10', 2100.00, NULL, 'GMD', 0, 4, 0, 1, 0, 0.540, '180x75x75 mm', 'GM', 'REGIIONAL',
  'OPEN_BOX',
  13.43830000, -16.67810000, NULL, 0, 0, 40, NULL,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, '744485b67efa3c76c099715866d146a5e15831fad3f15da4c0d0b0771773ffd6', @NOW, @NOW),
 (1305, 1102, 1216, NULL, 'Wax Print — Six Yards, Indigo', 'wax-print-six-yards-indigo',
  'Hand-finished cotton, six-yard piece', 'Printed in Ziguinchor. Colour holds through cold washing.',
  'TER-WAX-IND', 14500.00, NULL, 'XOF', 18, 4, 0, 1, 1, 0.850, '6 yards', 'SN', 'GLOBAL',
  'NEW',
  12.56410000, -16.27190000, 4.80, 1, 6, 80, @NOW,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, 'a33708187bd7ff9dfd6285fd592a56cb3cc76a5400c1faba8966f6511b310b51', @NOW, @NOW),
 (1306, 1102, 1216, NULL, 'Damask Bazin — Three Yards', 'damask-bazin-three-yards',
  'Heavy damask, unbleached', 'Sold in three-yard cuts. Weight and origin not recorded by the seller.',
  'TER-BAZ-3Y', 9800.00, NULL, 'XOF', 7, 2, 0, 1, 0, NULL, NULL, 'SN', 'NATIONAL',
'NEW',
  NULL, NULL, NULL, 0, 0, 35, NULL,
  'PUBLISHED', @NOW, 1001, @NOW, NULL, @NOW, NULL, NULL, 'b4b0bbd5a0a433a03a86331a3821263b67d0d0d2e6c691f0cddf358a042150e8', @NOW, @NOW),
 (1307, 1101, 1213, 1201, 'Samsung Galaxy A05 (withdrawn)', 'samsung-galaxy-a05',
  'Superseded model', 'Unpublished rather than deleted: order lines still point at it.',
  'KOM-SGA05', 6900.00, NULL, 'GMD', 0, 3, 0, 0, 0, 0.190, NULL, 'GM', 'NATIONAL',
  'REFURBISHED',
  13.43830000, -16.67810000, NULL, 0, 4, 10, NULL,
  'ARCHIVED',  @NOW, 1001, @NOW, NULL, @NOW, @NOW, @NOW, NULL, @NOW, @NOW);

-- ── reviews ─────────────────────────────────────────────────────────────────
-- Nothing writes these: Review is an entity with no service, and the
-- `rating` / `total_reviews` columns on products above are maintained by hand
-- here to match.

-- edit_count and report_count are NOT NULL and have no database default: the
-- Lombok @Builder.Default on Review is a Java-side default and does nothing for
-- raw SQL. Listed explicitly rather than relied upon, which is also what a
-- migration adding them to a live table would have to do.
INSERT INTO reviews (id, product_id, user_id, rating, title, comment, verified, vendor_reply, vendor_replied_at, edit_count, report_count, created_at) VALUES
 (1310, 1301, 1004, 5, 'Battery is the selling point', 'Two days between charges with normal use. Screen is bright enough outdoors.', 1, 'Thank you Aminata!', @NOW, 0, 0, @NOW),
 (1311, 1301, 1005, 4, 'Good, slow to charge',        'No complaints about the phone. The charger in the box is slow.', 1, NULL, NULL, 0, 1, @NOW),
 (1312, 1303, 1004, 5, 'Boils fast',                  'Four minutes as advertised. Handle stays cool.', 1, NULL, NULL, 0, 0, @NOW),
 (1313, 1302, 1006, 4, 'Does what it should',         'Bought it for the torch and the standby time.', 0, NULL, NULL, 0, 0, @NOW),
 (1314, 1305, 1005, 5, 'Colour held',                 'Washed cold three times, no bleeding at all.', 1, NULL, NULL, 0, 0, @NOW);

-- ── vendor_bank_accounts ────────────────────────────────────────────────────
-- Where a payout would go. MOBILE_MONEY is the account type that matters most
-- in this market, and is deliberately the default for one of the two.

-- `account_number`, `iban` and `mobile_money_phone` are encrypted columns: the
-- JPA converter seals them with AES-GCM on the way in, and PUT
-- /vendor/stores/{id}/bank-account refuses outright when no key is configured
-- rather than writing one of these in clear.
--
-- The values below are plaintext, which is deliberate and safe in one
-- direction only. The decrypt path returns anything without the `enc:v1:`
-- marker unchanged, so a hand-written seed still reads; nothing about that can
-- cause a plaintext row to be *written* through the application. Read one of
-- these back through the API and it comes out as typed here. Save one through
-- the endpoint and the column turns into ciphertext — which is what
-- BankAccountEncryptionTest asserts by reading the raw column past the mapping.
--
-- The `*_last4` columns are the readable half, and the only half any response
-- carries. Nothing the API returns about a payout destination could be used to
-- send money anywhere.
--
-- `currency` is the VENDOR's settlement currency, never a buyer's display one.
-- 1322 is XOF because Awa banks in Senegal, whatever an order was charged in.

INSERT INTO vendor_bank_accounts
 (id, vendor_id, account_holder_name, bank_name, account_number, account_number_last4,
  account_type, currency, iban, iban_last4, swift_code, routing_number,
  mobile_money_phone, mobile_money_last4, mobile_money_provider,
  is_default, verified, last_changed_by_user_id, last_changed_at, created_at, updated_at)
VALUES
 (1320, 1101, 'Lamin Touray', 'Trust Bank Gambia', '0123456789', '6789', 'CHECKING',     'GMD',
  NULL, NULL, 'TBLGGMGM', NULL, NULL, NULL, NULL, 1, 1, 1002, @NOW, @NOW, @NOW),
 (1321, 1101, 'Lamin Touray', 'Africell Money',    '+2203100002', '0002', 'MOBILE_MONEY', 'GMD',
  NULL, NULL, NULL, NULL, '+2203100002', '0002', 'Africell Money', 0, 1, 1002, @NOW, @NOW, @NOW),
 (1322, 1102, 'Awa Diallo',   'Wave Senegal',      '+2217700003', '0003', 'MOBILE_MONEY', 'XOF',
  NULL, NULL, NULL, NULL, '+2217700003', '0003', 'Wave', 1, 0, 1003, @NOW, @NOW, @NOW);

-- 1103 has no payout destination at all. That is the correct state for a store
-- that has not been verified: there is nothing to pay out to yet, and the
-- endpoint that sets one re-authenticates the caller before it will.

-- ── store_operating_hours ───────────────────────────────────────────────────
-- When a driver may collect. Structured rather than the free-text opening hours
-- a storefront usually carries, because dispatch reads this: a pickup scheduled
-- for 03:00 because "Mon-Sat 9-6" was a string nobody could parse is a driver
-- standing outside a shuttered shop.
--
-- `closed` is a flag rather than two null times so that "shut on Sunday" and
-- "nobody has filled Sunday in yet" are different answers. 1101 is closed on
-- Sunday; 1103 has no rows at all, which is the second case.

INSERT INTO store_operating_hours (id, vendor_id, day_of_week, closed, opens_at, closes_at) VALUES
 (1350, 1101, 'MONDAY',    0, '09:00:00', '18:00:00'),
 (1351, 1101, 'TUESDAY',   0, '09:00:00', '18:00:00'),
 (1352, 1101, 'WEDNESDAY', 0, '09:00:00', '18:00:00'),
 (1353, 1101, 'THURSDAY',  0, '09:00:00', '18:00:00'),
 (1354, 1101, 'FRIDAY',    0, '09:00:00', '12:30:00'),
 (1355, 1101, 'SATURDAY',  0, '09:00:00', '16:00:00'),
 (1356, 1101, 'SUNDAY',    1, NULL,       NULL),
 (1357, 1102, 'MONDAY',    0, '08:30:00', '17:00:00'),
 (1358, 1102, 'SATURDAY',  0, '08:30:00', '13:00:00');

-- ── kyc_documents ───────────────────────────────────────────────────────────
-- What a store produced to be allowed to trade. Only the storage key is here;
-- the file is an object with its own access rules. A passport scan in a
-- database column is a passport scan in a backup, a staging dump and an
-- analyst's laptop.
--
-- Re-uploading supersedes rather than replaces, and 1360/1363 are that pair:
-- Awa's first national ID came back refused as unreadable and the second is the
-- live one. Both rows survive, and the reason on the first is the record of why
-- her onboarding took three weeks.
--
-- What is *required* depends on what the store claims to be. 1101 gave a
-- registration number and a tax number, so both certificates are asked of it.
-- 1103 gave neither and is asked for an identity document and a proof of
-- address, which is all a market trader has — demanding a company's paperwork
-- from her is how a marketplace turns away the sellers it exists for.

INSERT INTO kyc_documents
 (id, vendor_id, type, status, file_url, original_filename, content_type, size_bytes,
  expires_on, rejection_reason, reviewed_by_user_id, reviewed_at, superseded_at, submitted_at)
VALUES
 (1360, 1101, 'NATIONAL_ID',           'ACCEPTED',
  'kyc/1101/national-id-a4f21c.jpg',    'lamin-id.jpg',      'image/jpeg', 184320,
  '2031-04-18', NULL, 1001, @NOW, NULL, @NOW),
 (1361, 1101, 'PROOF_OF_ADDRESS',      'ACCEPTED',
  'kyc/1101/nawec-bill-77b902.pdf',     'nawec-bill.pdf',    'application/pdf', 96001,
  NULL, NULL, 1001, @NOW, NULL, @NOW),
 (1362, 1101, 'BUSINESS_REGISTRATION', 'ACCEPTED',
  'kyc/1101/rc-884120-2f81aa.pdf',      'registration.pdf',  'application/pdf', 210455,
  NULL, NULL, 1001, @NOW, NULL, @NOW),
 (1363, 1101, 'TAX_CERTIFICATE',       'ACCEPTED',
  'kyc/1101/tin-55231-9c04de.pdf',      'tin.pdf',           'application/pdf', 118204,
  NULL, NULL, 1001, @NOW, NULL, @NOW),
 (1364, 1102, 'NATIONAL_ID',           'REJECTED',
  'kyc/1102/cni-first-try-1b7e33.jpg',  'cni.jpg',           'image/jpeg', 44100,
  NULL, 'The photograph is too dark to read the number. Please send it again in daylight.',
  1001, @NOW, @NOW, @NOW),
 (1365, 1102, 'NATIONAL_ID',           'ACCEPTED',
  'kyc/1102/cni-second-try-8d21f0.jpg', 'cni-2.jpg',         'image/jpeg', 198340,
  '2029-11-02', NULL, 1001, @NOW, NULL, @NOW),
 (1366, 1102, 'PROOF_OF_ADDRESS',      'REJECTED',
  'kyc/1102/bail-4c9911.pdf',           'bail.pdf',          'application/pdf', 88210,
  NULL, 'This lease is for the depot. Send something showing the shop address on Rue de France.',
  1001, @NOW, NULL, @NOW),
 (1367, 1102, 'BUSINESS_REGISTRATION', 'ACCEPTED',
  'kyc/1102/rc-220914-3a77bc.pdf',      'rc.pdf',            'application/pdf', 156700,
  NULL, NULL, 1001, @NOW, NULL, @NOW);

-- 1103 has no rows: NOT_STARTED, which is why it sits in PENDING_KYC. That
-- state is waiting on Mariama, and PENDING is waiting on a reviewer; collapsed
-- into one status, the review queue would be mostly applications nobody sent.

-- ── store_staff ─────────────────────────────────────────────────────────────
-- People who work in a shop without owning it. Invited by email, because the
-- usual invitee is a relative or an assistant who has never used the platform —
-- 1371 is exactly that, and it has no user_id at all.
--
-- The token is stored as a SHA-256 digest, like every other bearer credential
-- here. 1372 was removed, so its digest is null: otherwise a link mailed last
-- week still opens the shop.
--
-- Permissions come from a store-only enum. There is no value a seller can put
-- in the table below that reaches another vendor's data or the platform's —
-- the dangerous grants are unrepresentable rather than rejected, which is the
-- difference between a check that can be forgotten and one that cannot.

INSERT INTO store_staff
 (id, vendor_id, user_id, email, display_name, status,
  invite_token_hash, invite_expires_at, invited_by_user_id,
  accepted_at, revoked_at, created_at, updated_at)
VALUES
 (1370, 1101, 1006, 'modou.sanneh@example.gm', 'Modou (shop floor)', 'ACTIVE',
  NULL, NULL, 1002, @NOW, NULL, @NOW, @NOW),
 (1371, 1101, NULL, 'binta.cousin@example.gm', 'Binta', 'INVITED',
  '5f2c8e1a9b73d04e6f8a1c2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60',
  @FUTURE, 1002, NULL, NULL, @NOW, @NOW),
 (1372, 1102, 1011, 'ndeye.sarr@example.sn', 'Ndeye', 'REVOKED',
  NULL, NULL, 1003, @NOW, @NOW, @NOW, @NOW);

INSERT INTO store_staff_permissions (staff_id, permission) VALUES
 (1370, 'ORDERS_VIEW'),
 (1370, 'ORDERS_FULFIL'),
 (1370, 'CATALOGUE_MANAGE'),
 (1371, 'ORDERS_VIEW');

-- 1372 has no permissions: revoking clears them rather than leaving a row that
-- still describes what somebody used to be allowed to do.
--
-- Note who is NOT in this table: Lamin and Awa. The owner is not a staff row —
-- there is no invitation to accept and no permission to withdraw — and
-- GET /vendor/stores/{id}/staff puts them at the top of the list from the
-- vendor record instead.

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

-- `version` is the optimistic lock, and it exists for one write: setting stock
-- to an absolute figure. A delta is safe whoever else is saving - +5 is +5 -
-- but two people counting the same shelf and saving 10 and 12 leave whichever
-- committed last, with the other simply wrong and nothing anywhere to say so.
-- So PATCH /vendor/inventory/{variantId} demands this value with a setTo and
-- refuses a stale one. It is separate from the pessimistic lock checkout takes:
-- that one stops two buyers reserving the last handset, this one stops two
-- members of staff overwriting each other's count.
--
-- 1350 is the serialised one. Its stock is not typed in - it is however many
-- handsets below are IN_STOCK, which is why it is 2 rather than a round number
-- somebody chose.

INSERT INTO product_variants (id, product_id, sku, stock, price_override, active, version) VALUES
 (1350, 1301, 'KOM-SGA16-128-BLK', 2, NULL,    1, 3),
 (1351, 1301, 'KOM-SGA16-128-BLU', 3, NULL,    1, 0),
 (1352, 1301, 'KOM-SGA16-256-BLK', 4, 9700.00, 1, 1),
 (1353, 1305, 'TER-WAX-IND-6Y',   18, NULL,    1, 0);

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

-- The row is written when a storage key is claimed and only becomes READY when
-- the bytes arrive, so a listing never shows an image that is not there. 1377
-- is an upload the seller started and abandoned: PENDING, no confirmed_at, and
-- deliberately not the default even though it is the only image on 1307.
--
-- Exactly one default per product, and it is whichever is first in sort order -
-- reordering is how a seller changes their card image, so the two are not
-- allowed to disagree.

INSERT INTO product_images
 (id, product_id, image_url, alt_text, sort_order, is_default,
  status, original_filename, content_type, size_bytes, confirmed_at, created_at)
VALUES
 (1370, 1301, 'https://media.example.invalid/products/sga16-front.jpg', 'Galaxy A16 front',   0, 1,
  'READY', 'sga16-front.jpg', 'image/jpeg', 184320, @NOW, @NOW),
 (1371, 1301, 'https://media.example.invalid/products/sga16-back.jpg',  'Galaxy A16 back',    1, 0,
  'READY', 'sga16-back.jpg',  'image/jpeg', 176044, @NOW, @NOW),
 (1372, 1302, 'https://media.example.invalid/products/n110.jpg',        'Nokia 110 4G',       0, 1,
  'READY', 'n110.jpg',        'image/jpeg',  91200, @NOW, @NOW),
 (1373, 1303, 'https://media.example.invalid/products/kettle.jpg',      'Kettle',             0, 1,
  'READY', 'kettle.jpg',      'image/jpeg', 120880, @NOW, @NOW),
 (1374, 1304, 'https://media.example.invalid/products/speaker.jpg',     'Bluetooth speaker',  0, 1,
  'READY', 'speaker.jpg',     'image/jpeg', 143002, @NOW, @NOW),
 (1375, 1305, 'https://media.example.invalid/products/wax-indigo.jpg',  'Indigo wax print',   0, 1,
  'READY', 'wax-indigo.jpg',  'image/jpeg', 210400, @NOW, @NOW),
 (1376, 1306, 'https://media.example.invalid/products/bazin.jpg',       'Damask bazin',       0, 1,
  'READY', 'bazin.jpg',       'image/jpeg', 198111, @NOW, @NOW),
 (1377, 1307, 'https://media.example.invalid/products/sga05.jpg',       NULL,                 0, 0,
  'PENDING', 'sga05.jpg',     'image/jpeg',   NULL, NULL,  @NOW);

-- ── product_translations ────────────────────────────────────────────────────
-- Not a nicety on this marketplace. Awa writes French in Ziguinchor; the person
-- paying is in London and reads English; the person receiving is in Serrekunda.
-- The listing text is the only description any of them gets - nobody in that
-- chain can pick the cloth up and look at it - so a translation is the
-- difference between a sale and an argument about what was bought.
--
-- 1390 is written by a person and 1391 by a machine, and the flag is shown to
-- the buyer. A machine translation of "six yards of wax print, cut to order" is
-- usually fine and occasionally nonsense, and somebody spending a month's
-- remittance deserves to know which kind of text they are reading.
--
-- 1392 is partial on purpose: a translated name with the original description
-- beneath it beats neither, so nothing on this table is required but the locale.

INSERT INTO product_translations
 (id, product_id, locale, name, short_description, description, machine_translated,
  created_at, updated_at)
VALUES
 (1390, 1305, 'fr-SN', 'Wax - six yards, indigo', 'Six yards, indigo',
  'Wax imprime a la main, coupe en six yards.', 0, @NOW, @NOW),
 (1391, 1305, 'en-GM', 'Wax print - six yards, indigo', 'Six yards, indigo',
  'Hand-finished wax print, cut to six yards.', 1, @NOW, @NOW),
 (1392, 1306, 'fr-SN', 'Bazin riche - trois yards', NULL, NULL, 0, @NOW, @NOW);

-- ── catalogue_jobs and catalogue_job_errors ─────────────────────────────────
-- Bulk import and export, run away from the request that asked for them. A
-- seller uploading four hundred rows over a mobile connection loses the
-- response long before the work finishes, so a synchronous import would have
-- written half their catalogue with nobody able to say which half.
--
-- `reference` is what they poll with, and it is random rather than sequential
-- because a job id anybody can count through hands out other sellers' import
-- errors - which name their products, their prices and their SKUs. Ownership is
-- checked in the query as well; this is the second lock, not the only one.
--
-- Three states worth seeing:
--
--   1400 COMPLETED_WITH_ERRORS  30 rows, 27 in, 3 rejected, with reasons
--   1401 FAILED                 the file could not be read at all
--   1402 COMPLETED (export)     finished, behind a link that expires
--
-- 1400 and 1401 are different kinds of failure and they need different words.
-- "Row 14 has no price" is a problem with a row and the other 29 still went in;
-- "this is not a spreadsheet" is a problem with the file and nothing was tried.

INSERT INTO catalogue_jobs
 (id, reference, vendor_id, requested_by_user_id, type, status,
  source_url, original_filename, format, result_url, result_expires_at,
  total_rows, succeeded_rows, failed_rows, failure_reason,
  created_at, started_at, finished_at)
VALUES
 (1400, 'IMP-7QK2M4XR9DTB5VNC', 1101, 1002, 'IMPORT', 'COMPLETED_WITH_ERRORS',
  'imports/1101/january-stock-4f21c8.xlsx', 'january-stock.xlsx', 'xlsx', NULL, NULL,
  30, 27, 3, NULL,
  @NOW, @NOW, @NOW),
 (1401, 'IMP-3HJ8P6WZ2FKD7RQY', 1101, 1002, 'IMPORT', 'FAILED',
  'imports/1101/prices-9c04de.pdf', 'prices.pdf', NULL, NULL, NULL,
  0, 0, 0, 'That file is not a spreadsheet we can read. Save it as .xlsx or .csv.',
  @NOW, @NOW, @NOW),
 (1402, 'EXP-5MNX9TQ2JVH4BKDW', 1102, 1003, 'EXPORT', 'COMPLETED',
  NULL, 'current', 'csv',
  'https://media.example.invalid/exports/exp-5mnx9tq2jvh4bkdw.csv', @FUTURE,
  2, 2, 0, NULL,
  @NOW, @NOW, @NOW);

-- The row numbers are the seller's own: the header is row 1, so the first data
-- row is row 2. Any other convention makes them count.
--
-- Each error quotes their text back. "Category does not exist" is half an
-- answer; "there is no category called 'Phonez'" is the whole one, and it is
-- the difference between a five-minute fix and an abandoned import.

INSERT INTO catalogue_job_errors (id, job_id, row_number, field, message, value) VALUES
 (1410, 1400, 4,  'price',    '''nine hundred'' is not a price.', 'nine hundred'),
 (1411, 1400, 17, 'category', 'There is no category called ''Phonez''.', 'Phonez'),
 (1412, 1400, 23, 'sku',      'You already have a listing with the code KOM-SGA16.', 'KOM-SGA16');

-- ── stock_movements ─────────────────────────────────────────────────────────
-- The stock ledger. Same shape as the custody chain and for the same reason: a
-- count that can be assigned directly is a count nobody can explain, and the
-- moment a seller finds nine on the shelf against eleven on the screen, "the
-- number is eleven" stops being an answer.
--
-- `quantity_change` is signed, so the rows sum to the figure on the shelf and a
-- reconciliation is something anybody can check rather than take on trust. Add
-- the changes for variant 1351 below: 0 + 10 - 3 - 4 = 3, which is its stock.
--
-- Note what is in here alongside the seller's own corrections: SALEs. An audit
-- that showed the manual edits and quietly omitted the orders that took the
-- stock would be wrong in exactly the case somebody opens it for - which is why
-- OrderServiceImpl reserves and releases through the ledger too.
--
-- A SALE carries no order number and cannot: stock is reserved before the order
-- exists, which is the right order to do it in. A RETURN does, because by then
-- there is one.
--
-- `recorded_by` is null on a sale. An order deducted the stock, not a person.

INSERT INTO stock_movements
 (id, vendor_id, product_id, variant_id, reason, quantity_change,
  stock_before, stock_after, reference, note, recorded_by_user_id, recorded_at)
VALUES
 (1450, 1101, 1301, 1351, 'RESTOCK',    10,  0, 10, 'GRN-2026-014', 'Container from Dakar',
  1002, @NOW),
 (1451, 1101, 1301, 1351, 'SALE',        -3, 10,  7, NULL, 'Reserved for an order', NULL, @NOW),
 (1452, 1101, 1301, 1351, 'CORRECTION',  -4,  7,  3, NULL,
  'Counted the shelf - four short, reported to the police', 1002, @NOW),
 (1453, 1101, 1301, 1352, 'RESTOCK',      6,  0,  6, 'GRN-2026-014', NULL, 1002, @NOW),
 (1454, 1101, 1301, 1352, 'SALE',        -2,  6,  4, NULL, 'Reserved for an order', NULL, @NOW),
 (1455, 1102, 1305, 1353, 'RESTOCK',     20,  0, 20, 'BOLT-77', 'Twenty six-yard pieces',
  1003, @NOW),
 (1456, 1102, 1305, 1353, 'SALE',        -6, 20, 14, NULL, 'Reserved for an order', NULL, @NOW),
 (1457, 1102, 1305, 1353, 'RETURN',       4, 14, 18, 'SJL-SEED-0001', 'Order cancelled',
  NULL, @NOW),
 (1458, 1101, 1301, 1350, 'SERIALISED_UNIT', 3, 0, 3, NULL, '3 handset(s) registered',
  1002, @NOW),
 (1459, 1101, 1301, 1350, 'SERIALISED_UNIT', -1, 3, 2, NULL,
  'Handset 356938035643809 is now WRITTEN_OFF', 1002, @NOW);

-- 1452 is the row that makes this worth building. A month of CORRECTIONs and no
-- RESTOCKs is a shop with a theft problem, and a ledger that called them all
-- restocks would hide it.

-- ── imei_units ──────────────────────────────────────────────────────────────
-- Phones are the one product a count cannot describe. Most sold here are
-- second-hand, and the buyer is frequently thousands of miles away choosing a
-- gift for somebody at home: two units of the same model are not
-- interchangeable when one was opened once and the other has a scratched
-- screen. That gap is most of the dispute surface on this marketplace, which is
-- why the grade is finer than the product's NEW/USED and why FOR_PARTS has to
-- name its fault.
--
-- Every IMEI here is Luhn-valid - the last digit checks the other fourteen, and
-- the API refuses one that does not. Check 490154203237518 by hand if you like.
--
-- 1464 and 1465 are the same model AND the same variant as each other. That is
-- the point of them: a count would say "two Galaxy A16 256GB", and the order
-- needs to say which of the two is in the box.
--
-- The unique constraint is platform-wide rather than per seller. The same
-- handset on two shelves is a phone somebody has sold twice, and a constraint
-- is the cheapest place to find that out.
--
-- 1462 is written off, which is why variant 1350's stock is 2 and not 3: the
-- units are the authority and the count follows them. A shop whose count did
-- not follow would go on selling a phone that is in a drawer with water damage.

INSERT INTO imei_units
 (id, imei, imei2, serial_number, vendor_id, product_id, variant_id, status, grade, grade_note,
  cost_price, battery_health, warranty_expires_on, sold_on_order_number, sold_at, note,
  registered_by_user_id, order_item_id, assigned_at, created_at, updated_at)
VALUES
 (1460, '490154203237518', NULL, 'RF8N90ABCDE', 1101, 1301, 1350, 'IN_STOCK', 'A_GRADE', NULL,
  7000.00, 98, '2027-04-18', NULL, NULL, 'Opened for display only', 1002, NULL, NULL, @NOW, @NOW),
 (1461, '351756051523993', NULL, 'RF8N90ABCDF', 1101, 1301, 1350, 'IN_STOCK', 'B_GRADE',
  'Light marks on the frame. Screen unmarked.',
  6600.00, 91, NULL, NULL, NULL, NULL, 1002, NULL, NULL, @NOW, @NOW),
 (1462, '356938035643809', NULL, 'RF8N90ABCDG', 1101, 1301, 1350, 'WRITTEN_OFF', 'FOR_PARTS',
  'Liquid damage. Does not charge.',
  6600.00, NULL, NULL, NULL, NULL, 'Dropped in a bucket during the rains', 1002, NULL, NULL, @NOW, @NOW),
 (1463, '013227009086244', NULL, 'RF8N90ABCDH', 1101, 1307, NULL, 'SOLD', 'A_GRADE', NULL,
  5800.00, 95, NULL, 'SJL-SEED-0001', @NOW, NULL, 1002, NULL, NULL, @NOW, @NOW),
 -- Bound to order line 1418, but not here: this table is written long before
 -- order_items exists, and the foreign key is real. The binding is an UPDATE
 -- further down, which is also the order it happens in for real - a handset is
 -- registered when it arrives and bound weeks later when somebody packs it.
 --
 -- RESERVED rather than SOLD: it is in a box in the shop, not with the buyer,
 -- and it goes back to IN_STOCK if the order is rejected.
 (1464, '351756051524009', NULL, 'RF8N90ABCDJ', 1101, 1301, 1352, 'RESERVED', 'A_GRADE', NULL,
  7100.00, 100, '2027-06-30', 'SJL-SEED-0004', NULL, 'Packed for the Serrekunda parcel',
  1002, NULL, NULL, @NOW, @NOW),
 -- On the shelf, same model and same variant. What makes the binding on 1464 a
 -- choice rather than the only possibility: two units of one variant are not
 -- interchangeable, and the order records which one actually went.
 (1465, '490154203237609', NULL, 'RF8N90ABCDK', 1101, 1301, 1352, 'IN_STOCK', 'B_GRADE',
  'Small scuff on the rear glass.',
  6900.00, 94, NULL, NULL, NULL, NULL, 1002, NULL, NULL, @NOW, @NOW);

-- 1463 is SOLD and carries the order it went out on. A seller cannot set that
-- by hand - the order does it, so the record and the sale cannot disagree.
-- Nothing here is BLOCKED either: that is only ever set by us after a report,
-- because a seller who could clear it could launder a stolen handset, and
-- recording an IMEI at all is mostly about making that harder.

-- ── promotions ──────────────────────────────────────────────────────────────
-- A promotion is a price the shop is charging; a coupon is a credential a buyer
-- presents. They look alike and behave differently - a coupon can be capped per
-- customer because there is a customer to count, and a promotion cannot.
--
-- `currency` is the vendor's settlement currency and never the buyer's. 1471 is
-- 500 GMD off because Lamin banks in dalasi; a buyer paying in GBP sees that
-- converted at the rate their order was quoted at. Re-striking it in GBP would
-- leave him funding an amount that moves with the market.
--
-- 1470 and 1472 are the overlap case. Both cover product 1301 and their windows
-- touch, so POST /vendor/promotions/1472/activate is refused and the refusal
-- names 1470 - "conflicts with an existing promotion" would leave a seller
-- hunting through their own list. Two discounts on one item do not compound
-- into a price anybody can predict; they compound into whichever the pricing
-- code reaches first.
--
-- 1473 is FREE_SHIPPING and runs alongside 1470 quite happily: one discounts
-- the delivery leg and the other the goods, so they are not competing for the
-- same number.

INSERT INTO promotions
 (id, vendor_id, name, description, type, status,
  percent_off, amount_off, currency, buy_quantity, get_quantity, get_discount_percent,
  bundle_price, minimum_basket, maximum_discount,
  starts_at, ends_at, times_applied, activated_at, created_at, updated_at)
VALUES
 (1470, 1101, 'Tobaski phone sale', '15% off selected handsets', 'PERCENT', 'ACTIVE',
  15.00, NULL, 'GMD', NULL, NULL, NULL, NULL, 2000.00, 2500.00,
  @NOW, @FUTURE, 7, @NOW, @NOW, @NOW),
 (1471, 1101, '500 off kettles', NULL, 'FIXED', 'PAUSED',
  NULL, 500.00, 'GMD', NULL, NULL, NULL, NULL, 1000.00, NULL,
  @NOW, @FUTURE, 0, @NOW, @NOW, @NOW),
 (1472, 1101, 'Second phone half price', 'Buy one, get one at 50%', 'BUY_X_GET_Y', 'DRAFT',
  NULL, NULL, 'GMD', 1, 1, 50.00, NULL, NULL, NULL,
  @NOW, @FUTURE, 0, NULL, @NOW, @NOW),
 (1473, 1102, 'Free delivery on cloth', NULL, 'FREE_SHIPPING', 'ACTIVE',
  NULL, NULL, 'XOF', NULL, NULL, NULL, NULL, 10000.00, NULL,
  @NOW, @FUTURE, 3, @NOW, @NOW, @NOW);

-- Which goods each covers. No rows means the whole shop, which is why 1473 has
-- none - and why a store-wide promotion conflicts with everything.

INSERT INTO promotion_products (promotion_id, product_id) VALUES
 (1470, 1301),
 (1470, 1302),
 (1471, 1303),
 (1472, 1301);

INSERT INTO promotion_categories (promotion_id, category_id) VALUES
 (1470, 1213);

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
 (id, order_number, tracking_code, customer_id, guest_name, guest_email, guest_phone, guest_session_id,
  status, subtotal, shipping_cost, tax_amount, discount, total, currency,
  coupon_id, coupon_code, payment_status, payment_method, paid_at,
  delivery_mode, pickup_point_id,
  shipping_full_name, shipping_phone, shipping_street, shipping_apartment,
  shipping_city, shipping_state, shipping_postal_code, shipping_country,
  shipping_latitude, shipping_longitude, shipping_address_id,
  billing_full_name, billing_street, billing_city, billing_state, billing_postal_code, billing_country,
  notes, internal_notes, delivery_instructions, contactless_delivery,
  scheduled_date, scheduled_time_slot,
  loyalty_points_earned, loyalty_points_redeemed, loyalty_discount,
  gift_card_code, gift_card_discount, created_at, updated_at)
VALUES
 (1401, 'SJL-SEED-0001', 'K7MPQ4RTVX2ND9YH', 1005, NULL, NULL, NULL, NULL,
  'CONFIRMED', 139.56, 4.13, 0.00, 13.96, 129.73, 'GBP',
  1080, 'TERANGA10', 'PAID', 'CARD', @NOW,
  'HOME_DELIVERY', NULL,
  'Oliver Bennett', '+447700900005', '221B Baker Street', 'Flat 2',
  'London', 'Greater London', 'NW1 6XE', 'GB',
  51.52370000, -0.15850000, 1052,
  'Oliver Bennett', '221B Baker Street', 'London', 'Greater London', 'NW1 6XE', 'GB',
  'Leave with the porter if out.', 'Two vendors, two settlement currencies.', 'Ring the bell twice.', 0,
  NULL, NULL,
  129, 0, 0.00,
  NULL, 0.00, @NOW, @NOW),

 (1402, 'SJL-SEED-0002', 'B3WQHJ7FNXR5MTCD', NULL, 'Binta Faal', 'binta.faal@example.gm', '+2203100010', 'c4d9e7a1-2f60-4b13-9a55-7e81d0c3b46f',
  'PENDING', 2900.00, 150.00, 0.00, 0.00, 3050.00, 'GMD',
  NULL, NULL, 'PENDING', 'PAY_ON_DELIVERY', NULL,
  'HOME_DELIVERY', NULL,
  'Binta Faal', '+2203100010', 'Bakau New Town', NULL,
  'Bakau', 'Kanifing', NULL, 'GM',
  13.47810000, -16.68200000, NULL,
  NULL, NULL, NULL, NULL, NULL, NULL,
  NULL, 'Guest checkout, no account.', NULL, 0,
  NULL, NULL,
  0, 0, 0.00,
  NULL, 0.00, @NOW, @NOW),

 (1403, 'SJL-SEED-0003', 'Z9DKP2VMHT6RXQFB', 1004, NULL, NULL, NULL, NULL,
  'DELIVERED', 2500.00, 110.00, 0.00, 0.00, 2610.00, 'GMD',
  NULL, NULL, 'PAID', 'CASH_IN_STORE', @NOW,
  'PICKUP_POINT', 1096,
  'Aminata Ceesay', '+2203100004', 'Westfield Junction', 'Unit 3',
  'Serekunda', 'West Coast', NULL, 'GM',
  13.44290000, -16.67760000, 1054,
  NULL, NULL, NULL, NULL, NULL, NULL,
  'Collecting Saturday morning.', NULL, NULL, 0,
  '2026-09-12', '09:00-12:00',
  26, 0, 0.00,
  NULL, 0.00, @NOW, @NOW),

 -- The shape this marketplace exists for, and the only order in this file that
 -- actually has it: the payer and the recipient are different people, in
 -- different countries, on different continents. Fatou is in Madrid and pays in
 -- euros with a European card; the parcel goes to her sister Isatou in
 -- Serrekunda, who has a phone number and no account.
 --
 -- Read the two blocks below as the two halves of C1. billing_* is the payer
 -- context: Madrid, Spain, which is what decides the currency and the payment
 -- methods offered. shipping_* is the delivery context: Serrekunda, Gambia,
 -- which is what decides the shipping cost, the serviceability and what the
 -- seller in Banjul is shown. Nothing derives either from the other, and an
 -- implementation that collapsed them would price this parcel as a delivery
 -- inside Spain.
 (1404, 'SJL-SEED-0004', 'Q2XNF8VJHR4TMW7K', 1005, NULL, NULL, NULL, NULL,
  'PROCESSING', 125.26, 6.90, 0.00, 0.00, 132.16, 'EUR',
  NULL, NULL, 'PAID', 'CARD', @NOW,
  'HOME_DELIVERY', NULL,
  'Isatou Ceesay', '+2203100077', '12 Kairaba Avenue', NULL,
  'Serrekunda', 'Kanifing', NULL, 'GM',
  13.43840000, -16.67810000, NULL,
  'Fatou Ceesay', 'Calle de Alcala 145', 'Madrid', 'Comunidad de Madrid', '28009', 'ES',
  'For my sister - please do not write the price on the box.',
  'Diaspora order: payer ES, delivery GM.', 'Call the number on arrival, she has no app.', 0,
  NULL, NULL,
  132, 0, 0.00,
  NULL, 0.00, @NOW, @NOW);

-- shipping_address_id on the orders above records which saved address the buyer
-- chose; the shipping_* snapshot records where the parcel actually went. They
-- are deliberately not the same thing, and 1403 is the case that shows why:
-- Aminata picked her old flat (1054) and then chose to collect at Westfield, so
-- the snapshot is the pickup point and the reference is the address. She has
-- since moved and deleted 1054 — which is why that row is a tombstone rather
-- than gone, and why deleting it did not orphan this order.

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
 (1600, 1401, 'PAY-SEED0000000', 'CARD', 'PAID', 129.73, 0.00, 'GBP',
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
  @NOW, NULL, NULL, 2, @NOW, @NOW),
 -- One payment, two vendors, and one of them has since rejected. The payment
 -- stays PAID and whole: the refund for the rejected slice is a request against
 -- that slice (1451), not a reduction of this row. Reducing it here would make
 -- the money the buyer actually paid unreconstructable.
 (1603, 1404, 'PAY-SEED0000003', 'CARD', 'PAID', 132.16, 0.00, 'EUR',
  'MOCK-3D7C25E1A84B49F2C6E0', NULL, NULL, '{"gateway":"mock","status":"succeeded"}', NULL,
  NULL, 'MOCK-3D7C25E1A84B49F2C6E0', NULL, 'Settled synchronously by mock',
  @NOW, NULL, NULL, 1, @NOW, @NOW);

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

-- fx_* is the rate each slice's native figures were converted at, and when that
-- rate was published. Without it the frozen amounts are the right numbers that
-- nobody can explain: the rate table moves daily, so a payout questioned next
-- month cannot be re-derived from anything still on the system.
--
-- Check it: 9900.00 GMD x 0.011 = 108.90 GBP, which is what 1501's `total` says.
-- 13050.00 XOF x 0.00128 = 16.70, which is 1502's. The stored rate has to
-- reproduce the stored amounts or it is decoration rather than evidence.
--
-- IDENTITY on 1503 and 1504 is deliberate rather than lazy: those were sold in
-- dalasi to a buyer shopping in dalasi, and recording "no conversion applied" as
-- a fact is not the same as leaving the columns null, which would be
-- indistinguishable from nobody having written anything down.

-- The fulfilment stamps are consequences, not inputs. accepted_at is set when a
-- seller accepts, ready_at when they pack, collected_at when a driver presents
-- the release code — so a row with a collected_at and no ready_at would be a
-- parcel that left without being packed. release_code_issue_count is a summary
-- of the handover_codes rows, never the authority: the rate limit counts the
-- rows, because a counter is something a code path can forget to increment.

INSERT INTO vendor_orders
 (id, order_id, vendor_id, status, native_currency,
  subtotal_native, discount_native, total_native,
  commission_rate, commission_native, delivery_native, payout_native,
  subtotal, discount, total, coupon_id, coupon_code, cancelled_at,
  receipt_confirmed_at, escrow_released_at, created_at, updated_at,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id,
  accepted_at, ready_at, collected_at, rejection_reason,
  release_code_issue_count, release_code_issued_at)
VALUES
 (1501, 1401, 1101, 'SHIPPED', 'GMD',
  11000.00, 1100.00, 9900.00,
  10.00, 990.00, 209.09, 8910.00,
  121.00, 12.10, 108.90, NULL, NULL, NULL, NULL, NULL, @NOW, @NOW,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  @NOW, @NOW, @NOW, NULL, 1, @NOW),
 (1502, 1401, 1102, 'PREPARING', 'XOF',
  14500.00, 1450.00, 13050.00,
  12.50, 1631.25, 1429.69, 11418.75,
  18.56, 1.86, 16.70, NULL, NULL, NULL, NULL, NULL, @NOW, @NOW,
  'XOF', 'GBP', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  @NOW, NULL, NULL, NULL, 0, NULL),
 (1503, 1402, 1101, 'PENDING', 'GMD',
  2900.00, 0.00, 2900.00,
  10.00, 290.00, 150.00, 2610.00,
  2900.00, 0.00, 2900.00, NULL, NULL, NULL, NULL, NULL, @NOW, @NOW,
  'GMD', 'GMD', 1.00000000, @NOW, 'IDENTITY', NULL,
  NULL, NULL, NULL, NULL, 0, NULL),
 (1504, 1403, 1101, 'DELIVERED', 'GMD',
  2500.00, 0.00, 2500.00,
  10.00, 250.00, 110.00, 2250.00,
  2500.00, 0.00, 2500.00, NULL, NULL, NULL, @NOW, @NOW, @NOW, @NOW,
  'GMD', 'GMD', 1.00000000, @NOW, 'IDENTITY', NULL,
  @NOW, @NOW, @NOW, NULL, 1, @NOW),

 -- Packed and waiting for a driver, with a live release code (1735) and a
 -- handset bound to its line (1464). The only slice here that reaches
 -- READY_FOR_PICKUP, which is the state most of the fulfilment surface is about:
 -- the goods are in the shop, nothing is on a road, and the code exists.
 (1505, 1404, 1101, 'READY_FOR_PICKUP', 'GMD',
  9700.00, 0.00, 9700.00,
  10.00, 970.00, 380.00, 8730.00,
  106.70, 0.00, 106.70, NULL, NULL, NULL, NULL, NULL, @NOW, @NOW,
  'GMD', 'EUR', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  @NOW, @NOW, NULL, NULL, 1, @NOW),

 -- Rejected by the seller, carrying the reason the buyer is shown and the
 -- refund it raised (1451). Note what did not happen to 1505 on the same
 -- payment: it is still packed and still going. That is C3 — one seller pulling
 -- out does not touch another's line, and the refund is this slice's amount
 -- rather than a proportion of the order.
 (1506, 1404, 1102, 'CANCELLED', 'XOF',
  14500.00, 0.00, 14500.00,
  12.50, 1812.50, 1590.00, 12687.50,
  18.56, 0.00, 18.56, NULL, NULL, @NOW, NULL, NULL, @NOW, @NOW,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  @NOW, NULL, NULL,
  'The indigo run sold out at the market and the next dye is three weeks away.',
  0, NULL);

-- ── vendor_payouts ──────────────────────────────────────────────────────────
-- Transfers to a seller. Keyed to a user as well as a vendor: drivers and
-- pickup-point operators earn through this same table and have no shop.
--
-- Every one of these has matching rows in vendor_ledger_entries below, and that
-- is the point — a payout is not a fact on its own, it is a movement in a
-- ledger. The balance a seller is shown is the sum of those rows and is stored
-- nowhere, so there is nothing for it to drift from.
--
-- 1031 is REQUESTED, which is the state this platform spends most of its time
-- in: the seller has asked and nobody has decided. Money leaving is the one
-- action no later call can undo.

-- ── payout_batches ──────────────────────────────────────────────────────────
-- A run of transfers, prepared by one person and approved by another.
--
-- The two people are the point, and the entity refuses one person doing both
-- rather than leaving it to a procedure somebody follows when they are not in a
-- hurry. 2500 below was prepared by Fatou and released by Omar; a row where
-- those two columns held the same id would be a control that exists only in a
-- comment.
--
-- Per currency, always. Sixteen sellers settling in dalasi and four in CFA are
-- two runs, not one with a mixed total — a figure that added GMD to XOF is a
-- number somebody would nonetheless reconcile against a bank statement (C2).
--
-- total is STORED rather than summed at read time, and the two are checked
-- against each other before release. A batch whose recorded total and item sum
-- disagree has been edited underneath, and releasing it would move an amount
-- nobody approved — which is exactly what the second approver believes they are
-- preventing. Keep 2500's total equal to the sum of the payouts pointing at it.
--
-- exclusions is written once, at assembly. It describes what the batch IS, not
-- what is true now: an approver who notices a seller missing will otherwise
-- assume a bug rather than a hold.

INSERT INTO payout_batches
 (id, version, reference, status, currency, total, item_count,
  prepared_by_user_id, prepared_at, approved_by_user_id, approved_at,
  note, cancelled_reason, cancelled_at, exclusions, created_at, updated_at)
VALUES
 (2500, 0, 'PB-2026-08-GMD', 'APPROVED', 'GMD', 1000.00, 1,
  1001, '2026-09-01 09:30:00.000000', 1012, '2026-09-01 09:55:00.000000',
  'August settlement for the Kombos.', NULL, NULL,
  'Basse Phone Repair: payouts on hold (three filtered messages in one day) — 2,400.00 GMD is being kept, not lost.',
  @NOW, @NOW),

 -- Waiting on somebody who is not Fatou. Nothing has moved: no ledger entry is
 -- written until release, so cancelling this changes no seller's balance.
 (2501, 0, 'PB-2026-09-XOF', 'AWAITING_APPROVAL', 'XOF', 0.00, 0,
  1001, @NOW, NULL, NULL,
  'September run for Senegal.', NULL, NULL,
  'Teranga Textiles: already has 96000 XOF in flight.',
  @NOW, @NOW),

 -- Abandoned before release, with the reason on the row rather than in
 -- somebody's memory.
 (2502, 0, 'PB-2026-09-GMD-A', 'CANCELLED', 'GMD', 0.00, 0,
  1012, @NOW, NULL, NULL, NULL,
  'Assembled against the wrong window.', @NOW, NULL, @NOW, @NOW);

-- ── fx_spreads ──────────────────────────────────────────────────────────────
-- What the platform adds to a published rate, and from when.
--
-- The spread is real revenue and it is the BUYER's money, so it is a record
-- with a start time and an author rather than a number in a properties file
-- that changes when somebody redeploys.
--
-- Basis points, not a decimal fraction: a spread typed as 0.015 and one typed
-- as 1.5 are indistinguishable to a form and differ by a factor of a hundred in
-- what a buyer pays. 150 here is 1.50%.
--
-- Rows are APPENDED, never edited. 2510 is the opening platform-wide spread and
-- 2511 supersedes it for GMD→EUR only; both survive, because an order converted
-- in July has to stay explicable in December. Most specific wins, so a Gambian
-- seller's goods bought in euro use 2511 and the same goods bought in sterling
-- use 2510.
--
-- A NULL currency on either side is a wildcard.

INSERT INTO fx_spreads
 (id, from_currency, to_currency, basis_points, effective_from, set_by_user_id,
  reason, created_at)
VALUES
 (2510, NULL, NULL, 100, '2026-06-01 00:00:00.000000', 1001,
  'Opening platform spread — 1.00% on every conversion.', @NOW),

 (2511, 'GMD', 'EUR', 150, '2026-09-01 00:00:00.000000', 1001,
  'The dalasi moved twice in August. 1.50% on this pair only.', @NOW);

-- ── report_exports ──────────────────────────────────────────────────────────
-- Somebody asked for a file of the platform's money.
--
-- A row rather than only an access-log line, because this is the most sensitive
-- read on the platform: every seller's earnings and every buyer's spend, in one
-- file that leaves the building. "Who exported every seller's earnings in
-- March" has to stay answerable, which is why an expired export keeps its row
-- and loses only its link.
--
-- That is what 2522 is. READY with a lapsed result_expires_at would be a row
-- telling a lie, so the worker moves it to EXPIRED and the URL stops being
-- offered. The row, and the name on it, remain.

INSERT INTO report_exports
 (id, reference, type, status, requested_by_user_id, requested_by_email,
  from_date, to_date, currency, vendor_id, format, result_url, row_count,
  result_expires_at, failure_reason, created_at, started_at, finished_at, updated_at)
VALUES
 (2520, 'EXP-4K7MQ2BVNXRT', 'LEDGER', 'READY', 1001, 'fatou.admin@sujula.gm',
  '2026-08-01', '2026-08-31', NULL, NULL, 'CSV',
  'https://media.example.invalid/finance-exports/exp-4k7mq2bvnxrt.csv', 412,
  -- Relative to now rather than to @NOW, which is a fixed date in this file.
  -- A READY export is one whose link works TODAY; anchoring its expiry to the
  -- seed's own clock made it a row saying READY about a file that had gone,
  -- which is the exact lie the EXPIRED state exists to prevent.
  DATEADD('HOUR', 20, CURRENT_TIMESTAMP), NULL, @NOW, @NOW, @NOW, @NOW),

 (2521, 'EXP-9QRT4XKM2BHV', 'RECONCILIATION', 'QUEUED', 1001, 'fatou.admin@sujula.gm',
  '2026-09-01', '2026-09-14', 'GMD', NULL, 'CSV',
  NULL, NULL, NULL, NULL, @NOW, NULL, NULL, @NOW),

 (2522, 'EXP-2MHB6VXQ4TWK', 'PAYOUTS', 'EXPIRED', 1012, 'ousman.support@sujula.gm',
  '2026-07-01', '2026-07-31', NULL, 1101, 'CSV',
  'https://media.example.invalid/finance-exports/exp-2mhb6vxq4twk.csv', 18,
  DATEADD('HOUR', -6, @NOW), NULL, @NOW, @NOW, @NOW, @NOW),

 -- It did not build, and the reason is on the row. The person who asked is
 -- waiting on a file, and a job stuck in BUILDING saying nothing is the worst
 -- of both.
 (2523, 'EXP-7XK4MQ2BVN3D', 'REVENUE', 'FAILED', 1001, 'fatou.admin@sujula.gm',
  '2026-01-01', '2026-09-14', NULL, NULL, 'CSV',
  NULL, NULL, NULL,
  'That window holds 241,880 rows, above the 200,000 limit. Narrow the dates or name a single seller — a truncated finance file reconciles to the wrong answer.',
  @NOW, @NOW, @NOW, @NOW);

INSERT INTO vendor_payouts
 (id, user_id, vendor_id, amount, currency, status, reference, notes, period,
  requested_by_user_id, requested_at, failure_reason, processed_by, processed_at,
  batch_id, attempts, last_attempt_at, created_at, updated_at)
VALUES
 -- Sent in a run, which is why it carries a batch. attempts counts the
 -- transfers actually made rather than the rows: a retry is a second attempt on
 -- the same payout, because the seller is owed one amount and a second row
 -- would look like two.
 (1030, 1002, 1101, 1000.00, 'GMD', 'COMPLETED', 'PO-2026-08-KOMBO',
  'August settlement', '2026-08', NULL, NULL, NULL,
  1001, '2026-09-01 10:00:00.000000', 2500, 1, '2026-09-01 10:00:00.000000', @NOW, @NOW),

 -- Asked for by the seller rather than assembled by anybody: no batch. Both
 -- kinds live in this table on purpose — a second table for "batch payouts"
 -- would be a second way for money to leave the platform.
 (1031, 1002, 1101,  250.00, 'GMD', 'REQUESTED', 'PO-2026-09-KOMBO',
  'Asked for on the 13th', NULL, 1002, @NOW, NULL,
  NULL, NULL, NULL, 0, NULL, @NOW, @NOW),

 -- Two attempts and the bank refused both. A third is allowed; after that the
 -- answer is a different destination account, not another attempt.
 (1032, 1003, 1102, 96000.00, 'XOF', 'FAILED', 'PO-2026-09-TERANGA',
  NULL, '2026-08', NULL, NULL, 'The bank rejected the account number',
  NULL, NULL, NULL, 2, @NOW, @NOW, @NOW);

-- ── vendor_ledger_entries ───────────────────────────────────────────────────
-- Every movement of a seller's money. Nothing stores a balance: a balance is
-- the sum of these rows, so there is no second number to disagree with them.
--
-- Signed throughout. Positive is owed to the seller, negative leaves. A ledger
-- of absolute values plus a direction column is one whose sum is wrong the first
-- time somebody forgets to read the direction.
--
-- available_from is escrow. NULL means the money is earned and NOT payable
-- because the parcel has not been confirmed delivered — which is the whole
-- reason a buyer in Madrid will send money for goods they cannot inspect. It is
-- stamped when delivery is proven, never by time passing and never by the seller.
--
-- Currencies are never mixed. Kombo's rows are all GMD and Teranga's all XOF,
-- and no row anywhere converts one to the other: the conversion already happened
-- once, at checkout, at a rate the order still carries (C2).
--
-- What these rows come to, and what /vendor/balance therefore says:
--
--   Kombo (1101), GMD   available  2500 - 250 - 1000 - 250   =  1000.00
--                       pending    9900 - 990 + 9700 - 970   = 17640.00
--                       on hold    the 250.00 requested in 1031, which has
--                                  already left `available` above — asking is
--                                  what commits it, not approval
--   Teranga (1102), XOF available  0        (the payout failed and came back)
--                       pending    13050 - 1631              = 11419
--
-- Teranga's rejected slice 1506 is the interesting one: a sale, its commission,
-- the refund and the commission handed back — four rows that come to exactly
-- nothing. Netting them into one row would hide the thing a seller opens a
-- refund to check, which is that they were not charged commission on a sale that
-- did not happen.

INSERT INTO vendor_ledger_entries
 (id, vendor_id, vendor_order_id, payout_id, type, amount, currency,
  available_from, occurred_at, description, reference, created_by_user_id,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id,
  created_at)
VALUES
 -- Order 1403, delivered and confirmed: the only money Kombo can actually touch.
 (1800, 1101, 1504, NULL, 'SALE',       2500.00, 'GMD',
  @NOW, @NOW, 'Sale on order SJL-SEED-0003', 'SJL-SEED-0003', NULL,
  'GMD', 'GMD', 1.00000000, @NOW, 'IDENTITY', NULL, @NOW),
 (1801, 1101, 1504, NULL, 'COMMISSION', -250.00, 'GMD',
  @NOW, @NOW, 'Platform commission on order SJL-SEED-0003', 'SJL-SEED-0003', NULL,
  'GMD', 'GMD', 1.00000000, @NOW, 'IDENTITY', NULL, @NOW),

 -- Order 1401, shipped but not confirmed. Earned, and not payable.
 (1802, 1101, 1501, NULL, 'SALE',       9900.00, 'GMD',
  NULL, @NOW, 'Sale on order SJL-SEED-0001', 'SJL-SEED-0001', NULL,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1803, 1101, 1501, NULL, 'COMMISSION', -990.00, 'GMD',
  NULL, @NOW, 'Platform commission on order SJL-SEED-0001', 'SJL-SEED-0001', NULL,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),

 -- Order 1404, packed and waiting for a driver. Also held.
 (1804, 1101, 1505, NULL, 'SALE',       9700.00, 'GMD',
  NULL, @NOW, 'Sale on order SJL-SEED-0004', 'SJL-SEED-0004', NULL,
  'GMD', 'EUR', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1805, 1101, 1505, NULL, 'COMMISSION', -970.00, 'GMD',
  NULL, @NOW, 'Platform commission on order SJL-SEED-0004', 'SJL-SEED-0004', NULL,
  'GMD', 'EUR', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),

 -- August's transfer, which settled.
 (1806, 1101, NULL, 1030, 'PAYOUT',    -1000.00, 'GMD',
  @NOW, @NOW, 'Payout requested — PO-2026-08-KOMBO', 'PO-2026-08-KOMBO', NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, @NOW),

 -- And September's, which is still a question rather than an answer. The money
 -- leaves `available` the moment it is asked for, so a second request cannot
 -- claim it while the first is being decided.
 (1807, 1101, NULL, 1031, 'PAYOUT',     -250.00, 'GMD',
  @NOW, @NOW, 'Payout requested — PO-2026-09-KOMBO', 'PO-2026-09-KOMBO', NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, @NOW),

 -- Teranga's live slice on order 1401. 13050 x 12.5% is 1631.25, and XOF has no
 -- minor units, so the ledger holds 1631 — 1631.25 CFA is not an amount that
 -- exists and a row storing it could never be paid.
 (1808, 1102, 1502, NULL, 'SALE',       13050, 'XOF',
  NULL, @NOW, 'Sale on order SJL-SEED-0001', 'SJL-SEED-0001', NULL,
  'XOF', 'GBP', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1809, 1102, 1502, NULL, 'COMMISSION', -1631, 'XOF',
  NULL, @NOW, 'Platform commission on order SJL-SEED-0001', 'SJL-SEED-0001', NULL,
  'XOF', 'GBP', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),

 -- The slice Teranga rejected, in four rows that come to nothing. The refund
 -- sits on the same side of escrow as the sale it reverses; if it did not, a
 -- fully refunded order would leave a balance behind.
 (1810, 1102, 1506, NULL, 'SALE',       14500, 'XOF',
  NULL, @NOW, 'Sale on order SJL-SEED-0004', 'SJL-SEED-0004', NULL,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1811, 1102, 1506, NULL, 'COMMISSION', -1813, 'XOF',
  NULL, @NOW, 'Platform commission on order SJL-SEED-0004', 'SJL-SEED-0004', NULL,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1812, 1102, 1506, NULL, 'REFUND',    -14500, 'XOF',
  NULL, @NOW,
  'Refund on order SJL-SEED-0004 — the seller could not fulfil this order',
  'RFN-SEED-0002', NULL,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),
 (1813, 1102, 1506, NULL, 'COMMISSION_REVERSAL', 1813, 'XOF',
  NULL, @NOW, 'Commission returned on the refunded part of SJL-SEED-0004',
  'RFN-SEED-0002', NULL,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL, @NOW),

 -- A transfer that left and came back. Two rows, not a deleted one: the attempt
 -- happened, and a seller looking at a gap in their statement deserves to see
 -- both halves of it.
 (1814, 1102, NULL, 1032, 'PAYOUT',    -96000, 'XOF',
  @NOW, @NOW, 'Payout requested — PO-2026-09-TERANGA', 'PO-2026-09-TERANGA', NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, @NOW),
 (1815, 1102, NULL, 1032, 'PAYOUT_REVERSAL', 96000, 'XOF',
  @NOW, @NOW,
  'Payout PO-2026-09-TERANGA came back — The bank rejected the account number',
  'PO-2026-09-TERANGA', NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, @NOW);

-- ── product_view_stats ──────────────────────────────────────────────────────
-- One row per listing per day, not one per view. Views outnumber orders by a
-- long way on a marketplace where most traffic is a phone on a slow connection,
-- and a row each would be the biggest table here inside a month for a number
-- only ever read as a daily total.
--
-- These count page loads, including reloads and the same person twice. They are
-- NOT unique visitors, and the funnel that uses them says so on the response —
-- a conversion rate that quietly means something other than what a seller
-- assumes is worse than no conversion rate at all.
--
-- The phone is looked at far more than it is bought, which is the shape of
-- selling a 9,700 dalasi item: 180 looks, one sale.

INSERT INTO product_view_stats (id, product_id, vendor_id, viewed_on, views) VALUES
 (1850, 1301, 1101, '2026-09-11', 64),
 (1851, 1301, 1101, '2026-09-12', 71),
 (1852, 1301, 1101, '2026-09-13', 45),
 (1853, 1303, 1101, '2026-09-12', 18),
 (1854, 1303, 1101, '2026-09-13', 12),
 (1855, 1305, 1102, '2026-09-12', 26),
 (1856, 1305, 1102, '2026-09-13', 9);

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
  delivery_cost, product_name, product_sku, variant_sku, selected_options, product_image_url,
  assigned_imeis, imei_assigned_at)
VALUES
 (1405, 1401, 1501, 1301, 1352, 1101, 1,
  9700.00, 9700.00, 'GMD', 106.70, 106.70,
  1.84, 'Samsung Galaxy A16', 'KOM-SGA16', 'KOM-SGA16-256-BLK', 'Storage: 256 GB, Colour: Black',
  'https://media.example.invalid/products/sga16-front.jpg', NULL, NULL),
 (1406, 1401, 1501, 1303, NULL, 1101, 1,
  1300.00, 1300.00, 'GMD', 14.30, 14.30,
  0.46, 'Tobaski 1.8L Electric Kettle', 'KOM-KET18', NULL, NULL,
  'https://media.example.invalid/products/kettle.jpg', NULL, NULL),
 (1407, 1401, 1502, 1305, 1353, 1102, 1,
  14500.00, 14500.00, 'XOF', 18.56, 18.56,
  1.83, 'Wax Print — Six Yards, Indigo', 'TER-WAX-IND', 'TER-WAX-IND-6Y', 'Length: Six yards',
  'https://media.example.invalid/products/wax-indigo.jpg', NULL, NULL),
 (1408, 1402, 1503, 1307, NULL, 1101, 1,
  2900.00, 2900.00, 'GMD', 2900.00, 2900.00,
  150.00, 'Samsung Galaxy A05', 'KOM-SGA05', NULL, NULL,
  NULL, NULL, NULL),
 (1409, 1403, 1504, 1303, NULL, 1101, 2,
  1250.00, 2500.00, 'GMD', 1250.00, 2500.00,
  110.00, 'Tobaski 1.8L Electric Kettle', 'KOM-KET18', NULL, NULL,
  'https://media.example.invalid/products/kettle.jpg', NULL, NULL),
 -- A phone, packed and bound to handset 1464. assigned_imeis is a snapshot for
 -- the receipt; imei_units.order_item_id is the authority, and the two are
 -- written together when the seller scans it.
 (1418, 1404, 1505, 1301, 1352, 1101, 1,
  9700.00, 9700.00, 'GMD', 106.70, 106.70,
  4.18, 'Samsung Galaxy A16', 'KOM-SGA16', 'KOM-SGA16-256-BLK', 'Storage: 256 GB, Colour: Black',
  'https://media.example.invalid/products/sga16-front.jpg',
  '351756051524009', @NOW),
 -- The rejected seller's line. It stays on the order: what the buyer ordered is
 -- a fact, and deleting the row would leave the refund referring to nothing.
 (1419, 1404, 1506, 1305, 1353, 1102, 1,
  14500.00, 14500.00, 'XOF', 18.56, 18.56,
  2.72, 'Wax Print — Six Yards, Indigo', 'TER-WAX-IND', 'TER-WAX-IND-6Y', 'Length: Six yards',
  'https://media.example.invalid/products/wax-indigo.jpg',
  NULL, NULL);

-- The handset binding, now that both ends of it exist. Two writes, deliberately:
-- imei_units.order_item_id is the authority and is what a warranty claim or a
-- stolen-handset report is resolved through, and order_items.assigned_imeis is
-- the snapshot the buyer's receipt reads years later. The service writes both
-- together when the seller scans; so does this.
UPDATE imei_units SET order_item_id = 1418, assigned_at = @NOW WHERE id = 1464;

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

-- ── refund_requests ─────────────────────────────────────────────────────────
-- What a buyer's cancellation actually produces. A refund is attached to ONE
-- vendor_order, never to the order, because one seller pulling out of a
-- multivendor basket does not refund the rest of it — 1450 below takes back
-- Dakar Mobile's 16.70 GBP and leaves Banjul Electronics' 108.90 alone.
--
-- The status is REQUESTED, not COMPLETED, and that is the point: money leaving
-- the platform is the one action no later API call can undo, so an
-- administrator decides. Nothing in the buyer surface can move it further.
--
-- The fx_* columns are copied from the slice rather than looked up again. By
-- the time a refund settles the published rate has moved, and re-deriving it
-- would make what the buyer is given back and what the vendor is not paid stop
-- agreeing — 13050 XOF is 16.70 GBP at the rate this order was placed at and at
-- no other.

INSERT INTO refund_requests
 (id, reference, order_id, vendor_order_id, requested_by_user_id, status,
  amount, currency, amount_native,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id,
  reason, decided_by_user_id, decided_at, decision_note, payment_id, completed_at, created_at)
VALUES
 (1450, 'RFN-SEED-0001', 1401, 1502, 1005, 'REQUESTED',
  16.70, 'GBP', 13050.00,
  'XOF', 'GBP', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  'Found the same phone locally.', NULL, NULL, NULL, NULL, NULL, @NOW),
 -- Raised by the SELLER rejecting, not by the buyer asking. The two arrive at
 -- the same queue and the same status, and they should: either way a person
 -- decides whether money leaves. requested_by is the seller's user here, which
 -- is how an administrator tells the two apart.
 (1451, 'RFN-SEED-0002', 1404, 1506, 1003, 'REQUESTED',
  18.56, 'EUR', 14500.00,
  'XOF', 'EUR', 0.00128000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  'The seller could not fulfil this order: The indigo run sold out at the market and the next dye is three weeks away.',
  NULL, NULL, NULL, NULL, NULL, @NOW);

-- 1451 refunds 18.56 EUR of a 132.16 EUR payment and leaves the other 106.70
-- alone, because that parcel is packed and going. A refund expressed as a
-- proportion of the order could not say that.

-- 1502 is left CONFIRMED above rather than CANCELLED on purpose: this is the
-- state between a buyer asking and an administrator answering, which is where
-- most refunds in a real queue actually sit. An admin surface that only ever
-- sees already-cancelled slices has never been tested against it.

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
 (1717, 1701, 'IN_TRANSIT',       'With the international courier',    13.43830000, -16.67810000, 1007, @NOW),
 (1718, 1700, 'DELIVERED',        'Receipt confirmed by the buyer',    NULL,        NULL,         1004, @NOW);

-- 1718 is what POST /orders/{id}/vendor-orders/{voId}/confirm-receipt writes,
-- and it is why that endpoint is not just a status flip: the buyer saying the
-- goods arrived is itself a link in the custody chain, recorded in their name
-- like every other handover. 1715 is the driver's account of the same moment
-- and 1718 is the recipient's; the row that releases the money is the second
-- one. It carries no coordinates because a buyer confirming from a phone in
-- another country is not evidence of where the parcel is.
--
-- Note what these descriptions contain. A driver types free text into this
-- column — 1712 names Ebrima — so GET /track/{code} never publishes it: the
-- public page substitutes a fixed phrase per status, and that substitution is
-- the only thing standing between a driver's note and a stranger with an SMS.

-- ── proof_of_delivery ───────────────────────────────────────────────────────

INSERT INTO proof_of_delivery (id, delivery_id, image_url, signature_url, latitude, longitude, notes, submitted_at, created_at) VALUES
 (1720, 1700, 'https://media.example.invalid/pod/dlv-0000001.jpg', NULL,
  13.44290000, -16.67760000, 'Photographed at the counter with the buyer present.', @NOW, @NOW);

-- ── shipments, shipment_legs, custody_events ────────────────────────────────
-- The custody chain, and the point of the whole delivery module.
--
-- A shipment is ONE PARCEL, which is one vendor order: a seller packs their
-- whole slice into one box and hands over one release code. Keying it to an
-- order line instead would give a two-line slice two parcels with one code
-- between them.
--
-- STATUS IS NEVER WRITTEN BY ANYTHING BUT THE CHAIN. Shipment has no status
-- setter; CustodyChain derives it from the custody_events below every time one
-- is appended. The values in the status column here are what that derivation
-- produces for these events — not an independent claim. Change an event and the
-- status is wrong until the chain is re-derived, which is exactly the property
-- worth having: the events are the truth and the column is a cache of them.
--
-- Read the three shipments as three points on that chain:
--
--   1900  DELIVERED       collected, then released at the door. The full chain,
--                         with a photograph on the last link.
--   1901  OUT_FOR_DELIVERY collected and moving. One event, and the status that
--                         follows from it.
--   1902  DRIVER_OFFERED  no events at all. The status comes from the leg being
--                         offered and unanswered, which is what "nothing has
--                         happened yet" derives to.
--
-- metres_from_expected and within_geofence are kept on every event rather than
-- checked and discarded. A delivery at 12 metres and one at 4,300 both happened;
-- only one is worth anything in a dispute, and the chain says which. 1901's
-- collection is the far one, deliberately: it was recorded and flagged rather
-- than refused, because the parcel may genuinely have changed hands and refusing
-- would have stranded it.

INSERT INTO shipments
 (id, version, reference, tracking_code, vendor_order_id, status,
  recipient_name, recipient_phone, destination_street, destination_city, destination_country,
  destination_latitude, destination_longitude,
  origin_latitude, origin_longitude, origin_address,
  parcel_count, delivery_fee, fee_currency,
  failed_attempts, next_attempt_after,
  collected_at, delivered_at, returned_at, cancelled_at, created_at, updated_at,
  held_at_pickup_point_id, shelf_code, stored_at, storage_deadline,
  pickup_commission, pickup_commission_currency,
  requested_pickup_point_id, requested_window_from, requested_window_until,
  safe_drop_authorised, safe_drop_location, safe_drop_person)
VALUES
 -- Order 1403: collected and delivered, and the reason vendor_order 1504's
 -- escrow could be released at all.
 (1900, 0, 'SHP-SEED-0001', 'PARC7K3M9QXB2VDH', 1504, 'DELIVERED',
  'Aminata Ceesay', '+2203100004', 'Westfield Junction, Unit 3', 'Serekunda', 'GM',
  13.44290000, -16.67760000,
  13.45300000, -16.67500000, '14 Kairaba Avenue, Serekunda',
  1, 110.00, 'GMD',
  0, NULL,
  @NOW, @NOW, NULL, NULL, @NOW, @NOW,
  -- Went through the Westfield counter and was collected from it. held_at is
  -- NULL because it is no longer on the shelf, and the shelf code and the
  -- commission stay: they are the record of where it sat and what handling it
  -- was worth, which is what an earnings statement next month is built from.
  NULL, 'B-417', @NOW, @NOW, 25.00, 'GMD',
  NULL, NULL, NULL, FALSE, NULL, NULL),

 -- Order 1404: the diaspora parcel. Fatou paid in Madrid; Isatou is waiting in
 -- Serrekunda and has no account.
 (1901, 0, 'SHP-SEED-0002', 'PARCQ4T8NHRW6JZY', 1505, 'OUT_FOR_DELIVERY',
  'Isatou Ceesay', '+2203100077', '12 Kairaba Avenue', 'Serrekunda', 'GM',
  13.43840000, -16.67810000,
  13.45300000, -16.67500000, '14 Kairaba Avenue, Serekunda',
  1, 380.00, 'GMD',
  0, NULL,
  @NOW, NULL, NULL, NULL, @NOW, @NOW,
  NULL, NULL, NULL, NULL, NULL, NULL,
  -- What Isatou asked for, and the row that says so is 1940 below. These six
  -- columns are derived: RecipientDirectives recomputes them from the
  -- instruction record every time one is written, exactly as the status column
  -- is recomputed from the custody chain. Nothing sets them directly, which is
  -- why a safe-drop permission cannot exist here without a row saying who gave
  -- it, when, and holding which code.
  NULL, @TOMORROW, @TOMORROW_LATE, TRUE, 'with the pharmacy next door', 'Ndey'),

 -- Order 1401's Banjul slice: packed, offered, and nobody has answered yet.
 (1902, 0, 'SHP-SEED-0003', 'PARCV2B9KXFM3QTD', 1501, 'DRIVER_OFFERED',
  'Oliver Bennett', '+447700900005', '221B Baker Street, Flat 2', 'London', 'GB',
  51.52370000, -0.15850000,
  13.45300000, -16.67500000, '14 Kairaba Avenue, Serekunda',
  2, 209.09, 'GMD',
  0, NULL,
  NULL, NULL, NULL, NULL, @NOW, @NOW,
  NULL, NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, FALSE, NULL, NULL),

 -- On the shelf at Westfield right now, waiting for somebody to collect it.
 -- This is the row the counter's screen is built from, and what makes
 -- pickup_points.stored_parcels = 1 true rather than asserted.
 --
 -- Its deadline is in the future, so returning it is refused: somebody may be
 -- travelling to collect, and sending it back early takes a decision that is
 -- not the counter's to take.
 (1903, 0, 'SHP-SEED-0004', 'PARCH6RJ4WY8NPQC', 1502, 'AT_PICKUP_POINT',
  'Binta Faal', '+2203100010', 'Bakau New Town', 'Bakau', 'GM',
  13.47810000, -16.68200000,
  13.45300000, -16.67500000, 'Teranga Mobile, Dakar',
  1, 96.43, 'GMD',
  0, NULL,
  @NOW, NULL, NULL, NULL, @NOW, @NOW,
  1096, 'A-118', @NOW, @FUTURE, 25.00, 'GMD',
  NULL, NULL, NULL, FALSE, NULL, NULL);

-- Legs are what a driver accepts, not shipments. The driver who can reach a shop
-- on Kairaba Avenue is frequently not the one who covers the street a parcel is
-- going to, so a journey is a list of hops with one person accountable for each.
--
-- 1912 carries an offer_expires_at in the past on purpose: it is the lapsed
-- offer, which the queue must not show and the accept endpoint must refuse. An
-- offer nobody declined holds a parcel out of circulation, which is worse for
-- the person waiting than one somebody turned down.

INSERT INTO shipment_legs
 (id, version, shipment_id, sequence, leg_type, assignment_status, driver_id,
  offered_at, offer_expires_at, accepted_at, declined_at, started_at, completed_at,
  decline_reason,
  origin_latitude, origin_longitude, origin_label,
  destination_latitude, destination_longitude, destination_label,
  origin_pickup_point_id, destination_pickup_point_id,
  distance_km, earning, earning_currency, created_at, updated_at)
VALUES
 (1910, 0, 1900, 1, 'ORIGIN_TO_PICKUP', 'COMPLETED', 1090,
  @NOW, NULL, @NOW, NULL, @NOW, @NOW, NULL,
  13.45300000, -16.67500000, 'Kombo Electronics, Kairaba Avenue',
  13.44290000, -16.67760000, 'Westfield Junction',
  NULL, 1096,
  1.800, 110.00, 'GMD', @NOW, @NOW),

 (1911, 0, 1901, 1, 'ORIGIN_TO_RECIPIENT', 'IN_PROGRESS', 1090,
  @NOW, NULL, @NOW, NULL, @NOW, NULL, NULL,
  13.45300000, -16.67500000, 'Kombo Electronics, Kairaba Avenue',
  13.43840000, -16.67810000, 'Serrekunda',
  NULL, NULL,
  1.640, 380.00, 'GMD', @NOW, @NOW),

 -- Offered and lapsed. Not held against the driver: nobody answered, which is
 -- different from somebody saying no.
 (1912, 0, 1902, 1, 'ORIGIN_TO_PICKUP', 'OFFERED', 1090,
  @LAPSED, @LAPSED, NULL, NULL, NULL, NULL, NULL,
  13.45300000, -16.67500000, 'Kombo Electronics, Kairaba Avenue',
  13.44290000, -16.67760000, 'Westfield Junction',
  NULL, 1096,
  1.800, 209.09, 'GMD', @NOW, @NOW),

 -- The leg that carried 1903 to the counter. Completed, because the counter
 -- accepted it and the parcel is on the shelf now.
 (1913, 0, 1903, 1, 'ORIGIN_TO_PICKUP', 'COMPLETED', 1090,
  @NOW, NULL, @NOW, NULL, @NOW, @NOW, NULL,
  13.45300000, -16.67500000, 'Teranga Mobile',
  13.44290000, -16.67760000, 'Westfield Junction',
  NULL, 1096,
  2.100, 96.43, 'GMD', @NOW, @NOW);

-- The chain itself. Append-only: a correction is a new event, because "the
-- driver said they delivered it and then said they had not" is a fact worth
-- keeping rather than a mistake to erase.
--
-- Two clocks on every row. occurred_at is the driver's phone and recorded_at is
-- when the server heard; they differ by hours for something captured with no
-- signal, which is Tuesday here. 1923 is that case — captured_offline is 1 and
-- its client_event_id is what stops a re-upload recording it twice.
--
-- code_presented is kept even though the code is spent by the time it lands. It
-- is a record of what was read out, not a credential, and a disputed handover
-- months later is exactly when somebody needs it.

INSERT INTO custody_events
 (id, shipment_id, leg_id, type, recorded_by_user_id, counterparty_user_id,
  code_presented, handover_code_id,
  latitude, longitude, accuracy_metres, metres_from_expected, within_geofence,
  photo_url, signature_url, reason_code, note,
  occurred_at, captured_offline, client_event_id, recorded_at)
VALUES
 -- 1900: the full chain, start to finish.
 (1920, 1900, 1910, 'ARRIVED_AT_ORIGIN', 1007, NULL,
  NULL, NULL,
  13.45301000, -16.67498000, 8.00, 2.34, 1,
  NULL, NULL, NULL, 'At the shop',
  @NOW, 0, 'seed-evt-1920', @NOW),
 (1921, 1900, 1910, 'COLLECTED', 1007, NULL,
  '418302', 1730,
  13.45299000, -16.67501000, 9.00, 1.62, 1,
  NULL, NULL, NULL, NULL,
  @NOW, 0, 'seed-evt-1921', @NOW),
 -- The last link, and the only one that releases the seller's money. It carries
 -- the most: a code, a position at the counter, and a photograph.
 (1922, 1900, 1910, 'RELEASED', 1008, NULL,
  '234861', 1732,
  13.44291000, -16.67759000, 6.00, 1.42, 1,
  'https://media.example.invalid/pod/shp-0001.jpg', NULL, NULL,
  'Collected at the counter by the buyer',
  @NOW, 0, 'seed-evt-1922', @NOW),

 -- 1901: one event, captured with no signal, and the status that follows.
 -- 4.3km from the shop: recorded and flagged rather than refused, because the
 -- parcel may genuinely have changed hands and refusing would strand it.
 (1923, 1901, 1911, 'COLLECTED', 1007, NULL,
  '871460', 1736,
  13.49000000, -16.65000000, 240.00, 4310.55, 0,
  NULL, NULL, NULL, 'Signal was gone in the compound; uploaded on the road',
  @NOW, 1, 'seed-evt-1923', @NOW),

 -- 1903: collected from the seller, then handed across a counter. Its
 -- AT_PICKUP_POINT status is derived from the second of these, and its
 -- collected_at from the first — a parcel on a shelf that had never been
 -- collected would be the hole C4 exists to close.
 (1924, 1903, 1913, 'COLLECTED', 1007, NULL,
  '660419', 1733,
  13.45301000, -16.67499000, 10.00, 1.85, 1,
  NULL, NULL, NULL, NULL,
  @NOW, 0, 'seed-evt-1924', @NOW),
 -- Recorded by the OPERATOR (user 1008), not the driver. The receiving party
 -- verifies the giving party's code, which is the same shape as every other
 -- link and the only thing a code can prove. within_geofence is true without a
 -- phone's guess: a counter is a fixed, reviewed address.
 (1925, 1903, 1913, 'DEPOSITED', 1008, NULL,
  '905177', 1731,
  13.44290000, -16.67760000, NULL, 0.00, 1,
  NULL, NULL, NULL, 'Onto shelf A-118',
  @NOW, 0, 'seed-evt-1925', @NOW);

-- 1902 has no events at all, and that is the point of it: its DRIVER_OFFERED
-- status is derived from the leg being offered rather than from anything having
-- happened to the parcel. "Nothing yet" is a state the chain has to produce
-- correctly, and it is the state most shipments are in at any moment.

-- ── handover_codes ──────────────────────────────────────────────────────────
-- The short code the receiving party reads out. `version` is an optimistic
-- lock, so two people cannot burn the same code concurrently, and
-- `failed_attempts` is what burns a code somebody is guessing at: six digits is
-- a hundred thousand tries to a determined person and three to somebody who
-- misheard.
--
-- Written after the shipments above because these now point at legs. Exactly one
-- owner is set on each row - a delivery, a vendor order, or a shipment - since a
-- code belonging to none would authorise nothing and one belonging to several
-- would authorise several different handovers.

-- VENDOR_RELEASE is the one type that hangs off a vendor order rather than a
-- delivery, which is why delivery_id is nullable: a seller packs one parcel for
-- the whole slice and there is no delivery yet to attach it to. Exactly one of
-- the two columns is set on every row.
--
-- 1735 and 1736 are the same slice. 1735 was read out over a bad line, so the
-- seller reissued: it carries invalidated_at and no longer opens anything, and
-- 1736 is the one the driver actually presented - which is why it is now spent
-- rather than live. Reissuing replaces rather than edits, so a code that leaked
-- is dead AND the fact that it was reissued survives; a seller reissuing
-- constantly is worth being able to see.
--
-- 1737 is the recipient's, and the only code on this platform presented by
-- somebody with no account. It is shipment-scoped rather than delivery-scoped,
-- which is the third kind of owner: a delivery, a vendor order, or a parcel.

INSERT INTO handover_codes (id, delivery_id, vendor_order_id, shipment_id, leg_id, code, code_type, used, used_at, used_by_user_id, expires_at, invalidated_at, failed_attempts, version, created_at) VALUES
 (1730, 1700, NULL, NULL, NULL, '418302', 'VENDOR_TO_DRIVER',    1, @NOW, 1007, '2026-09-12 18:00:00.000000', NULL, 0, 1, @NOW),
 (1731, 1700, NULL, NULL, NULL, '905177', 'DRIVER_TO_PICKUP',    1, @NOW, 1008, '2026-09-12 18:00:00.000000', NULL, 0, 1, @NOW),
 (1732, 1700, NULL, NULL, NULL, '234861', 'PICKUP_TO_CUSTOMER',  1, @NOW, 1004, '2026-09-12 20:00:00.000000', NULL, 0, 1, @NOW),
 (1733, 1701, NULL, NULL, NULL, '660419', 'VENDOR_TO_DRIVER',    1, @NOW, 1007, '2026-09-13 18:00:00.000000', NULL, 0, 1, @NOW),
 -- The three rows below are meant to be LIVE, so their expiry is relative to
 -- the moment this file runs rather than a date written into it. A code seeded
 -- to expire on a fixed day is expired by the time anybody reads these notes,
 -- and the row is then decorative: the endpoint answers "no code" and the value
 -- printed at the end of this file is refused. The used codes above keep their
 -- fixed timestamps, because those are history and history does not decay.
 (1734, 1701, NULL, NULL, NULL, '773025', 'DRIVER_TO_CUSTOMER',  0, NULL, NULL, @FUTURE, NULL, 0, 0, @NOW),
 (1735, NULL, 1505, NULL, NULL, '304912', 'VENDOR_RELEASE',      0, NULL, NULL, @FUTURE, @NOW,  0, 0, @NOW),
 -- Unused, and that is the only state it could be in: slice 1505 is
 -- READY_FOR_PICKUP with collected_at NULL, so no driver has presented this
 -- code. It was seeded as used, which contradicted the slice beside it and made
 -- GET /vendor/orders/1505/handoff-code answer "there is no code yet" against
 -- the note at the end of this file saying it answers 871460.
 (1736, NULL, 1505, NULL, NULL, '871460', 'VENDOR_RELEASE',      0, NULL, NULL, @FUTURE, NULL, 0, 0, @NOW),
 -- The recipient's code for the diaspora parcel. It went to Fatou in Madrid by
 -- email, and she passes it to Isatou the way anybody passes on a Western Union
 -- reference. Isatou needs no account, no app and no email of her own (C5) —
 -- only to read six digits to the driver at the door.
 (1737, NULL, NULL, 1901, 1911, '540913', 'RECIPIENT_RELEASE', 0, NULL, NULL, @FUTURE, NULL, 0, 0, @NOW);

-- Two rows for one slice, and vendor_orders.release_code_issue_count says 1
-- rather than 2 - which is the disagreement to expect, because the count is a
-- summary and these rows are the record. The reissue limit reads the rows.

-- Neither code is written on the parcel label. The label carries a signed QR
-- that resolves the address for whoever scans it; the code is read aloud to the
-- driver. A code printed on the box it protects protects nothing.

-- ── recipient_instructions ──────────────────────────────────────────────────
-- What Isatou asked for about her own parcel, and the proof she was the one
-- asking. She has no account: no row in users, no password, no session. What
-- she has is a link her sister forwarded and six digits her sister read to her
-- down the telephone from Madrid — which is the case this marketplace exists to
-- serve, and the reason this table exists at all.
--
-- Append-only, like custody_events. 1940 is superseded rather than edited: she
-- first said to leave it with Ndey at the pharmacy, then said next door was
-- closed on Fridays and gave a window instead. A driver who set off on the
-- first instruction has to be able to show it was the instruction at the time,
-- which a mutable column cannot show.
--
-- verified_by_code_id points at parcel_access_codes and nothing reads it to
-- authorise anything. It is evidence after the fact, which is the only kind
-- that survives the code being rotated.

INSERT INTO recipient_instructions
 (id, shipment_id, type, pickup_point_id, window_from, window_until,
  safe_drop_location, safe_drop_person,
  verified_by_code_id, verified_at, superseded_at, summary, created_at)
VALUES
 -- Withdrawn by 1941. The words survive because "the parcel was left behind the
 -- pharmacy" needs an answer to "who said it could be".
 (1940, 1901, 'AUTHORISE_SAFE_DROP', NULL, NULL, NULL,
  'at the pharmacy next door', 'Ndey',
  1950, @NOW, @NOW, 'Leave with Ndey at the pharmacy next door', @NOW),

 -- In force. Note that shipments.1901 carries BOTH the window from this row and
 -- a safe-drop authorisation from 1942 — two instructions of different kinds,
 -- each superseding only its own kind.
 (1941, 1901, 'RESCHEDULE', NULL, @TOMORROW, @TOMORROW_LATE,
  NULL, NULL,
  1950, @NOW, NULL, 'Deliver between tomorrow morning and early afternoon', @NOW),

 -- She thought better of it once the window was set: Ndey is there in the
 -- mornings. This is the row shipments.1901.safe_drop_* is derived from.
 (1942, 1901, 'AUTHORISE_SAFE_DROP', NULL, NULL, NULL,
  'with the pharmacy next door', 'Ndey',
  1950, @NOW, NULL, 'Leave with Ndey at the pharmacy next door', @NOW);

-- ── parcel_access_codes ─────────────────────────────────────────────────────
-- The six digits that let her give those instructions. NOT the delivery code:
-- 1737 in handover_codes is what she reads to the driver at the door, and this
-- is what she types on the tracking page. Keeping them separate is the point —
-- a delivery code typed into a web page is a delivery code somebody can be
-- talked into giving away, and a page that displayed one would be worse still.
--
-- sent_to is stored already masked. Support reading this row needs to know
-- whether Fatou got it; the address in full is on the order for anyone who
-- genuinely needs it.
--
-- 1950 is deliberately expired, so entering it is refused and asking for a new
-- one is the answer the page gives. 1951 shows the other failure: five wrong
-- guesses, invalidated, and the message that sends somebody to ask for another
-- rather than to wait.

INSERT INTO parcel_access_codes
 (id, version, shipment_id, code, failed_attempts, invalidated_at, expires_at,
  last_used_at, instructions_given, sent_to, created_at)
VALUES
 (1950, 0, 1901, '628104', 0, NULL, @LAPSED, @NOW, 3, 'f•••u@example.es', @NOW),
 (1951, 0, 1901, '317905', 5, @NOW,  @FUTURE, NULL, 0, 'f•••u@example.es', @NOW);

-- ── return_requests, return_lines ───────────────────────────────────────────
-- A return is goods going back. A refund is money going back. They are
-- different things on different timelines and this schema keeps them apart —
-- refund_requests is elsewhere in this file and is what an administrator
-- approves, while this is a conversation between a buyer and a seller about a
-- parcel.
--
-- 1960 is the diaspora case in its unhappiest form. Oliver in London paid for a
-- phone on order 1401; the seller says the driver collected it, and it never
-- turned up. Note the reason: NEVER_ARRIVED is the one return reason that
-- skips the delivery check, because a buyer who cannot report a missing parcel
-- until it is marked delivered has been beaten by the very problem they are
-- reporting.
--
-- The offer columns carry a settlement the buyer did not take: the seller
-- offered 50 GBP against the 106.70 claimed, and Oliver escalated instead.
-- offered_at survives beside the escalation because "he offered and I refused"
-- is a fact a moderator needs.
--
-- Two currencies on every figure, at the order's own frozen rate (C2). Oliver
-- is owed pounds; Kombo would lose dalasis; the rate is 0.011, taken on the day
-- of the order and never read again.

INSERT INTO return_requests
 (id, version, reference, vendor_order_id, requested_by_user_id, status, reason,
  description, photo_urls,
  amount, currency, amount_native,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id,
  decided_by_user_id, decided_at, decision_note, seller_pays_carriage,
  offered_amount, offered_amount_native, offer_note, offered_at, offer_accepted_at,
  received_at, received_by_user_id, received_note,
  escalated_at, dispute_id, refund_reference, withdrawn_at,
  created_at, updated_at)
VALUES
 (1960, 0, 'RTN-4KQ7MXB2VN', 1501, 1005, 'ESCALATED', 'NEVER_ARRIVED',
  'The tracking says collected but nothing has arrived. My cousin has been at the address every day.',
  'https://media.example.invalid/returns/empty-doorway.jpg',
  106.70, 'GBP', 9700.00,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  1002, @NOW, 'Our driver collected it and the code was presented. We cannot send a second one.',
  TRUE,
  -- 50 GBP at 0.011 display-per-native is 4545.45 GMD. Divided at the order's
  -- own rate rather than looked up today: the seller does not choose the rate.
  50.00, 4545.45, 'We will meet you halfway rather than argue about it.', @NOW, NULL,
  NULL, NULL, NULL,
  -- dispute_id is filled by the UPDATE below, once 1980 exists. The two tables
  -- reference each other, so no insert order satisfies both — which is exactly
  -- what the service does too: raise the dispute, then point the return at it.
  @NOW, NULL, NULL, NULL,
  @NOW, @NOW);

-- The line, priced as it was at the time. product_name and the unit prices are
-- snapshots: a seller who later renames or reprices the phone must not change
-- what an open return settles at.
INSERT INTO return_lines
 (id, return_request_id, order_item_id, quantity, unit_price_native, unit_price,
  product_name, assigned_imeis)
VALUES
 (1970, 1960, 1405, 1, 9700.00, 106.70, 'Samsung Galaxy A16', NULL);

-- ── disputes ────────────────────────────────────────────────────────────────
-- What 1960 became. Raising one freezes the seller's money on THIS sub-order
-- and nothing else (C3): vendor_order 1501 carries dispute_frozen_at below, and
-- 1502 — Teranga's wax print on the same payment — does not. Teranga is paid on
-- time for a parcel nobody is arguing about.
--
-- hold_reference is deliberately NULL, and that is the interesting half. The
-- freeze works two ways depending on where the money was when the argument
-- started:
--
--   * Sale already out of escrow → a DISPUTE_HOLD row takes it back off the
--     available balance, with a reason the seller can read.
--   * Sale still IN escrow → there is nothing available to hold, and posting a
--     negative row would take the balance down twice for one sale. The freeze
--     is the stamp on the vendor order, which MoneyLedger reads before it
--     releases anything.
--
-- 1501 is the second case: its ledger rows (1802, 1803) carry available_from
-- NULL. So there is no hold row here, and there must not be one — which the
-- claims check.

INSERT INTO disputes
 (id, version, reference, vendor_order_id, raised_by_user_id, status, reason,
  description, return_request_id,
  amount, currency, amount_native,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id,
  frozen_at, unfrozen_at, hold_reference,
  outcome, awarded_to_buyer, awarded_to_buyer_native,
  resolved_by_user_id, resolved_at, resolution_note, withdrawn_at, refund_reference,
  assigned_to_user_id, assigned_at, due_by, callback_requested_at,
  created_at, updated_at)
VALUES
 -- Open, assigned to support, and PAST its deadline. due_by is frozen when the
 -- dispute is raised rather than computed from today's policy — a queue sorted
 -- by a deadline that moves with policy is one where the oldest is never the
 -- most urgent, and both parties have money tied up behind the answer.
 --
 -- Support may work this: read it, write internal notes, arrange a call. Only
 -- an administrator may decide it, because deciding moves the seller's money.
 (1980, 0, 'DSP-9MRT4XKQ2B', 1501, 1005, 'OPEN', 'NOT_RECEIVED',
  'Nothing arrived. The seller says it was collected; nobody has it.', 1960,
  106.70, 'GBP', 9700.00,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL,
  @NOW, NULL, NULL,
  NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  1012, @NOW, @LAPSED, @NOW,
  @NOW, @NOW);

-- The other half of the link, now that both rows exist.
UPDATE return_requests SET dispute_id = 1980 WHERE id = 1960;

-- The freeze itself. One column, read by MoneyLedger before every escrow
-- release, so a delivery recorded after the dispute was raised cannot pay the
-- seller in the middle of the argument about whether it happened.
UPDATE vendor_orders SET dispute_frozen_at = @NOW WHERE id = 1501;

-- ── dispute_messages, dispute_evidence ──────────────────────────────────────
-- The case file, in the order it was written. Both sides, plus one internal
-- note that neither of them may read — filtered out in the repository query
-- rather than in a mapper somebody later forgets to apply.
--
-- author_side is frozen at the time rather than derived on every read: a buyer
-- who later opens a shop is not retroactively the seller in an old argument.

INSERT INTO dispute_messages
 (id, dispute_id, author_user_id, author_side, body, internal, created_at)
VALUES
 (1990, 1980, 1005, 'BUYER',
  'Nothing arrived. The seller says it was collected; nobody has it.', FALSE, @NOW),
 (1991, 1980, NULL, 'VENDOR',
  'From the return RTN-4KQ7MXB2VN: Our driver collected it and the code was presented. We cannot send a second one.',
  FALSE, @NOW),
 -- Never shown to either party. A moderator working from the custody chain
 -- needs somewhere to put what they found that is not a public reply.
 (1992, 1980, 1001, 'PLATFORM',
  'Custody chain on SHP-SEED-0003 has no COLLECTED event at all — the parcel is still DRIVER_OFFERED. The seller is describing a different shipment.',
  TRUE, @NOW);

INSERT INTO dispute_evidence
 (id, dispute_id, uploaded_by_user_id, uploaded_by_side, url, content_type, caption, created_at)
VALUES
 -- The caption is required for a reason: without it a moderator has a
 -- photograph of a doorway and no idea what they are meant to see in it.
 (1995, 1980, 1005, 'BUYER',
  'https://media.example.invalid/disputes/doorway-2026-09-12.jpg', 'image/jpeg',
  'The delivery address on the afternoon the tracking says it was handed over.', @NOW);

-- ── review_reports ──────────────────────────────────────────────────────────
-- A report queues a person. It hides nothing, and the review it names (1311,
-- four stars, "slow to charge") is still visible — which is the whole design.
-- A marketplace where a review disappears because the seller objected has no
-- reviews worth reading, and the sellers who lose most by that are the honest
-- ones.
--
-- reviews.report_count is recounted from these rows rather than nudged, and the
-- table refuses a second report from the same person — so this counts people
-- rather than complaints.

INSERT INTO review_reports
 (id, review_id, reported_by_user_id, reason, detail,
  reviewed_at, reviewed_by_user_id, moderator_note, created_at)
VALUES
 (1998, 1311, 1002, 'FALSE_CLAIM',
  'The charger in the box is the 25W one. This is not accurate.',
  NULL, NULL, NULL, @NOW);


-- ── message_threads, thread_messages ────────────────────────────────────────
-- Every thread hangs off an order or a product and there is no third option.
-- That is what stops this being a messaging service: nobody can open a channel
-- to a stranger, because there is no row shape for one.
--
-- 2011 is the filter doing its job. What the seller typed is in original_body
-- and only a moderator ever reads it; what everybody reads has the number taken
-- out. Both are kept — a buyer whose innocent sentence was mangled needs
-- somebody to be able to look, and a seller who tries this every week leaves a
-- pattern that only exists if the attempts were kept.
--
-- The reason it is taken out at all: a sale arranged privately has no escrow,
-- so money sent is gone; no custody chain, so nobody can prove what arrived;
-- no dispute and no refund. On this route the buyer is on another continent
-- from the goods, which makes them the person least able to walk in and
-- complain.

INSERT INTO message_threads
 (id, version, subject, order_id, product_id, buyer_user_id, vendor_id, title,
  last_message_at, unread_for_buyer, unread_for_vendor, closed_at, created_at)
VALUES
 -- Oliver asked and the seller answered, so the unread message is the seller's
 -- and it is unread by the buyer. The counters are per side and are recomputed
 -- from the messages rather than nudged: a counter that is incremented drifts,
 -- and what this one drifts into is a badge nobody can clear.
 (2000, 0, 'ORDER', 1401, NULL, 1005, 1101, 'Where is the phone?',
  @NOW, 1, 0, NULL, @NOW),
 -- No purchase behind this one, and none needed: somebody deciding whether to
 -- buy is exactly who has to be able to ask. Nobody has answered yet, so it is
 -- the seller who has something waiting.
 (2001, 0, 'PRODUCT', NULL, 1301, 1006, 1101, 'Is it dual sim?',
  @NOW, 0, 1, NULL, @NOW);

INSERT INTO thread_messages
 (id, thread_id, sender_user_id, sender_side, body, original_body,
  filtered, filtered_kinds, read_at, created_at)
VALUES
 (2010, 2000, 1005, 'BUYER',
  'The tracking has said collected for four days. Has it actually left the shop?',
  NULL, FALSE, NULL, @NOW, @NOW),
 (2011, 2000, 1002, 'VENDOR',
  'It went out with the driver on Tuesday. Ring me on [removed] and I will check with him.',
  'It went out with the driver on Tuesday. Ring me on 3100002 and I will check with him.',
  TRUE, 'PHONE', NULL, @NOW),
 (2012, 2001, 1006, 'BUYER',
  'Does the A16 take two sim cards, or one sim and a memory card?',
  NULL, FALSE, NULL, NULL, @NOW);

-- ── sanctions ───────────────────────────────────────────────────────────────
-- Why an account is locked, rather than the fact that it is. The same shape as
-- the custody chain and the money ledger: the sanction is the record and
-- users.enabled is the consequence, recomputed from these rows by
-- SanctionRegistry. There is no other way to disable an account, so there is no
-- account disabled for a reason nobody wrote down.
--
-- 2200 is why user 1013 has enabled = 0. Note expires_at: the suspension is
-- over on that date whether or not anything sweeps it, because every gate
-- resolves against these rows rather than against the flag. An outage cannot
-- leave somebody locked out for an extra week.
--
-- 2201 is lifted rather than deleted, and that is deliberate. "We suspended you
-- for a week and then agreed we should not have" is a different history from
-- "nothing happened", and the person it was applied to knows which.
--
-- 2202 is a restriction rather than a lock: Mariama can still sign in, answer
-- her buyers and fulfil what she has already sold — she just cannot list
-- anything new while the case is open. Locking the whole account would have
-- punished her existing buyers for something she is only accused of.

INSERT INTO sanctions
 (id, user_id, type, reason, reason_text, restricted_permission,
  expires_at, lifted_at, lifted_by_user_id, lifted_reason,
  moderation_case_id, issued_by_user_id, created_at)
VALUES
 -- moderation_case_id is filled by the UPDATE below, once 2300 exists. The two
 -- tables reference each other — a sanction names the case it came out of, and
 -- the case names the sanction it issued — so no insert order satisfies both.
 (2200, 1013, 'SUSPENSION', 'OFF_PLATFORM_PAYMENT',
  'Asking buyers to complete payment by mobile money outside Sujula. Three messages on 11 September were filtered for contact details before this was raised.',
  NULL, @FUTURE, NULL, NULL, NULL, NULL, 1001, @NOW),
 (2201, 1009, 'WARNING', 'ABUSIVE_CONDUCT',
  'Language used in a message to a seller.',
  NULL, NULL, @NOW, 1001, 'The message was in Wolof and was mistranslated by the reporting tool. Nothing abusive was said.',
  NULL, 1001, @NOW),
 (2202, 1010, 'FEATURE_RESTRICTION', 'MISLEADING_LISTING',
  'New listings held for review while an open case about product descriptions is decided.',
  'CATALOGUE_WRITE', @FUTURE, NULL, NULL, NULL, NULL, 1001, @NOW);

-- ── moderation_cases ────────────────────────────────────────────────────────
-- One queue for every allegation, whatever raised it: a buyer's report, a
-- seller's, a support agent noticing a pattern, or a rule firing on its own.
-- Having one rather than four is what makes "this seller has two open cases" a
-- fact rather than something somebody happens to remember.
--
-- subject_type and subject_id are a polymorphic reference, which is the one
-- place in this schema that earns itself: a product can be deleted and a review
-- hidden, and a case that vanished with its subject would take the reason
-- somebody was banned with it. subject_label is carried for the same reason —
-- it still reads correctly after the listing is gone.
--
-- due_by is frozen when the case is raised rather than computed from today's
-- policy. A queue sorted by a deadline that moves with policy is one where the
-- oldest case is never the most urgent. 2301's is in the past on purpose: it is
-- the overdue row the queue must surface first.

INSERT INTO moderation_cases
 (id, version, reference, status, reason,
  subject_type, subject_id, subject_label, accountable_user_id, vendor_id,
  raised_by_user_id, source, description, evidence_urls,
  assigned_to_user_id, assigned_at, due_by,
  resolved_by_user_id, resolved_at, outcome, resolution_note, sanction_id,
  created_at, updated_at)
VALUES
 (2300, 0, 'CASE-7XK4MQ2BVN', 'RESOLVED', 'OFF_PLATFORM_PAYMENT',
  'USER', 1013, 'Alieu Manneh — Basse Phone Repair', 1013, NULL,
  NULL, 'AUTOMATED',
  'Three messages filtered for contact details in one day, all to different buyers on unpaid orders.',
  NULL,
  1001, @NOW, @NOW,
  1001, @NOW, 'UPHELD',
  'Pattern is clear from the filtered originals. Thirty days, and the account comes back by itself.',
  NULL, @NOW, @NOW),

 -- Open, overdue, and assigned to support — who can read it and add to it, and
 -- cannot resolve it. Only an administrator issues a sanction.
 (2301, 0, 'CASE-9QRT4XKM2B', 'IN_REVIEW', 'MISLEADING_LISTING',
  'PRODUCT', 1305, 'Wax Print — Six Yards, Indigo (Teranga Mobile)', 1010, 1102,
  1005, 'USER_REPORT',
  'Buyer says the cloth that arrived is five yards, not six, and that two other buyers have said the same in the reviews.',
  'https://media.example.invalid/cases/wax-measured.jpg',
  1012, @NOW, @LAPSED,
  NULL, NULL, NULL, NULL, NULL, @NOW, @NOW),

 -- Raised and untouched. What an empty queue looks like when it is not empty.
 (2302, 0, 'CASE-2MHB6VXQ4T', 'OPEN', 'RATING_MANIPULATION',
  'REVIEW', 1313, 'Four-star review on the Nokia 105 by Modou Sanneh', 1006, NULL,
  1002, 'USER_REPORT',
  'Seller says the reviewer never bought this product. reviews.verified is 0 on that row, which is worth checking rather than acting on.',
  NULL,
  NULL, NULL, @FUTURE,
  NULL, NULL, NULL, NULL, NULL, @NOW, @NOW);

-- The two halves of the link, now that both sides exist. Written as updates for
-- the same reason the return/dispute pair is: neither table can be inserted
-- with both ends filled, and the services do exactly this — raise one, then
-- point the other at it.
UPDATE sanctions        SET moderation_case_id = 2300 WHERE id = 2200;
UPDATE sanctions        SET moderation_case_id = 2301 WHERE id = 2202;
UPDATE moderation_cases SET sanction_id = 2200        WHERE id = 2300;

-- ── delivery_routes ─────────────────────────────────────────────────────────
-- A driver's run for one day. delivery_ids and optimized_order are TEXT lists,
-- not foreign keys, so nothing enforces that the ids below exist.

INSERT INTO delivery_routes (id, driver_id, route_date, delivery_ids, optimized_order, total_estimated_distance_km, estimated_duration_minutes, created_at, updated_at) VALUES
 (1740, 1090, '2026-09-12', '1700,1701', '1700,1701', 12.40, 55, @NOW, @NOW);

-- ── user_sessions ───────────────────────────────────────────────────────────
-- Devices that are signed in. The refresh tokens are real: each hash below is
-- the SHA-256 of a token printed in the notes at the end of this file, so the
-- seeded sessions can actually be exercised against POST /auth/refresh rather
-- than only looked at.
--
-- Tokens are stored hashed and never in clear — the column holds the digest,
-- not the credential. SHA-256 rather than BCrypt on purpose: a 256-bit random
-- token has nothing to guess, so the slow hash buys nothing and would make the
-- lookup-by-hash on every refresh impossible.

INSERT INTO user_sessions
 (id, user_id, refresh_token_hash, previous_token_hash, device_label, user_agent,
  ip_address, country_code, created_at, last_seen_at, expires_at, revoked_at, revoked_reason, version)
VALUES
 -- Aminata on two devices. The phone is what she is holding; the laptop is
 -- yesterday's, and is what GET /me/sessions shows her she could sign out.
 (1801, 1004, '3603c99a7dee6931f58bba6ebeaa0936e67ff1e3939052f692eb481fdfdd6139', NULL,
  'Chrome on Android', 'Mozilla/5.0 (Linux; Android 14; Infinix X669C) AppleWebKit/537.36',
  '41.222.10.14', 'GM', '2026-09-11 08:15:00.000000', @NOW, @FUTURE, NULL, NULL, 0),
 (1802, 1004, '7afaf5d4b71d9447aa0afd3fdb0e2db0333c434b4656dcdc504e6532c585d90f', NULL,
  'Firefox on Windows', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/131.0',
  '41.222.10.14', 'GM', '2026-09-05 19:40:00.000000', '2026-09-11 21:02:00.000000',
  @FUTURE, NULL, NULL, 0),

 -- A vendor and a buyer abroad, so the device list is not all one country.
 (1803, 1002, 'ef18219e7ec9ab4e1d18b2b565b2c8f25e5a0743a7b1b204d036e3354a832ed8', NULL,
  'Safari on iOS', 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15',
  '41.222.11.90', 'GM', '2026-09-09 07:05:00.000000', @NOW, @FUTURE, NULL, NULL, 0),
 (1804, 1005, '1983a183253caeb411fe8000aa7042c6cbc7c3a64adc919e48227c7821a30cf7', NULL,
  'Chrome on macOS', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/537.36',
  '86.140.22.7', 'GB', '2026-09-10 11:30:00.000000', @NOW, @FUTURE, NULL, NULL, 0),

 -- One session that has already rotated once. Refreshing with the token behind
 -- previous_token_hash is a replay: the legitimate client moved on to the
 -- current one, so whoever presents the old one is not it, and the session is
 -- revoked rather than refreshed. This row is what makes that demonstrable.
 (1805, 1005, 'a1b7c3d9e5f2048516273849506172839405162738495061728394051627384a',
  '30550aca7cd587d9156420d022eea8c062cf096a0d17fbbf3b157aec3af01198',
  'Edge on Windows', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Edg/129.0',
  '86.140.22.7', 'GB', '2026-09-08 06:00:00.000000', @NOW, @FUTURE, NULL, NULL, 1),

 -- Ended, for the two reasons a session ends without the user asking.
 (1806, 1006, '82b45eeac3a87adc0008792f9808d99b1daf8e271b92fc9467cfb235297c886d', NULL,
  'Chrome on Android', 'Mozilla/5.0 (Linux; Android 13; TECNO KI5k) AppleWebKit/537.36',
  '41.222.14.3', 'GM', '2026-07-02 10:00:00.000000', '2026-07-20 10:00:00.000000',
  @LAPSED, NULL, NULL, 0),
 (1807, 1007, 'f5a38a4c7aa9f0890acd64eead5ce83340d54b8a12611735bafc8121d014fee5', NULL,
  'Sujula Driver App', 'SujulaDriver/1.4 (Android 14)',
  '41.222.19.51', 'GM', '2026-09-01 05:30:00.000000', '2026-09-07 18:45:00.000000',
  '2026-10-01 05:30:00.000000', '2026-09-07 18:46:00.000000', 'LOGOUT', 0);

-- ── mfa_recovery_codes ──────────────────────────────────────────────────────
-- Only the hashes are kept, exactly as the API does it — the codes themselves
-- are shown to the user once at activation and never stored. The three below
-- belong to Aminata, whose account has an authenticator enrolled; the raw codes
-- are in the notes so the recovery path can be tested end to end.

INSERT INTO mfa_recovery_codes (id, user_id, code_hash, used_at, created_at) VALUES
 (1811, 1004, '$2a$10$dM0/ckvU.j.bM2GGA9c4d.nV6yymwjZ3456a30hpLmyxmKcGghxw.', NULL, @NOW),
 (1812, 1004, '$2a$10$uYdUdeiuxQOS3gqGSUGds.YP8XXAIs2o7owjnpOypDYoz2Ul7fJVW', NULL, @NOW),
 -- Spent. A recovery code is single use, and a used one is kept rather than
 -- deleted so the count of what remains stays honest.
 (1813, 1004, '$2a$10$01O9ffCkLM0EmIXTrCw3z.g76vqOGdF5C3SygX4PEZSkuJTVMiYqK',
  '2026-09-10 22:14:00.000000', @NOW);

-- ── oauth_accounts ──────────────────────────────────────────────────────────
-- Identity is the provider's subject claim and never the email address. A
-- person who changes their Google address keeps their account; someone who
-- later acquires an address another person once used does not inherit theirs.

INSERT INTO oauth_accounts (id, user_id, provider, provider_user_id, email, linked_at, last_used_at) VALUES
 (1821, 1005, 'GOOGLE', '109327741556028834421', 'oliver.bennett@example.co.uk',
  '2026-08-14 09:12:00.000000', @NOW),
 -- Same person, second provider. Apple's private relay address is deliberate:
 -- it is what Apple actually returns when someone hides their email, and code
 -- that assumes an address is routable or unique-per-person breaks on it.
 (1822, 1005, 'APPLE', '001432.8f3c9a2b4e5d6f71.0913', 'k7m2x9p4qz@privaterelay.appleid.com',
  '2026-08-20 20:45:00.000000', NULL),
 (1823, 1004, 'GOOGLE', '117884920037451126690', 'aminata.ceesay@example.gm',
  '2026-06-03 14:20:00.000000', '2026-09-11 08:15:00.000000');

-- ── phone_verifications ─────────────────────────────────────────────────────
-- A number is claimed here and written to the profile only once the code is
-- confirmed, so an unconfirmed attempt never changes the account. Codes are
-- hashed like passwords: a six-digit code is guessable, which is why the row
-- carries an attempt count and an expiry.

INSERT INTO phone_verifications (id, user_id, phone, code_hash, attempts, expires_at, confirmed_at, created_at) VALUES
 -- Live: Modou is mid-verification. The code is in the notes; POST
 -- /auth/verify-phone/confirm with it completes the flow.
 (1831, 1006, '+2203100006', '$2a$10$w6T6bwAzzN1PofDiHXYezu/YIJLLfpsAOu8Tn2kFAoTjkFLUIyafq',
  0, @SOON, NULL, @NOW),
 -- Already confirmed, and kept: the history of which numbers were proved when
 -- is worth more than the row is expensive.
 (1832, 1004, '+2203100004', '$2a$10$w6T6bwAzzN1PofDiHXYezu/YIJLLfpsAOu8Tn2kFAoTjkFLUIyafq',
  1, '2026-06-01 10:05:00.000000', '2026-06-01 10:02:00.000000', '2026-06-01 10:00:00.000000'),
 -- Burnt through its attempts and expired. This is what a failed verification
 -- looks like, and the row is why a fourth attempt is refused rather than
 -- silently starting over.
 (1833, 1009, '+2203100009', '$2a$10$w6T6bwAzzN1PofDiHXYezu/YIJLLfpsAOu8Tn2kFAoTjkFLUIyafq',
  5, '2026-08-30 12:00:00.000000', NULL, '2026-08-30 11:55:00.000000');

-- ── account_data_requests ───────────────────────────────────────────────────
-- The data-protection queue. Erasure on this platform is pseudonymisation:
-- orders, payments and payouts are financial records that must be kept, so the
-- rows stay and the personal data in them is overwritten. Deleting a buyer
-- would tear the referential heart out of every order they ever placed.

INSERT INTO account_data_requests
 (id, user_id, type, status, reference, requested_at, started_at, completed_at,
  download_url, download_expires_at, failure_reason)
VALUES
 -- Finished. The payload is stored on the row rather than in a bucket: an
 -- export is the most concentrated personal data the platform ever produces,
 -- and a bucket is one misconfiguration away from being public.
 (1841, 1005, 'EXPORT', 'COMPLETED', 'EXP-7F3A2B91',
  '2026-09-09 10:00:00.000000', '2026-09-09 10:01:00.000000', '2026-09-09 10:01:30.000000',
  'data:application/json;base64,eyJleHBvcnRlZEF0IjoiMjAyNi0wOS0wOVQxMDowMTozMCJ9',
  @FUTURE, NULL),
 -- Waiting for the worker, which picks it up within the minute.
 (1842, 1006, 'EXPORT', 'PENDING', 'EXP-C40D18E2', @NOW, NULL, NULL, NULL, NULL, NULL),
 -- An erasure in flight. Asking again while this is open returns this row
 -- rather than queueing a second, so a retry on a bad connection cannot start
 -- two erasure jobs racing over one account.
 (1843, 1009, 'ERASURE', 'PROCESSING', 'ERA-9B21EF07',
  '2026-09-12 08:58:00.000000', '2026-09-12 08:59:00.000000', NULL, NULL, NULL, NULL),
 -- Failed, with the reason kept for the person who asked. A job that fails
 -- silently is a data-protection request nobody knows went missing.
 (1844, 1003, 'EXPORT', 'FAILED', 'EXP-1D5C77A4',
  '2026-08-28 16:00:00.000000', '2026-08-28 16:01:00.000000', '2026-08-28 16:01:12.000000',
  NULL, NULL, 'Could not serialise the export: order 1402 had no currency recorded');

-- ── delivery_contexts ───────────────────────────────────────────────────────
-- Where a basket is going, resolved once so the cart, the quote and checkout
-- all price against the same destination rather than three copies of it that
-- drift apart.
--
-- The ids here start with 'seed-' so the delete block above can find them: real
-- ones are 256 bits of base64url from a secure random, because for a guest the
-- id is the only thing standing between a stranger and their home address. A
-- readable id is fine in a seed and would be a vulnerability in production.
--
-- @FUTURE and @LAPSED, not fixed timestamps: a context seeded to expire on a
-- date in this file is expired before anyone runs it, and an expired context
-- reads as one that never existed.
--
-- pickup_point_id is a plain column rather than a mapped association, so the
-- database will accept an id that points at nothing. 1096 is the only seeded
-- hub; check it by hand when adding a row here, because nothing else will.

INSERT INTO delivery_contexts
 (id, user_id, latitude, longitude, address_line, city, state, postal_code, country_code,
  geocode_confidence, mode, pickup_point_id, address_id, currency, language, timezone,
  created_at, expires_at)
VALUES
 -- Aminata, from her saved and pin-confirmed address. Home delivery to a point
 -- a rider can actually navigate to.
 ('seed-ctx-aminata-home', 1004, 13.43950000, -16.67520000,
  '27 Sayerr Jobe Avenue', 'Serekunda', 'West Coast', NULL, 'GM',
  'USER_CONFIRMED', 'HOME_DELIVERY', NULL, 1050, 'GMD', 'en', 'Africa/Banjul', @NOW, @FUTURE),

 -- A guest: no account, and the id is the whole of their claim to this row.
 -- This is the common case — most shopping happens before anyone signs in.
 ('seed-ctx-guest-brikama', NULL, 13.27140000, -16.64920000,
  'Brikama Market Road', 'Brikama', 'West Coast', NULL, 'GM',
  'CENTROID', 'HOME_DELIVERY', NULL, NULL, 'GMD', 'en', 'Africa/Banjul', @NOW, @FUTURE),

 -- A guest delivering to Serrekunda, which is what cart 1071 prices against.
 -- It has to be a GUEST row rather than Aminata's: a context belonging to an
 -- account is readable only by that account, deliberately, so a leaked id
 -- cannot hand a stranger a signed-in shopper's home address. Cart 1071 pointed
 -- at hers, so the guest who owns that basket could not resolve its destination
 -- and it priced with no shipping at all — and, until the fix that came with
 -- this change, answered 500.
 --
 -- Same pin as Aminata's home, so the delivery arithmetic in the notes at the
 -- end of this file still holds: the leg from Kairaba Avenue is about half a
 -- kilometre.
 ('seed-ctx-guest-serrekunda', NULL, 13.43950000, -16.67520000,
  '27 Sayerr Jobe Avenue', 'Serekunda', 'West Coast', NULL, 'GM',
  'USER_CONFIRMED', 'HOME_DELIVERY', NULL, NULL, 'GMD', 'en', 'Africa/Banjul', @NOW, @FUTURE),

 -- Collection from a hub. No destination pin at all, and still deliverable:
 -- the parcel goes to the pickup point and the buyer collects it.
 ('seed-ctx-guest-pickup', NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, 'GM',
  'NONE', 'PICKUP_POINT', 1096, NULL, 'GMD', 'en', 'Africa/Banjul', @NOW, @FUTURE),

 -- Oliver in London, shopping in sterling. Cross-border, which is what makes
 -- the serviceability answer interesting.
 ('seed-ctx-oliver-london', 1005, 51.52370000, -0.15850000,
  '221B Baker Street Flat 2', 'London', 'Greater London', 'NW1 6XE', 'GB',
  'EXACT', 'HOME_DELIVERY', NULL, 1052, 'GBP', 'en', 'Europe/London', @NOW, @FUTURE),

 -- Expired, and therefore indistinguishable from one that never existed.
 -- GET /delivery-contexts/seed-ctx-expired returns 404, not 410.
 ('seed-ctx-expired', NULL, 13.44000000, -16.67000000,
  'Kairaba Avenue', 'Serekunda', NULL, NULL, 'GM',
  'CENTROID', 'HOME_DELIVERY', NULL, NULL, 'GMD', 'en', 'Africa/Banjul',
  '2026-09-01 08:00:00.000000', @LAPSED);

-- ── idempotency_records ─────────────────────────────────────────────────────
-- The answer already given to a request that is being made again.
--
-- Seeded so the replay path can be exercised without first having to lose a
-- response: POST /me/addresses as Aminata with Idempotency-Key
-- 'seed-key-aminata-home' returns the recorded body instead of saving a second
-- address, and the same key with a different body is refused rather than
-- served — which is the check that stops a client reusing one key for a whole
-- session from silently discarding requests.
--
-- The scope is the caller and the endpoint together. Neither alone is enough:
-- without the caller, two shoppers who pick the same key collide; without the
-- endpoint, one key used twice returns the wrong operation's answer.

INSERT INTO idempotency_records
 (id, scope, idempotency_key, request_fingerprint, response_status, response_body,
  created_at, expires_at)
VALUES
 (1850, 'user:1004:me.addresses.create', 'seed-key-aminata-home',
  '0000000000000000000000000000000000000000000000000000000000000000', 201,
  '{"id":1050,"label":"Home","city":"Serekunda","countryCode":"GM","confidence":"USER_CONFIRMED"}',
  @NOW, @FUTURE),

 -- A guest's key. Guests share one scope per endpoint, which is why the
 -- fingerprint check is not optional.
 (1851, 'anon:delivery-contexts.create', 'seed-key-guest-context',
  '1111111111111111111111111111111111111111111111111111111111111111', 201,
  '{"id":"seed-ctx-guest-brikama","guest":true,"countryCode":"GM","currency":"GMD"}',
  @NOW, @FUTURE);

-- ── fx_quotes ───────────────────────────────────────────────────────────────
-- A rate held still long enough for someone to pay at it.
--
-- Between seeing a total and finishing a card form a buyer spends a minute or
-- two, and an indicative rate moves in that time. Without a held quote the
-- platform either charges a different figure from the one agreed, or absorbs
-- the difference silently on every order. A quote makes the commitment explicit
-- and bounded.
--
-- The rates below match the exchange_rates rows seeded above, which is the
-- point: a quote records the rate as it stood when it was taken, and does not
-- follow the table afterwards. Change rate 1220 and re-read
-- seed-fx-oliver-gbp — it still says 0.011.
--
-- Ids start with 'seed-' so the delete block can find them. Real ones are 256
-- bits of base64url from a secure random, because for a guest the id is the
-- whole of their claim to the quote.

INSERT INTO fx_quotes
 (id, user_id, base_currency, quote_currency, rate, base_amount, quote_amount,
  rate_fetched_at, created_at, expires_at, consumed_at)
VALUES
 -- Oliver, mid-checkout in sterling. Live, unspent, and the rate is frozen.
 ('seed-fx-oliver-gbp', 1005, 'GMD', 'GBP', 0.01100000, 4500.0000, 49.5000,
  '2026-09-12 00:00:00.000000', @NOW, @SOON, NULL),

 -- A guest pricing in CFA. Note the converted amount is a whole franc: XOF has
 -- no minor units, so 3870.50 is not an amount anyone could hand over.
 ('seed-fx-guest-xof', NULL, 'GMD', 'XOF', 8.60000000, 450.0000, 3870.0000,
  '2026-09-12 00:00:00.000000', @NOW, @SOON, NULL),

 -- Already spent on an order. Kept rather than deleted: months from now this is
 -- the evidence of what rate a buyer was actually promised.
 ('seed-fx-consumed', 1005, 'GMD', 'GBP', 0.01100000, 11793.6400, 129.7300,
  '2026-09-12 00:00:00.000000', '2026-09-12 08:40:00.000000', @FUTURE, @NOW),

 -- Past its window. GET /currencies/quote/seed-fx-expired is a 404, not a 410:
 -- an expired bearer credential and one that never existed should look the same.
 ('seed-fx-expired', NULL, 'GMD', 'USD', 0.01400000, 1000.0000, 14.0000,
  '2026-09-12 00:00:00.000000', '2026-09-12 07:00:00.000000', @LAPSED, NULL);

-- ── product_questions ───────────────────────────────────────────────────────
-- What a shopper asked about a listing, and what the seller said.
--
-- These matter more here than on most marketplaces. Oliver in London cannot
-- pick the phone up, and Aminata — who will actually use it — is not in the
-- conversation at all. "Does the charger have a UK plug" is not idle curiosity;
-- it is the only way to find out.
--
-- Visibility is a consequence of moderation, never of the row existing. A
-- question with approved_at NULL is not merely hidden by a service that
-- remembers to filter — the query does not return it. All four states are here
-- because a client has to render each differently.

INSERT INTO product_questions
 (id, product_id, asked_by_user_id, question, answer,
  answered_by_user_id, answered_at, approved_at, approved_by_user_id,
  rejected_reason, rejected_at, created_at)
VALUES
 -- Approved and answered: what a shopper came for, and what sorts first.
 (1900, 1301, 1005,
  'Does this come with a charger, and is it a UK or two-pin plug?',
  'It ships with a two-pin European charger. We include a UK adapter free on request — put a note on the order.',
  1002, '2026-09-10 14:20:00.000000', '2026-09-10 09:05:00.000000', 1001,
  NULL, NULL, '2026-09-10 08:40:00.000000'),

 -- Approved, not yet answered. Visible, because the question itself tells the
 -- next shopper that somebody else wondered the same thing.
 (1901, 1301, 1004,
  'Is the 256GB version available in black, or only the 128GB?',
  NULL, NULL, NULL, '2026-09-11 07:30:00.000000', 1001,
  NULL, NULL, '2026-09-11 07:12:00.000000'),

 -- Waiting for a moderator. GET /products/1301/questions must not return this.
 (1902, 1301, 1006,
  'Can you hold one for me until Friday? I get paid then.',
  NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, @NOW),

 -- Refused, with the reason kept for the person who asked. This is the case the
 -- moderation exists for: a phone number posted on somebody else's shopfront.
 (1903, 1305, 1009,
  'Message me on +2203100009, I can get you this cheaper elsewhere.',
  NULL, NULL, NULL, NULL, NULL,
  'Contact details and off-platform selling are not allowed in questions.',
  '2026-09-11 10:00:00.000000', '2026-09-11 09:45:00.000000'),

 -- A second product, so the listing page is not the only one with any.
 (1904, 1305, 1005,
  'Is the indigo colourfast? I am sending this as a gift and cannot return it easily.',
  'Yes — cold wash, separate for the first two washes. It holds.',
  1003, '2026-09-09 16:00:00.000000', '2026-09-09 12:00:00.000000', 1001,
  NULL, NULL, '2026-09-09 11:30:00.000000');

-- ── cart_quotes and cart_quote_lines ────────────────────────────────────────
-- A priced cart, held still long enough to be paid for.
--
-- Between seeing a total and finishing a card form a shopper spends a minute or
-- two, and in that time the exchange rate, the listed price and the stock all
-- move independently. A checkout that re-priced from the live catalogue would
-- show one figure and take another — which is the most corrosive thing a
-- marketplace can do to someone who has just sent a month's income to a country
-- they are not in.
--
-- Each line records the rate it was quoted at, so "you were charged this because
-- the phone is 8500 dalasi and the rate was 0.011" is answerable from one row.

INSERT INTO cart_quotes
 (id, cart_id, user_id, cart_fingerprint, display_currency, delivery_context_id,
  delivery_mode, pickup_point_id, subtotal, discount, shipping, tax, total,
  complete, created_at, expires_at, consumed_order_id, consumed_at)
VALUES
 -- Live and payable: the guest cart, in sterling, going to Serrekunda.
 ('seed-quote-live', 1071, NULL,
  '53ce845d43342eb1f2095345bb29048b3db34fed0389d130ca5056223875b857',
  'GBP', 'seed-ctx-aminata-home', 'HOME_DELIVERY', NULL,
  93.50, 0.00, 4.13, 0.00, 97.63, TRUE, @NOW, @SOON, NULL, NULL),

 -- Already spent on order 1401. Kept rather than deleted: months from now this
 -- is the evidence of what the buyer actually agreed to.
 ('seed-quote-consumed', 1070, 1004,
  '9c4517988c88c4fafbd8405a895549829f4531292b7677a036b32553054832b7',
  'GMD', 'seed-ctx-aminata-home', 'PICKUP_POINT', 1096,
  2500.00, 0.00, 110.00, 0.00, 2610.00, TRUE,
  '2026-09-12 08:30:00.000000', @FUTURE, 1403, '2026-09-12 08:35:00.000000'),

 -- Past its window. POST /checkout with it is a 404, not a 410: an expired
 -- bearer credential and one that never existed should look the same.
 ('seed-quote-expired', 1071, NULL,
  '53ce845d43342eb1f2095345bb29048b3db34fed0389d130ca5056223875b857',
  'GBP', 'seed-ctx-aminata-home', 'HOME_DELIVERY', NULL,
  93.50, 0.00, 4.13, 0.00, 97.63, TRUE,
  '2026-09-12 07:00:00.000000', @LAPSED, NULL, NULL),

 -- Unpriceable: one vendor's currency had no rate. It exists so a client can
 -- show what is wrong, and checkout refuses it — a total that silently omitted
 -- a vendor's goods would undercharge and the platform would owe the difference.
 ('seed-quote-incomplete', 1071, NULL,
  '0feca479f6af39a1826b9a72484c956aba1d9280f68c87a0573003ecdb5c82c8',
  'SEK', 'seed-ctx-aminata-home', 'HOME_DELIVERY', NULL,
  0.00, 0.00, 0.00, 0.00, 0.00, FALSE, @NOW, @SOON, NULL, NULL);

INSERT INTO cart_quote_lines
 (id, quote_id, product_id, variant_id, vendor_id, quantity,
  listing_currency, unit_price_native, line_total_native, unit_price, line_total,
  delivery_cost, distance_km, billable_weight_kg, deliverable, issue,
  fx_native_currency, fx_display_currency, fx_rate, fx_rate_at, fx_source, fx_quote_id)
VALUES
 -- 8500 GMD x 0.011 = 93.50 GBP, which is what the quote's subtotal says. A
 -- stored rate that does not reproduce the stored amount is decoration.
 (1950, 'seed-quote-live', 1301, 1350, 1101, 1,
  'GMD', 8500.00, 8500.00, 93.50, 93.50,
  4.13, 0.500, 0.195, TRUE, NULL,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL),

 -- Same currency both sides, so IDENTITY rather than a null: "no conversion
 -- applied" is a fact, not an absence.
 (1951, 'seed-quote-consumed', 1303, NULL, 1101, 2,
  'GMD', 1250.00, 2500.00, 1250.00, 2500.00,
  110.00, 0.500, 2.480, TRUE, NULL,
  'GMD', 'GMD', 1.00000000, '2026-09-12 08:30:00.000000', 'IDENTITY', NULL),

 (1952, 'seed-quote-expired', 1301, 1350, 1101, 1,
  'GMD', 8500.00, 8500.00, 93.50, 93.50,
  4.13, 0.500, 0.195, TRUE, NULL,
  'GMD', 'GBP', 0.01100000, '2026-09-12 00:00:00.000000', 'PUBLISHED_RATE', NULL),

 -- No rate into SEK, so no converted figures and no snapshot. Recording a rate
 -- here would be a fiction that looked like evidence.
 (1953, 'seed-quote-incomplete', 1301, 1350, 1101, 1,
  'GMD', 8500.00, 8500.00, NULL, NULL,
  0.00, NULL, 0.195, TRUE, 'No exchange rate from GMD to SEK.',
  NULL, NULL, NULL, NULL, NULL, NULL);

-- ── delivery_zones ──────────────────────────────────────────────────────────
-- Polygons, and the one mistake that would otherwise fail silently.
--
-- Coordinates are GeoJSON's own [longitude, latitude], which is the opposite of
-- how everybody says it out loud. Get it the wrong way round and 13.44,-16.69
-- becomes -16.69,13.44 — a perfectly legal position in the Gulf of Guinea. The
-- shape parses, stores, and contains no Gambian address ever, and nobody finds
-- out for a month. Both the upload endpoint and CountryBounds refuse it; these
-- rows are here so the right way round has an example to copy.
--
-- min/max latitude and longitude are DERIVED from the geometry by ZoneRegistry
-- on every write. They are listed explicitly here because a raw INSERT bypasses
-- that, and a box that disagrees with its own polygon silently excludes
-- addresses that are inside the shape. If you edit a geometry below, edit its
-- box too — or load it through POST /admin/zones, which does it for you.
--
-- Overlap is deliberate: GM-SRK sits inside GM-KOMBO, and priority is what
-- decides which wins. That is how a denser city rate is expressed, not a
-- mistake to be prevented.

INSERT INTO delivery_zones
 (id, code, name, description, country_code, geometry,
  min_latitude, max_latitude, min_longitude, max_longitude, vertex_count,
  serviceable, unserviceable_reason, priority, active,
  last_edited_by_user_id, created_at, updated_at)
VALUES
 (2400, 'GM-SRK', 'Serrekunda', 'The dense part of the Kombos — short legs, many of them.', 'GM',
  '{"type":"Polygon","coordinates":[[[-16.72,13.42],[-16.66,13.42],[-16.66,13.47],[-16.72,13.47],[-16.72,13.42]]]}',
  13.42, 13.47, -16.72, -16.66, 5,
  TRUE, NULL, 10, TRUE, 1001, @NOW, @NOW),

 (2401, 'GM-KOMBO', 'Greater Kombo', 'Everything from Banjul out to Brikama.', 'GM',
  '{"type":"Polygon","coordinates":[[[-16.80,13.30],[-16.50,13.30],[-16.50,13.60],[-16.80,13.60],[-16.80,13.30]]]}',
  13.30, 13.60, -16.80, -16.50, 5,
  TRUE, NULL, 1, TRUE, 1001, @NOW, @NOW),

 -- Drawn, priced, and not being delivered to. A zone is how a platform says
 -- "not here" about somewhere it has looked at; deleting it would lose both the
 -- shape and the reason, and a shopper would be told "unavailable" rather than
 -- something they can act on.
 (2402, 'GM-NBR', 'North Bank', 'Across the river. Ferry-dependent.', 'GM',
  '{"type":"Polygon","coordinates":[[[-16.60,13.50],[-16.20,13.50],[-16.20,13.80],[-16.60,13.80],[-16.60,13.50]]]}',
  13.50, 13.80, -16.60, -16.20, 5,
  FALSE, 'The Barra ferry is out of service. We expect to be delivering again in October.',
  5, TRUE, 1001, @NOW, @NOW),

 (2403, 'SN-DKR', 'Dakar', 'Teranga Textiles collects here.', 'SN',
  '{"type":"Polygon","coordinates":[[[-17.55,14.65],[-17.35,14.65],[-17.35,14.80],[-17.55,14.80],[-17.55,14.65]]]}',
  14.65, 14.80, -17.55, -17.35, 5,
  TRUE, NULL, 10, TRUE, 1001, @NOW, @NOW);

-- ── driver_zone_coverage ────────────────────────────────────────────────────
-- What the platform decided about where a driver works, which is not the same
-- as drivers.zone — that is the free-text area they typed on their own
-- application. Ebrima wrote "Kombo North"; this says Serrekunda and Greater
-- Kombo, and this is what dispatch reads. Collapsing the two would let a driver
-- widen their own coverage by editing a text field.

INSERT INTO driver_zone_coverage (driver_id, zone_id) VALUES
 (1090, 2400),
 (1090, 2401);

-- ── delivery_rate_cards ─────────────────────────────────────────────────────
-- What carriage costs, from a day forward.
--
-- Effective-dated for the same reason commission is: an order priced in August
-- was priced under the August card, and a card written today must not be able
-- to reach back and make that total something nobody can reproduce. The service
-- refuses a start date in the past for exactly this reason; these rows are
-- seeded with one anyway, because a seed with no history has nothing to
-- demonstrate.
--
-- 2410 and 2411 are the same scope on either side of a boundary: the first ran
-- until the end of August, the second from the first of September with no end.
-- Ask the platform what a leg cost on the twentieth of August and it answers
-- from 2410 — which is the whole point, and the thing that breaks the moment
-- somebody edits a card's numbers instead of writing a new one.
--
-- 2412 is denser and more specific: one zone, one mode. Most specific wins, so
-- a home delivery inside Serrekunda is priced from it rather than from the
-- national card, and a hub run in the same zone is not.
--
-- Currency is the PLATFORM's, not the buyer's and not the vendor's. It is
-- converted once, at a snapshotted rate, like every other figure somebody is
-- charged.

INSERT INTO delivery_rate_cards
 (id, name, zone_id, country_code, mode, currency,
  base_fee, included_km, per_km, included_kg, per_kg, min_fee, max_fee, free_above,
  effective_from, effective_until, note, active, created_by_user_id,
  created_at, updated_at)
VALUES
 (2410, 'Gambia standard — opening', NULL, 'GM', NULL, 'GMD',
  45.00, 0.00, 10.00, 1.00, 20.00, 45.00, NULL, NULL,
  '2026-06-01', '2026-08-31', 'What we opened with.', TRUE, 1001, @NOW, @NOW),

 (2411, 'Gambia standard', NULL, 'GM', NULL, 'GMD',
  50.00, 0.00, 12.00, 1.00, 25.00, 50.00, 2500.00, 15000.00,
  '2026-09-01', NULL, 'Fuel. Free delivery above 15,000 with one seller.',
  TRUE, 1001, @NOW, @NOW),

 (2412, 'Serrekunda door', 2400, 'GM', 'HOME_DELIVERY', 'GMD',
  30.00, 2.00, 8.00, 1.00, 20.00, 30.00, 900.00, NULL,
  '2026-09-01', NULL, 'Short legs, many of them — the national card overcharges here.',
  TRUE, 1001, @NOW, @NOW),

 -- Senegal has a card in XOF, which has NO minor units. 1250.50 CFA is not an
 -- amount that exists, so every figure here is whole — and CurrencyCatalogue,
 -- not this file, is what enforces that when a leg is actually priced.
 (2413, 'Senegal standard', 2403, 'SN', NULL, 'XOF',
  1500.00, 0.00, 350.00, 1.00, 700.00, 1500.00, NULL, NULL,
  '2026-09-01', NULL, 'Dakar and the ring road.', TRUE, 1001, @NOW, @NOW);
-- ── callback_requests ───────────────────────────────────────────────────────
-- Somebody is going to telephone somebody, and what came of it.
--
-- Keyed on a PHONE NUMBER, not a user id, and 2602 is why that matters: Isatou
-- Ceesay is the recipient of SHP-SEED-0002 and has no account on this platform
-- at all. Her number lives on the shipment because she never signed up for
-- anything — she is the sister in Serrekunda the whole marketplace exists to
-- serve (C5), and a callback table keyed on users could not reach her.
--
-- The intent and the outcome are separate columns because they are separate
-- facts. "We said we would call", "we called and she did not answer" and "we
-- called and she said the seal was cut" are three states, and a platform that
-- stored only the first cannot tell a supervisor which calls are still owed.
-- NO_ANSWER and RESCHEDULED still count as owed; SPOKE and UNREACHABLE do not.
--
-- preferred_language earns its column. A caller who opens in English to
-- somebody who speaks Wolof has already lost the call, and "she did not want to
-- talk" is what gets written down instead.
--
-- Note whose number 2600 is. Dispute 1980 is about SHP-SEED-0003, which was
-- going to Oliver in London — he bought it and it was for himself. Not every
-- parcel on this platform is a remittance, and the callback follows the parcel
-- rather than an assumption about it.

INSERT INTO callback_requests
 (id, dispute_id, phone, contact_name, preferred_language, reason,
  requested_by_user_id, requested_at, call_by,
  outcome, called_by_user_id, called_at, notes, attempts, updated_at)
VALUES
 -- Owed, overdue, and never attempted. The one a supervisor must see first.
 (2600, 1980, '+447700900005', 'Oliver Bennett', 'English',
  'He says nothing arrived; the seller says it was collected. Ask what he was actually told and by whom.',
  1012, @NOW, @LAPSED,
  NULL, NULL, NULL, NULL, 0, @NOW),

 -- Rang out once. Still owed: no answer is not a finished call.
 (2601, 1980, '+2203100007', 'Ebrima Sowe', NULL,
  'Driver side of the same parcel — did he collect it at all.',
  1012, @NOW, @FUTURE,
  'NO_ANSWER', 1012, @NOW, 'Rang out. Trying again this afternoon.', 1, @NOW),

 -- No account, no dispute, and the number comes off the parcel. This is the
 -- row that could not exist if callbacks were keyed on users.
 (2602, NULL, '+2203100077', 'Isatou Ceesay', 'Wolof',
  'Her parcel is out for delivery and the address has no street number. Confirm where the driver should stop.',
  1012, @NOW, @NOW,
  'SPOKE', 1012, @NOW,
  'Second gate past the mosque, blue door. She is in after four.',
  1, @NOW);

-- ── announcements ───────────────────────────────────────────────────────────
-- Something the platform told a group of people at once.
--
-- A record rather than a loop, and that earns its keep the first time somebody
-- asks "did the sellers in Senegal get told about the holiday schedule".
--
-- recipients is NOT segment_size. Somebody who has switched every channel off
-- for this event is in the segment and receives nothing, and reporting the
-- segment size as the reach would be reporting a number that is not true — 2610
-- below reached nine of eleven for exactly that reason.
--
-- The event is PLATFORM_NOTICE rather than PROMOTION, deliberately. A seller
-- who switched marketing off has not asked to be the last to know the platform
-- is closed on Koriteh.
--
-- country_code here is the RECIPIENTS' own country — where a seller trades,
-- where a driver carries. It is the one place on this platform where that is
-- the right question, because an announcement is about the person rather than
-- about a parcel.

INSERT INTO announcements
 (id, reference, title, body, audience_role, country_code, event,
  sent_by_user_id, sent_at, segment_size, recipients, created_at, updated_at)
VALUES
 (2610, 'ANN-7XK4MQ2BVN3D', 'No collections on Tuesday',
  'Tuesday is a public holiday. Drivers will not collect, and orders placed Monday evening will be collected Wednesday morning. Your handling time is not counted against you for that day.',
  'VENDOR', 'GM', 'PLATFORM_NOTICE',
  1001, @NOW, 11, 9, @NOW, @NOW),

 (2611, 'ANN-9QRT4XKM2BHV', 'New payout floor',
  'From October, balances below 500 GMD roll into the next run rather than being sent on their own. A transfer costs a fee whatever it carries, and below that it costs more than it moves.',
  'VENDOR', NULL, 'PLATFORM_NOTICE',
  1001, @NOW, 14, 14, @NOW, @NOW),

 -- Drawn up and not sent. sent_at NULL is the difference, and it is why reach
 -- is zero rather than unknown.
 (2612, 'ANN-2MHB6VXQ4TWK', 'Safe drop is coming to Banjul',
  'From next month you will be able to tell a driver where to leave a parcel if you are out. You will still get a code, and the driver still photographs where they left it.',
  'CUSTOMER', 'GM', 'PLATFORM_NOTICE',
  1001, NULL, 0, 0, @NOW, @NOW);

-- ── feature_flags ───────────────────────────────────────────────────────────
-- Switches somebody can throw without a deploy.
--
-- These rows are NOT the source of truth for which flags exist: FeatureFlags
-- declares them in code and writes any that are missing at startup, so a flag
-- added in code exists the first time the application runs with it. They are
-- here so a fresh database has them at sensible settings and so the seed can
-- demonstrate one that has been moved.
--
-- Every one carries who last threw it and why. A flag with no author is one
-- nobody will dare turn back on: six months later the only thing anybody knows
-- is that it is off, and turning it on becomes an act of faith.
--
-- client_visible is false on admin.impersonation on purpose. Telling a browser
-- whether impersonation is available tells anybody looking how the platform is
-- defended, and a flag list is exactly the sort of thing that ends up in a
-- public config endpoint by accident.

INSERT INTO feature_flags
 (id, flag_key, label, description, enabled, client_visible,
  last_changed_by_user_id, last_changed_at, last_change_reason, created_at, updated_at)
VALUES
 (2620, 'delivery.safe-drop', 'Safe drop',
  'Lets a driver leave a parcel without a code where the recipient has authorised it in advance. Off, every delivery needs a code at the door — slower, and impossible for somebody who is out at work.',
  TRUE, TRUE, NULL, NULL, 'Created with its default when the flag was introduced.', @NOW, @NOW),

 (2621, 'checkout.guest', 'Guest checkout',
  'Lets somebody buy without an account. Off, every buyer must register first, which costs orders from people sending goods home once.',
  TRUE, TRUE, NULL, NULL, 'Created with its default when the flag was introduced.', @NOW, @NOW),

 -- Moved, and the reason is on the flag rather than only in the audit log,
 -- because the person who finds it off in six months reads the flag.
 (2622, 'vendor.applications', 'Seller applications',
  'Whether new sellers may apply. Off while a backlog is cleared — existing sellers are unaffected and keep trading.',
  FALSE, TRUE, 1001, @NOW,
  'Nineteen applications waiting on KYC review. Back on once the queue is under five.',
  @NOW, @NOW),

 (2623, 'admin.impersonation', 'Administrator impersonation',
  'Lets an administrator open a session as another user for support work. Every use is audited. Off, support must work from what the person tells them.',
  TRUE, FALSE, NULL, NULL, 'Created with its default when the flag was introduced.', @NOW, @NOW);

-- ── job_runs ────────────────────────────────────────────────────────────────
-- One row per PASS of a background job, including the ones that found nothing.
--
-- That last part is the design. A drainer finding an empty queue is the normal
-- case and still writes a row — so a GAP in these rows means the scheduler
-- stopped, which is a different and worse problem than a job failing. A
-- "last status" column on the job itself could not express it: a job that failed
-- silently every night for a week and succeeded this morning looks healthy under
-- one, and looks like a problem under a list.
--
-- 2634 is ABANDONED rather than RUNNING. A process killed mid-pass gets no
-- chance to record its own death, and a row saying RUNNING for three weeks is a
-- job that looks busy; the sweeper marks them.
--
-- 2635 was run by a person, which is context worth keeping: a manual pass in the
-- middle of an afternoon reads differently from the scheduled two o'clock one.

INSERT INTO job_runs
 (id, job_name, status, started_at, finished_at, items_processed,
  failure_reason, triggered_by_user_id, created_at)
VALUES
 (2630, 'catalogue-jobs', 'SUCCEEDED', @NOW, @NOW, 2, NULL, NULL, @NOW),
 -- Nothing to do, which is healthy and still worth a row.
 (2631, 'catalogue-jobs', 'SUCCEEDED', @LAPSED, @LAPSED, 0, NULL, NULL, @LAPSED),
 (2632, 'finance-exports', 'SUCCEEDED', @NOW, @NOW, 1, NULL, NULL, @NOW),
 (2633, 'finance-exports', 'FAILED', @LAPSED, @LAPSED, 0,
  'That window holds 241,880 rows, above the 200,000 limit.', NULL, @LAPSED),
 (2634, 'account-data-requests', 'ABANDONED', @LAPSED, @NOW, 0,
  'Still marked running 168 hours after it started. The process almost certainly died mid-pass.',
  NULL, @LAPSED),
 (2635, 'platform-sweeper', 'SUCCEEDED', @NOW, @NOW, 1, NULL, 1001, @NOW);

-- ── webhook_events ──────────────────────────────────────────────────────────
-- Things providers told us, and what we did about them.
--
-- The row is written BEFORE the event is acted on, and that ordering is the
-- design. A payment provider saying "this succeeded" is the only record that it
-- did; acting first and storing afterwards means a crash between the two loses
-- the fact that a buyer paid.
--
-- Replay protection is the unique constraint on (provider, event_id), not a
-- check in code — two deliveries arriving at once would both pass a check.
-- 2700 and 2701 below are the same event from the same provider arriving twice,
-- which is providers working correctly: the second is DUPLICATE and was
-- answered 200 so they stop, because a 4xx would make them retry harder.
--
-- 2702 is REJECTED and is KEPT rather than discarded. One refusal is a clock
-- drifting; fifty in a minute is somebody trying signatures, and a platform
-- that threw them away would have nothing to count. Its event_id carries a
-- :rejected: suffix so a forged attempt cannot squat on the id the genuine
-- event will later need.
--
-- 2703 is the one worth reading twice. The provider said a 132.16 EUR payment
-- was settled for 1.00, and the platform refused to credit it. That is a bug or
-- an attack and crediting it is the same mistake either way — a provider does
-- not get to decide what an order cost.
--
-- 2705 is a bounce. The recipient's release code reaches the BUYER by email for
-- them to pass on, the way a remittance reference does, so an address that
-- bounced is somebody standing in front of a driver with nothing to read out.
--
-- payload is stored in full and verbatim. The signature was computed over those
-- bytes, and when a figure disagrees with a provider's dashboard in six months
-- the thing that settles it is what they actually sent.

INSERT INTO webhook_events
 (id, kind, provider, event_id, event_type, status, payload,
  signature_valid, rejection_reason, provider_timestamp, received_at, processed_at,
  attempts, failure_reason, subject_reference, outcome, created_at, updated_at)
VALUES
 (2700, 'PSP', 'wave', 'evt_7K3M9QXB2VDH', 'payment.succeeded', 'PROCESSED',
  '{"id":"evt_7K3M9QXB2VDH","type":"payment.succeeded","reference":"PAY-SEED0000003","amount":"132.16","currency":"EUR"}',
  TRUE, NULL, @NOW, @NOW, @NOW,
  1, NULL, 'PAY-SEED0000003',
  'Marked paid, at the amount and currency the payment was created for.', @NOW, @NOW),

 -- The same event again. Not an error: this is a provider retrying, and the
 -- answer that makes them stop is 200.
 (2701, 'PSP', 'wave', 'evt_7K3M9QXB2VDH:redelivery', 'payment.succeeded', 'DUPLICATE',
  '{"id":"evt_7K3M9QXB2VDH","type":"payment.succeeded","reference":"PAY-SEED0000003","amount":"132.16","currency":"EUR"}',
  TRUE, NULL, @NOW, @NOW, @NOW,
  0, NULL, 'PAY-SEED0000003',
  'Already received — answered 200 so the provider stops retrying.', @NOW, @NOW),

 -- Refused, and kept. signature_valid is FALSE and nothing was acted on.
 (2702, 'PSP', 'wave', 'evt_9MRT4XKQ2B:rejected:3f1c8a2e5b7d4906', 'payment.succeeded', 'REJECTED',
  '{"id":"evt_9MRT4XKQ2B","type":"payment.succeeded","reference":"PAY-SEED0000003","amount":"132.16"}',
  FALSE, 'The signature does not match the body.', @NOW, @NOW, NULL,
  0, NULL, NULL, NULL, @NOW, @NOW),

 -- The provider said the order cost something it did not.
 (2703, 'PSP', 'wave', 'evt_2MHB6VXQ4TWK', 'payment.succeeded', 'FAILED',
  '{"id":"evt_2MHB6VXQ4TWK","type":"payment.succeeded","reference":"PAY-SEED0000003","amount":"1.00","currency":"EUR"}',
  TRUE, NULL, @NOW, @NOW, @NOW,
  1,
  'The event says 1.00 and the payment is for 132.16. Not marking it paid — a provider cannot decide an order cost something else.',
  'PAY-SEED0000003',
  'The event says 1.00 and the payment is for 132.16. Not marking it paid — a provider cannot decide an order cost something else.',
  @NOW, @NOW),

 -- Understood and deliberately nothing to do. Recording that stops somebody
 -- later assuming a missing effect was a bug.
 (2704, 'PSP', 'wave', 'evt_4KQ7MXB2VN9P', 'payout.updated', 'IGNORED',
  '{"id":"evt_4KQ7MXB2VN9P","type":"payout.updated","reference":"PO-2026-08-KOMBO"}',
  TRUE, NULL, @NOW, @NOW, @NOW,
  1, NULL, 'PO-2026-08-KOMBO',
  'Nothing on this platform acts on "payout.updated".', @NOW, @NOW),

 (2705, 'MESSAGING', 'ses', 'evt_6VXQ4TWK2MHB', 'bounce', 'PROCESSED',
  '{"id":"evt_6VXQ4TWK2MHB","type":"bounce","recipient":"sulayman.blocked@example.gm","reason":"mailbox does not exist"}',
  TRUE, NULL, @NOW, @NOW, @NOW,
  1, NULL, 'sulayman.blocked@example.gm',
  'Recorded a bounce. Anything time-sensitive sent to that address did not arrive — a release code that bounced is somebody unable to collect.',
  @NOW, @NOW),

 -- Waiting for the worker. Stored and verified; nothing has acted on it yet,
 -- which is exactly the state the store-before-act ordering creates.
 (2706, 'KYC', 'smile', 'evt_QRT4XKM2BHV9', 'check.completed', 'RECEIVED',
  '{"id":"evt_QRT4XKM2BHV9","type":"check.completed","reference":"1600","result":"clear"}',
  TRUE, NULL, @NOW, @NOW, NULL,
  0, NULL, NULL, NULL, @NOW, @NOW);


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
--     GET /vendor/orders                as Lamin, then as Awa. Same order 1401,
--                                       two slices, each priced only in that
--                                       vendor's own currency. Neither response
--                                       contains GBP, London, or Oliver.
--     GET /api/products/search?q=wax&deliveryLat=13.4383&deliveryLng=-16.6781
--         &deliveryCountry=GM&currency=GBP
--                                       Oliver is in London and the wax print is
--                                       going to Serrekunda. The coordinates are
--                                       the RECIPIENT's — ranking is by what can
--                                       reach her — and the currency is the
--                                       PAYER's, because he holds a UK card.
--                                       Passing London's coordinates here would
--                                       rank the catalogue against a place the
--                                       parcel is never going.
--     GET /api/admin/orders/1401        the buyer's side of the same order.
--
--     GET /vendor/orders/1505           as Lamin. The fulfilment surface: a
--                                       packed parcel for Isatou in Serrekunda,
--                                       paid for by Fatou in Madrid. The
--                                       `shipping` block is the whole of what a
--                                       seller learns about either of them - a
--                                       name, a town, a country and three digits
--                                       of a phone. No street, because the
--                                       platform routes the parcel and the QR on
--                                       the label resolves the address for
--                                       whoever scans it; no payer at all.
--     GET /vendor/orders/1505/handoff-code
--                                       871460. Served no-store and never
--                                       logged. 304912 was the previous one and
--                                       is dead - try presenting it. Both are
--                                       seeded with a RELATIVE expiry, so they
--                                       are live whenever this file is run
--                                       rather than on the day it was written.
--     GET /vendor/orders/1505/label     an A6 PDF. Read what is NOT on it: no
--                                       street, no price, no contents, and not
--                                       the collection code. A code printed on
--                                       the box it protects protects nothing.
--     POST /vendor/orders/1505/ready    refused - it is already packed. Try it
--                                       on a slice whose phone is unscanned and
--                                       the refusal names the line and the count.
--     GET /vendor/orders/1506           as Awa. The slice she rejected, with her
--                                       reason on it. Then look at 1505 again:
--                                       untouched, still going. One payment, two
--                                       vendors, independent outcomes (C3).
--
--     GET /vendor/balance               as Lamin: 1000 dalasi available and
--                                       17,640 pending. The pending figure is
--                                       escrow — two parcels that have not been
--                                       confirmed delivered. Nothing stores
--                                       either number; both are sums of
--                                       vendor_ledger_entries.
--     GET /vendor/transactions          the rows behind it. Add the amount
--                                       column up and you get the balance,
--                                       which is the only thing that makes a
--                                       balance checkable.
--     GET /vendor/balance               as Awa: zero available and 11,419 XOF
--                                       pending. Her payout failed and came
--                                       back as a reversal rather than being
--                                       deleted, so the statement explains the
--                                       gap instead of hiding it.
--     GET /vendor/transactions?currency=XOF
--                                       look at slice 1506: four rows — sale,
--                                       commission, refund, commission returned
--                                       — that come to exactly nothing. Netting
--                                       them into one would hide the thing a
--                                       seller opens a refund to check.
--     POST /vendor/payouts/request      as Awa. Refused, and the message says
--                                       which kind of nothing it is: money
--                                       still held against parcels, rather than
--                                       no money at all.
--     GET /vendor/statements/2026-09?currency=GMD
--                                       as Lamin. A PDF whose brought-forward
--                                       plus movements equals its carried-
--                                       forward. Add &format=csv for the same
--                                       figures in a spreadsheet.
--     GET /vendor/analytics/overview    revenue per currency, never one total.
--                                       A shop trading in two currencies gets
--                                       two figures and a note saying why they
--                                       are not added.
--     GET /vendor/analytics/products    the phone was looked at 180 times and
--                                       bought once. The response says plainly
--                                       that views are page loads rather than
--                                       people.
--     GET /vendor/analytics/customers   counts and countries, and nothing else.
--                                       Destinations are where parcels went,
--                                       never where the payer was, and a
--                                       country with only a handful of orders
--                                       is left out entirely.
--
--   ── The custody chain ───────────────────────────────────────────────────
--
--     GET /driver/assignments           as Ebrima. Leg 1911 is his and in
--                                       progress; 1912 is an offer that lapsed
--                                       and is deliberately NOT in the list —
--                                       a driver shown a dead offer will tap it
--                                       and read the refusal as a broken app.
--     POST /driver/assignments/1912/accept
--                                       refused, and says the offer went back
--                                       to the pool rather than blaming him.
--     GET /driver/shipments/1901        he is carrying this one, so the
--                                       destination block is there: Isatou's
--                                       name, her street and her number.
--     GET /driver/shipments/1900        he handed this one over. The
--                                       destination block is ABSENT, not
--                                       blank — a driver who delivered a parcel
--                                       yesterday has no reason to still hold
--                                       somebody's front door.
--     POST /driver/shipments/1901/request-recipient-code
--                                       emails Fatou in Madrid, who passes the
--                                       six digits to her sister. Isatou needs
--                                       no account, no app and no email of her
--                                       own. The driver never sees the code:
--                                       one who could read it could mark a
--                                       parcel delivered without meeting
--                                       anybody.
--     POST /driver/shipments/1901/deliver
--                                       needs her code (540913), a position and
--                                       a photograph. This is the link somebody
--                                       would forge if any one of them were
--                                       enough alone.
--     POST /driver/shipments/1902/collect
--                                       refused: nobody has accepted that leg,
--                                       so the parcel is not his to move.
--
--   The three shipments are three points on the chain. 1900 is finished, 1901
--   is moving, and 1902 has no events at all — its DRIVER_OFFERED status comes
--   from the leg rather than from anything having happened, which is the state
--   most parcels are in at any moment.
--
--   Look at event 1923: collected 4.3km from the shop, with within_geofence
--   false. It was recorded and flagged rather than refused. The parcel may
--   genuinely have changed hands, and refusing would have stranded it — but the
--   chain says plainly that the position does not corroborate the handover.
--
--   ── The counter ─────────────────────────────────────────────────────────
--
--     GET /pickup-points?lat=13.4429&lng=-16.6776&radius=5
--                                       needs no account: a shopper picks where
--                                       to collect before signing in. Westfield
--                                       (1096) comes back; Latrikunda (1097)
--                                       does not, because it is closed for a
--                                       funeral. Sending somebody to a shuttered
--                                       counter is worse than showing nothing.
--     GET /pickup-points/1096           an address, hours, and how full it is as
--                                       a BAND. No operator name, no contact
--                                       email, no parcel count — that this shop
--                                       holds a hundred and ninety parcels is a
--                                       fact about somebody's business.
--     GET /pickup/points/1096/parcels   as Isatou. Three piles: what is coming,
--                                       what is on the shelf (1903 on A-118),
--                                       and what is overdue. Incoming parcels
--                                       carry no recipient name — they are not
--                                       here yet.
--     POST /pickup/points/1096/parcels/1903/return
--                                       refused: its deadline has not passed.
--                                       Somebody may be travelling to collect,
--                                       and sending it back early takes a
--                                       decision that is not the counter's.
--     POST /pickup/points/1096/parcels/1903/release
--                                       needs the recipient's code AND the name
--                                       of whoever is collecting. A code alone
--                                       would let anybody who overheard it take
--                                       the parcel; a name alone anybody who
--                                       read the label. A brother collecting for
--                                       his sister is allowed and written down.
--     POST /pickup/points/1097/parcels/1903/accept
--                                       refused, and says WHICH of the four
--                                       reasons it is — closed, suspended,
--                                       switched off, or full.
--
--   pickup_points.stored_parcels is 1 for Westfield, and that is not an
--   assertion: it is recounted from the shipments actually held there. A count
--   that could drift would drift into accepting parcels there is no room for.
--
--   ── Signing in without signing in ───────────────────────────────────────
--
--   The seeded sessions hold real refresh tokens. Each hash in user_sessions is
--   the SHA-256 of the value below, so these work against POST /auth/refresh
--   straight after seeding — no login round trip needed:
--
--     sujula-dev-refresh-aminata-phone    Aminata, Android          session 1801
--     sujula-dev-refresh-aminata-laptop   Aminata, Windows          session 1802
--     sujula-dev-refresh-lamin-vendor     Lamin the vendor, iOS     session 1803
--     sujula-dev-refresh-oliver-london    Oliver in London          session 1804
--
--   And two that must fail, which is the more interesting half:
--
--     sujula-dev-refresh-rotated-away     replayed. Session 1805 has already
--                                         rotated past it, so refreshing with it
--                                         is treated as theft: 401, and the
--                                         session is revoked rather than renewed.
--                                         Check GET /me/sessions afterwards —
--                                         1805 comes back revoked, TOKEN_REPLAY.
--     sujula-dev-refresh-modou-expired    past its refresh window — seeded a
--                                         week in the past, so it is expired
--                                         whenever you run this. 401, and
--                                         nothing is revoked: an expired token
--                                         is not evidence of anything.
--
--     curl -X POST localhost:8080/auth/refresh -H 'Content-Type: application/json' \
--          -d '{"refreshToken":"sujula-dev-refresh-aminata-phone"}'
--
--   ── The second factor ────────────────────────────────────────────────────
--
--   Aminata has an authenticator enrolled. Her recovery codes are seeded as
--   hashes, as the API stores them; the raw values are:
--
--     7K2M-9QX4    unused
--     B3TN-6RWZ    unused
--     H8PD-2LVC    already spent — using it is refused, which is the point of
--                  keeping a used code rather than deleting it
--
--   Sign in with {"email":..., "password":..., "recoveryCode":"7K2M-9QX4"}.
--   Each one works once.
--
--   ── An unfinished phone verification ─────────────────────────────────────
--
--   Modou has a live challenge on +2203100006, code 445120. It is good for
--   thirty minutes from the moment you run this file, not from a date written
--   into it — a challenge seeded to expire at a fixed timestamp is expired
--   before anyone can use it, which makes the row decorative. The same code is
--   seeded against Aminata's already-confirmed number. Sulayman's row has
--   burnt all five attempts and expired: that is what a refused verification
--   looks like in the data, and it is why a sixth attempt is not simply a
--   fresh start.
--
--   ── Data-protection requests in every state ──────────────────────────────
--
--     EXP-7F3A2B91   done, Oliver, with a download good for thirty days
--     EXP-C40D18E2   queued, Modou — the worker takes it within the minute
--     ERA-9B21EF07   an erasure in flight against the blocked account
--     EXP-1D5C77A4   failed, with the reason kept for the person who asked
--
--   Asking for an erasure again while ERA-9B21EF07 is open returns that same
--   request rather than starting a second one.
--
--   ── Addresses, and how much each pin is worth ────────────────────────────
--
--   GET /me/addresses as Aminata returns two. Each carries a confidence, and a
--   client is meant to behave differently for each:
--
--     1050  Home        USER_CONFIRMED  she moved the pin herself. dispatchable,
--                                       and a PATCH to the street will not move it
--     1051  Shop        CENTROID        right block, wrong unit —
--                                       needsPinConfirmation is true, show a map
--     1052  Oliver      EXACT           London is street-numbered and fully mapped
--     1053  Modou       NONE            off the highway, on no street map. Saved
--                                       anyway; delivery prices from a scope
--                                       fallback until he drops a pin
--
--   Fix 1053 with the endpoint that exists for exactly this:
--
--     POST /me/addresses/1053/confirm-pin   {"latitude":13.2714,"longitude":-16.6492}
--
--   It comes back USER_CONFIRMED and dispatchable, and stays that way through
--   later edits.
--
--   ── The address that would not go away ───────────────────────────────────
--
--   1054 is Aminata's old flat. She deleted it, and it is still in the table —
--   because order 1403 was placed against it, and an order that names a row
--   should still resolve to something. It is gone from her book: GET
--   /me/addresses does not list it, GET /me/addresses/1054 is a 404.
--
--   Delete 1051 to see the other branch. Nothing was ever ordered against it,
--   so it is removed outright rather than kept — accumulating names, phone
--   numbers and locations for their own sake, least of all for someone who
--   asked for them to be gone, is not a default worth having.
--
--     DELETE /me/addresses/1051   -> {"retained": false, ...}
--     DELETE /me/addresses/1050   -> {"retained": true,  ...}   (order 1403)
--
--   ── Delivery contexts ────────────────────────────────────────────────────
--
--   The destination the cart, the quote and checkout all price against, so the
--   three cannot drift apart and quote three different figures.
--
--     seed-ctx-aminata-home    hers, from her confirmed address
--     seed-ctx-guest-brikama   a guest's — no account, and the id is the whole
--                              of their claim to it
--     seed-ctx-guest-serrekunda a guest delivering to Serrekunda. What cart 1071
--                              prices against, and a guest row rather than
--                              Aminata's because a context belonging to an
--                              account is readable only by that account — so a
--                              guest basket pointed at hers cannot resolve its
--                              own destination
--     seed-ctx-guest-pickup    collection at hub 1096, with no destination pin
--                              at all, and still deliverable
--     seed-ctx-oliver-london   cross-border, priced in sterling
--     seed-ctx-expired         past its window
--
--   Two of these are worth trying for what they refuse:
--
--     GET /delivery-contexts/seed-ctx-aminata-home   signed in as anyone else,
--          or as a guest, is a 404 rather than a 403. Confirming it exists
--          would tell whoever guessed the id that they guessed right.
--     GET /delivery-contexts/seed-ctx-expired        also a 404, not a 410: an
--          expired bearer credential and one that never existed should be
--          indistinguishable.
--
--   Real ids are 256 bits of base64url from a secure random. The readable ones
--   here exist so the DELETE block at the top can find them, and would be a
--   vulnerability in production — for a guest the id is the only thing between
--   a stranger and their home address.
--
--   ── Idempotency ──────────────────────────────────────────────────────────
--
--   A request that times out on a slow connection is indistinguishable, from
--   the client's side, from one that never arrived — so clients retry, and
--   without a key the shopper ends up with the address twice.
--
--     POST /me/addresses  as Aminata
--       Idempotency-Key: seed-key-aminata-home
--
--   returns the recorded response instead of saving anything. Send the same key
--   with a different body and it is refused rather than served: a key reused
--   for different content is not a retry, and replaying the first answer would
--   silently discard the second request.
--
--   ── Serviceability and quotes ────────────────────────────────────────────
--
--   Both public — no account needed, because both are asked before there is a
--   basket.
--
--     POST /delivery/serviceability
--       {"origin":{"vendorId":1101},"destination":{"deliveryContextId":null,
--        "latitude":13.2714,"longitude":-16.6492,"countryCode":"GM"},
--        "nearestPickupPoints":3}
--
--     POST /delivery/quote
--       {"origin":{"vendorId":1101},"destination":{"latitude":13.2714,
--        "longitude":-16.6492,"countryCode":"GM"},"weightKg":2.5,
--        "value":3000,"currency":"GMD"}
--
--   Quoting to Oliver in GBP returns complete:false unless an exchange rate is
--   seeded for it — which is correct. Quoting the rate card's own currency
--   under someone else's symbol is how a buyer is charged fifty pounds for a
--   fifty-dalasi delivery.
--
--   ── Without a geocoder ───────────────────────────────────────────────────
--
--   sujula.google.geocoding.api-key is usually unset in development, and
--   everything above still works. POST /geo/validate-address answers
--   available:false — "nobody looked", which is a different thing from "we
--   looked and found nothing" and leads to different advice. Addresses save
--   without coordinates, and delivery prices from a scope fallback.
--
--   ── Currencies, and why CFA is the interesting one ───────────────────────
--
--     GET /currencies
--
--   Every entry carries minorUnits. XOF has none — there is no centime in
--   circulation — so a client that formats every amount to two places will show
--   CFA totals that cannot be tendered. GMD, GBP, EUR and USD have two.
--
--   The exchange_rates rows seeded above make these work:
--
--     GET /currencies/rates?base=GMD&quote=GBP    a published rate, direct
--     GET /currencies/rates?base=GBP&quote=XOF    no direct row, so it comes
--                                                 back inverted:true with the
--                                                 reciprocal — disclosed,
--                                                 because a reciprocal carries
--                                                 no spread in that direction
--     GET /currencies/rates?base=GMD&quote=SEK    no rate published at all; it
--                                                 says so rather than guessing
--
--   ── Held rates ───────────────────────────────────────────────────────────
--
--     POST /currencies/quote   {"base":"GMD","quote":"GBP","amount":4500}
--
--   holds the rate for fifteen minutes. The four seeded quotes cover the states
--   a client has to handle:
--
--     seed-fx-oliver-gbp   live, his, unspent
--     seed-fx-guest-xof    a guest's — the id is the whole of their claim to it,
--                          and the converted amount is a whole franc
--     seed-fx-consumed     already spent on an order, and still readable: a
--                          client reloading a confirmation page should see it
--                          reported as used rather than as missing
--     seed-fx-expired      past its window — a 404, not a 410
--
--   Worth trying for what it refuses: GET /currencies/quote/seed-fx-oliver-gbp
--   as anyone other than Oliver is a 404 rather than a 403.
--
--   And worth trying for what it holds: change rate 1220 in exchange_rates,
--   then re-read seed-fx-oliver-gbp. It still says 0.011. A quote that re-read
--   the table would not be a quote.
--
--   ── Reference data ───────────────────────────────────────────────────────
--
--     GET /countries      buy and ship are separate flags. GB buys and does not
--                         ship: a buyer in London orders for delivery to
--                         Serekunda, and one "supported" boolean could not say
--                         that.
--     GET /locales        each carries rtl, so a client knows to flip.
--     GET /config/public  feature flags, minimum app versions, support contacts.
--                         An allow-list assembled by hand — never a filtered
--                         view of configuration, which is one careless rename
--                         away from publishing a secret.
--
--   None of these read the database. They are configuration under
--   sujula.reference.*, so changing what this deployment supports is a property
--   change rather than a migration.
--
--   ── Browsing as the buyer this marketplace is for ────────────────────────
--
--   Oliver is in London. The phone is going to Aminata in Serrekunda. Those are
--   two different places and the catalogue has to treat them as two different
--   questions:
--
--     GET /products?deliverableTo=seed-ctx-aminata-home
--
--   ranks against Serrekunda — Aminata's confirmed pin — while the prices come
--   back in whatever Oliver's browser and IP say, because he is the one paying.
--   Change the delivery context and the ranking moves; the currency does not.
--   Change nothing but call from a different country and the currency moves;
--   the ranking does not. That is C1, and it is testable from the shell.
--
--   Without a context, give the destination directly:
--
--     GET /products?deliveryLat=13.4383&deliveryLng=-16.6781&deliveryCountry=GM&currency=GBP
--
--   The response echoes back which location it used, under `delivery` — so you
--   can see at a glance that the catalogue ranked against the recipient.
--
--   ── The filters ──────────────────────────────────────────────────────────
--
--     GET /products?condition=REFURBISHED     nothing, and that is right: the
--                                             only refurbished listing is 1307,
--                                             which is ARCHIVED because order
--                                             lines point at it. This note used
--                                             to claim it returned 1307 — a
--                                             public catalogue that showed a
--                                             withdrawn listing would be the
--                                             bug, so the note was what was
--                                             wrong
--     GET /products?condition=OPEN_BOX        1304 only. The condition facet
--                                             therefore comes back with two
--                                             values, NEW and OPEN_BOX, which
--                                             is what it is seeded to prove
--     GET /products?inStockOnly=true          drops 1304 and 1307, which have none
--     GET /products?minRating=4.5             1301 and 1305
--     GET /products?store=kombo-electronics   one seller's shelf
--     GET /categories/phones                  the attribute schema, derived from
--                                             what is actually in the category
--     GET /search?q=wax                       full text, with facets
--     GET /search/suggest?q=gal               typeahead; two characters minimum
--     GET /brands                             only brands with stock behind them
--
--   ── Questions ────────────────────────────────────────────────────────────
--
--     GET /products/1301/questions
--
--   returns two: the answered one first, then the unanswered but approved one.
--   It does NOT return 1902, which is waiting for a moderator, or 1903, which
--   was refused for posting a phone number on somebody else's shopfront. That
--   is the point of the design — a question is invisible until somebody
--   approved it, and the query is what enforces it rather than a service that
--   remembers to filter.
--
--     POST /products/1301/questions   {"question":"..."}   signed in
--
--   returns 202 and PENDING_REVIEW. It does not appear on the listing.
--
--   ── The basket, and the two things it holds separately ───────────────────
--
--     GET /carts/seed-cart-guest-gbp
--
--   comes back grouped by store, each group with its own shipping and each line
--   with its own delivery leg — because a multivendor basket has no single
--   origin, and two sellers in two towns ship two parcels.
--
--   That cart is priced in GBP and delivers to Serrekunda. Those are two fields
--   because they are two questions, and the endpoints that set them are two
--   endpoints for the same reason:
--
--     PUT /carts/{token}/delivery-context   moves the parcel; re-prices shipping
--     PUT /carts/{token}/currency           moves the prices; touches no delivery
--
--   Change one and watch the other stay put. That is C1 on the basket.
--
--   ── Quotes ───────────────────────────────────────────────────────────────
--
--     POST /carts/seed-cart-guest-gbp/quote
--
--   prices the cart and holds the figures for fifteen minutes, freezing the rate
--   each line was converted at. Checkout is given the quote's id and reconciles
--   against it: if the price moved, nothing is charged and the buyer is asked to
--   re-quote.
--
--   Four are seeded, one per state a client has to handle:
--
--     seed-quote-live         payable now
--     seed-quote-consumed     already spent on order 1403, and kept — months
--                             from now this is the evidence of what the buyer
--                             agreed to
--     seed-quote-expired      past its window; POST /checkout with it is a 404,
--                             not a 410
--     seed-quote-incomplete   one vendor's currency had no rate. It exists so a
--                             client can show what is wrong, and checkout
--                             refuses it: a total that silently dropped a
--                             vendor's goods would undercharge and the platform
--                             would owe the difference
--
--   Check the arithmetic yourself: 8500 GMD x 0.011 = 93.50 GBP, which is what
--   seed-quote-live's subtotal says. A stored rate that does not reproduce the
--   stored amount is decoration rather than evidence.
--
--   ── Checkout ─────────────────────────────────────────────────────────────
--
--     POST /checkout   {"quoteId":"...","addressId":1050,"paymentMethod":"CARD"}
--       Idempotency-Key: <a new value per attempt>
--
--   validates, reserves stock, creates the order and its per-vendor sub-orders,
--   and opens a payment intent — in that order. Send the same key twice and the
--   second call is answered from the first rather than placing a second order,
--   which on a mobile network is a certainty rather than a risk.
--
--   The response carries the sub-orders, not just the order. That is the shape
--   the thing actually has: one payment, several slices that ship, cancel,
--   refund and pay out independently. A client rendering one order with one
--   status will eventually be wrong about half of it.
--
--   ── Delivery price ───────────────────────────────────────────────────────
--
--   Distance and weight, independently. Cart 1071's line is 0.195 kg travelling
--   half a kilometre and costs 4.13; move the delivery context further away or
--   add a heavier product and it rises. Collecting from the vendor is zero,
--   because nothing is delivered.
--
--   ── The buyer's own orders ───────────────────────────────────────────────
--
--     GET  /orders                                       Oliver sees 1401
--     GET  /orders/1401                                  grouped by seller
--     GET  /orders/1401/tracking                          one timeline each
--     GET  /orders/1401/invoice                           a signed, expiring link
--
--   Sign in as Oliver and 1401 is his; ask for it as Aminata and it is a 404
--   rather than a 403, because "forbidden" confirms the order exists.
--
--   Order 1401 is the shape this marketplace has, in one row: paid in GBP from
--   London, two sellers settling in GMD and XOF, delivering to a UK address.
--   The detail response groups it by seller and each group totals on its own.
--
--   ── Cancelling ───────────────────────────────────────────────────────────
--
--     POST /orders/1401/cancel                            refused
--     POST /orders/1401/vendor-orders/1502/cancel         allowed
--
--   The first is refused and names Lamin's store: slice 1501 is SHIPPED, and
--   stopping an order whose goods are already with a courier is a return rather
--   than a cancellation. The second is allowed because 1502 is still CONFIRMED,
--   and it leaves 1501 exactly where it was — that is C3, and it is worth
--   checking rather than assuming.
--
--   What comes back is a refund REQUEST, not a refund: 16.70 GBP against slice
--   1502 alone, waiting on an administrator. Money leaving the platform is the
--   one action no later API call can undo, so nothing in the buyer surface
--   completes it.
--
--   Row 1450 is that request, seeded before you make the call — which makes the
--   cancel above a test of something else as well. Ask twice and there is still
--   one row: a buyer who taps cancel, sees nothing happen on a slow connection
--   and taps again must not end up with two refunds queued against the same
--   goods, one of which an administrator approves after the other has paid.
--
--   Note what 1450 carries: the slice's own rate, copied rather than looked up
--   again. 13050 XOF is 16.70 GBP at the rate this order was placed at and at
--   no other, and a refund priced at today's rate would hand the buyer a
--   different number from the one the vendor is not being paid.
--
--   ── Receipt and escrow ───────────────────────────────────────────────────
--
--     POST /orders/1403/vendor-orders/1504/confirm-receipt
--
--   Slice 1504 is already confirmed and released in this file, so the call
--   answers idempotently. What it writes when it does fire is row 1718 in
--   delivery_tracking: the buyer's own statement that the goods arrived,
--   recorded in their name, next to the driver's account of the same moment in
--   1715. The status is the consequence of that row rather than the input to
--   it — which is the whole difference between a custody chain and a status
--   field somebody can set.
--
--   ── The page for somebody with no account ────────────────────────────────
--
--     GET /track/K7MPQ4RTVX2ND9YH      order 1401
--     GET /track/B3WQHJ7FNXR5MTCD      order 1402, the guest order
--     GET /track/Z9DKP2VMHT6RXQFB      order 1403
--
--   No token, no account, no sign-in. This is the sister in Serrekunda with a
--   text message and nothing else, and the codes are sixteen characters from a
--   thirty-symbol alphabet because possession of one is the only credential.
--
--   Compare it against GET /orders/1401/tracking, which is the same parcels for
--   the person who paid. The public page has no name, no street, no phone
--   number, no price and no order number — and its event descriptions are fixed
--   phrases, not the driver's free text. Row 1712 says "Assigned to Ebrima
--   Bojang"; the public page says "A driver has been assigned." That
--   substitution is the only thing between a driver's notes and a stranger who
--   was forwarded the SMS.
--
--   ── Opening a shop ───────────────────────────────────────────────────────
--
--     POST /vendor/stores                       opens one in PENDING_KYC
--     GET  /vendor/stores/1101                  Lamin's, as its owner sees it
--     PATCH /vendor/stores/1101                 policies, hours, collection point
--
--   Three stores are seeded, and the interesting one is 1103. Mariama's stall
--   is in Dakar and she settles in GMD, because she banks in Banjul. Compare it
--   with 1102, which is also in Senegal and settles in XOF. Same country, two
--   currencies — which is only possible because the address and the settlement
--   currency are two answers to two questions, and nothing reads one off the
--   other. Send POST /vendor/stores with a Senegalese address and no currency
--   and you get GMD, the platform default, not XOF inferred from the country.
--
--   1103 also has no coordinates at all. A market stall with no street number
--   is not a failure case here, it is most of the region, so the store opens
--   with geocode_confidence NONE and collections from it price from a scope
--   fallback until she drops a pin. 1101 is EXACT and 1102 is a CENTROID with a
--   separate depot address, because cloth is cut at the shop and collected two
--   streets away — pricing that leg from the shop front would be wrong on every
--   order they take.
--
--   ── Verification ─────────────────────────────────────────────────────────
--
--     GET  /vendor/stores/1102/kyc              what came back, and why
--     POST /vendor/stores/1103/kyc              storage keys, never bytes
--
--   1102 is the row worth reading. Awa's first national ID (1364) was refused
--   as too dark to read; the second (1365) was accepted. Both survive, because
--   re-uploading supersedes rather than replaces and the sequence is the record
--   of why her onboarding took three weeks. Her proof of address (1366) is
--   still refused — she sent the depot lease instead of the shop's — so her KYC
--   reads ACTION_REQUIRED with that reason attached, not a bare "incomplete"
--   that would send her back to upload the same document again.
--
--   What is required depends on what the store claims to be. 1101 gave a
--   registration number and a tax number, so it is asked for both certificates.
--   1103 gave neither and is asked for an identity document and a proof of
--   address, which is all a market trader has. Demanding a company's paperwork
--   from her is how a marketplace turns away the sellers it exists for.
--
--   The bytes never pass through this API. Only the storage key is stored, and
--   GET /kyc does not hand it back: a URL to somebody's passport in a JSON
--   response is a URL in a browser cache.
--
--   ── Staff ────────────────────────────────────────────────────────────────
--
--     GET    /vendor/stores/1101/staff
--     POST   /vendor/stores/1101/staff          invite by email
--     DELETE /vendor/stores/1101/staff/1006
--
--   1371 is the shape that matters: Binta has no account here at all. The
--   invitation is keyed on her email and waits for her to register, because the
--   usual invitee is a relative or an assistant who has never used the
--   platform. Her token is stored as a SHA-256 digest like every other bearer
--   credential in this file.
--
--   1372 was removed, so its digest is null and its permissions are gone —
--   otherwise a link mailed last week still opens the shop. The row stays, which
--   is what makes "who could see this, and when" a question with an answer.
--
--   Permissions come from a store-only enum. There is no value a seller can
--   send that reaches another vendor's data or the platform's: the dangerous
--   grants are unrepresentable rather than rejected, which is the difference
--   between a check that can be forgotten and one that cannot. Note also who is
--   not in store_staff — Lamin and Awa. The owner is not a staff row, and the
--   endpoint puts them at the top of the list from the vendor record.
--
--   ── Where the money goes ─────────────────────────────────────────────────
--
--     PUT /vendor/stores/1101/bank-account
--       { ..., "password": "Password123!", "totpCode": "<if MFA is on>" }
--
--   The one endpoint here that asks who you are again. A bearer token says
--   somebody held a credential an hour ago, which is not enough to redirect
--   every future payout for a shop — so the password is re-entered, and the
--   authenticator code as well when the account has one. Send the password
--   alone on an MFA account and it is refused; that is a step-up that would
--   otherwise step down, being easier to pass than the sign-in that reached it.
--
--   Try it on a deployment with no sujula.security.field-encryption.key and it
--   refuses before it reads anything. That is deliberate: a system that looks
--   like it encrypts bank details and does not is worse than one that admits it
--   cannot. With a key set, the account number goes into the column as
--   AES-GCM ciphertext — read the raw column and you will not find the digits.
--
--   The three seeded destinations hold plaintext, which reads back fine because
--   the decrypt path passes through anything without the enc:v1: marker. That
--   asymmetry tolerates a hand-written seed and cannot create a plaintext row
--   through the application.
--
--   Nothing the API returns about a destination could be used to send money
--   anywhere: four digits, a holder name and a currency. And the currency is
--   the vendor's own — 1322 is XOF because Awa banks in Senegal, whatever an
--   order was charged in.
--
--   1103 has no destination at all, which is right for a store nobody has
--   verified. There is nothing to pay out to yet.
--
--   ── A seller's own catalogue ─────────────────────────────────────────────
--
--     GET   /vendor/products                    drafts and suspended included
--     POST  /vendor/products                    created as DRAFT, always
--     PATCH /vendor/products/1301               some edits go back for review
--     POST  /vendor/products/1301/publish       only if approved
--
--   The ladder is DRAFT to IN_REVIEW to APPROVED to PUBLISHED, and the last
--   step is the seller's. Being allowed to sell and choosing to are different
--   decisions: approval arriving overnight should not put a listing live before
--   the seller has set the stock.
--
--   Try POST /vendor/products then publish it straight away. Refused - a draft
--   cannot put itself on sale, and that refusal is the only thing making the
--   review queue real rather than advisory.
--
--   Then try PATCH /vendor/products/1301 with a new price, and watch it come
--   off sale. Every seeded listing carries an `approved_content_hash`, a digest
--   of what a moderator actually looked at: the name, the text, the price, the
--   category, the brand, the condition. Change any of those and it stops
--   matching, so the listing goes back to IN_REVIEW and `active` goes false.
--   Change the stock instead and nothing happens - a seller who had to re-enter
--   a queue to restock would stop using the queue.
--
--   Those hashes are real, not filler. SeededContentHashTest recomputes two of
--   them from the rows' own text; edit a seeded description without re-deriving
--   its hash and that test fails rather than the seed quietly becoming a lie.
--
--   1307 is the shape of DELETE. Order lines point at it, so archiving is the
--   only thing that can happen to it: a buyer's receipt, invoice and review all
--   have to keep resolving years from now. A listing nobody ever ordered is
--   genuinely deleted, and the order lines decide which - not `total_sold`,
--   which a cancelled order leaves at zero while a line still points here.
--
--   ── Translations ─────────────────────────────────────────────────────────
--
--     PUT /vendor/products/1305/translations/fr-SN
--
--   Awa writes French in Ziguinchor; the buyer paying is in London; the parcel
--   goes to Serrekunda. Nobody in that chain can pick the cloth up and look at
--   it, so the listing text is the whole of what they get. 1391 is marked
--   machine-translated and the buyer is told: a machine's version of "six yards
--   of wax print, cut to order" is usually fine and occasionally nonsense, and
--   somebody spending a month's remittance should know which they are reading.
--
--   1392 is deliberately half a translation - a name and nothing else - because
--   a translated name above the original description beats neither.
--
--   ── Bulk ─────────────────────────────────────────────────────────────────
--
--     GET /vendor/imports/IMP-7QK2M4XR9DTB5VNC     27 in, 3 rejected, with reasons
--     GET /vendor/imports/IMP-3HJ8P6WZ2FKD7RQY     the file itself was unreadable
--     GET /vendor/imports/EXP-5MNX9TQ2JVH4BKDW     a finished export
--
--   Those two import jobs are different kinds of failure and they need
--   different words. 1400 is rows: three of thirty did not parse and the other
--   twenty-seven are in the catalogue as drafts. 1401 is the file: somebody
--   uploaded a PDF, nothing was attempted, and saying so once beats four
--   hundred identical row errors.
--
--   Read 1400's errors. Each one names the row as the seller's own spreadsheet
--   numbers it - header is row 1 - and quotes their text back: "there is no
--   category called 'Phonez'" rather than "invalid category". That difference
--   is the whole reason to build this instead of telling them to use the form.
--
--   Every row an import creates is a DRAFT. An import that could publish would
--   be the way past moderation: upload four hundred rows, skip the queue.
--
--   The references are random rather than sequential, because a job id anybody
--   can count through hands out other sellers' import errors - which name their
--   products, their prices and their SKUs. Ownership is checked in the query as
--   well; the reference is the second lock, not the only one.
--
--   ── One invariant worth checking yourself ────────────────────────────────
--
--     SELECT id, status, active FROM products;
--
--   `active` is true if and only if `status` is PUBLISHED. Every public
--   catalogue query filters on active; the ladder lives in status; and
--   ProductLifecycle is the only thing allowed to write the first. Two fields
--   describing one fact drift the moment something sets one without the other,
--   and the way they drift here is a listing moderation pulled that carries on
--   selling. The verifier asserts the pair on every row, and so does a test
--   across every state in the enum.
--
--   ── Stock, and why it moved ──────────────────────────────────────────────
--
--     GET   /vendor/inventory?lowStock=true
--     GET   /vendor/inventory/1351/movements
--     PATCH /vendor/inventory/1351   {"delta":-2,"reason":"DAMAGE"}
--
--   Add up variant 1351's movements: 0 + 10 - 3 - 4 = 3, which is its stock.
--   That is the whole design. A count that can be assigned directly is a count
--   nobody can explain, so the movement is the record and the figure is what
--   the movements come to.
--
--   Look at what is in that trail beside the seller's own corrections: SALEs.
--   An audit showing the manual edits and quietly omitting the orders that took
--   the stock would be wrong in exactly the case somebody opens it for, which
--   is why OrderServiceImpl reserves and releases through the same ledger. A
--   SALE has no order number and cannot — stock is reserved before the order
--   exists, which is the right way round — and no person either, because an
--   order deducted it rather than a member of staff. A RETURN has both.
--
--   1452 is the row that earns the feature: minus four, CORRECTION, "counted
--   the shelf, four short". A month of those and no restocks is a shop with a
--   theft problem, and a ledger that called them all restocks would hide it.
--
--   ── The optimistic lock ──────────────────────────────────────────────────
--
--     PATCH /vendor/inventory/1351   {"setTo":12,"version":0,"reason":"CORRECTION"}
--
--   Send that twice. The second is refused, because the first moved the
--   version. Two people counting the same shelf and saving 10 and 12 must not
--   leave whichever committed last, with the other simply wrong and nothing
--   anywhere to say so — so an absolute figure carries the version it was read
--   at. A delta needs none of that: +5 is +5 whoever else is writing, which is
--   why the endpoint offers both and asks for the version on only one.
--
--   ── Handsets ─────────────────────────────────────────────────────────────
--
--     GET   /vendor/imei-units?variantId=1350
--     POST  /vendor/imei-units
--
--   Phones are the one product a count cannot describe: most sold here are
--   second-hand, and the buyer is often thousands of miles away choosing a gift
--   for somebody at home. Two units of the same model are not interchangeable
--   when one was opened once and the other has a scratched screen, and that gap
--   is most of the dispute surface on this marketplace.
--
--   Every IMEI here satisfies its own Luhn check digit, and the verifier checks
--   that rather than taking this comment's word for it — two of the four
--   originally written here did not, which the API would have refused on
--   re-entry. Try registering 490154203237519 (one digit off) and watch that
--   line alone be rejected while the others register.
--
--   Variant 1350's stock is 2, not 3, because 1462 is written off. The units
--   are the authority and the count follows them; a shop whose count did not
--   follow would go on selling a phone that is in a drawer with water damage.
--   For the same reason, PATCH /vendor/inventory/1350 with a typed figure is
--   refused outright.
--
--   Two things a seller cannot do to a handset: mark it SOLD (the order does
--   that, so the record and the sale cannot disagree) and touch BLOCKED in
--   either direction. Setting it would let them flag a rival's stock; clearing
--   it would let them launder a stolen handset, and recording an IMEI at all is
--   mostly about making that harder.
--
--   ── Discounts ────────────────────────────────────────────────────────────
--
--     POST /vendor/promotions/1472/activate     refused
--     POST /vendor/promotions/1471/activate     allowed
--
--   The first is refused and names 1470: both cover product 1301 and their
--   windows touch. Two discounts on one item do not compound into a price
--   anybody can predict — they compound into whichever the pricing code reaches
--   first, which is a different answer on different days and an argument with a
--   buyer either way. "Conflicts with an existing promotion" would leave a
--   seller hunting through their own list, so the refusal says which one.
--
--   1473 is FREE_SHIPPING and runs alongside 1470 happily: one discounts the
--   delivery leg and the other the goods, so they are not competing for the
--   same number.
--
--   Every amount is in the seller's own currency. 1471 is 500 GMD off because
--   Lamin banks in dalasi; a buyer paying in GBP sees that converted at the
--   rate their order was quoted at. Striking it in GBP instead would leave him
--   funding an amount that moves with the market between writing the promotion
--   and the order landing.
--
--   A promotion is a price the shop is charging; a coupon is a credential a
--   buyer presents. That difference is why a coupon can be capped per customer
--   — there is a customer to count — and a promotion cannot. 1081 is a vendor
--   coupon and 1080 a platform-funded one, and the scope is what decides whose
--   money it is when commission is worked out.
--
--   And one thing you cannot do: move a vendor order to DELIVERED through the
--   API. Order 1403 is delivered only because this file wrote it that way.
-- ============================================================================
