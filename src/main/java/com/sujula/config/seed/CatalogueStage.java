package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sujula.model.Review;
import com.sujula.model.analytics.ProductViewStat;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.MediaStatus;
import com.sujula.model.constant.ProductCondition;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductAttribute;
import com.sujula.model.products.ProductImage;
import com.sujula.model.products.ProductOption;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductQuestion;
import com.sujula.model.products.ProductTranslation;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.products.ProductVariantValue;
import com.sujula.repository.product.Wishlist;
import com.sujula.repository.product.WishlistItem;

/**
 * What is for sale, and everything the listing carries.
 *
 * <p>Fourteen products across five stores, priced in two currencies, and
 * deliberately spread across every {@code ProductStatus} the moderation queue
 * can hold: a draft nobody has submitted, one waiting on a reviewer, one
 * approved but not yet published, one published, one the seller pulled, one
 * refused, one suspended mid-sale and one archived. Sample data with only
 * published products makes the moderation screens untestable, and those are the
 * screens where mistakes are expensive.
 *
 * <p>Prices are in the seller's own currency and nothing here converts them.
 * That is C2's first half: the listing currency is the payout currency, and the
 * buyer's currency does not exist until a quote is taken.
 */
@Component
class CatalogueStage implements SeedStage {

    @Override
    public String name() {
        return "Catalogue, variants, reviews and questions";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        brands(cat);
        categories(cat);
        cat.flush();
        products(cat);
        cat.flush();
        images(cat);
        attributes(cat);
        translations(cat);
        optionsAndVariants(cat);
        cat.flush();
        questions(cat);
        reviews(cat);
        cat.flush();
        viewStats(cat);
        wishlists(cat);
        categoryCommission(cat);
    }

    // ── Commission scoped to a category ──────────────────────────────────────

    /**
     * The one commission rate that names a category rather than a store.
     *
     * <p>It lives here rather than with the rest of the rates in
     * {@code StoreStage} for an unavoidable reason: categories do not exist
     * until this stage has run. Rates scoped to a category are how a marketplace
     * charges less on thin-margin goods than on phones, so the column needs a
     * row or nothing exercises the lookup that prefers it.
     */
    private void categoryCommission(SeedCatalogue cat) {
        cat.save(com.sujula.model.admin.CommissionRate.builder()
                .vendor(cat.vendor("banjul-phones"))
                .category(cat.category("parts"))
                .rate(new BigDecimal("5.00"))
                .effectiveFrom(cat.daysAgo(30))
                .setBy(cat.user("admin"))
                .note("Spares and repairs carry a thinner margin than handsets.")
                .build());
        cat.save(com.sujula.model.admin.CommissionRate.builder()
                .category(cat.category("food"))
                .rate(new BigDecimal("6.00"))
                .effectiveFrom(cat.daysAgo(30))
                .setBy(cat.user("admin"))
                .note("Platform-wide rate for food and provisions, whatever the store.")
                .build());
    }

    // ── Brands ───────────────────────────────────────────────────────────────

    private void brands(SeedCatalogue cat) {
        cat.brands.put("tecno", cat.save(Brand.builder()
                .name("Tecno").slug("tecno")
                .description("Entry and mid-range handsets, the volume seller across West Africa.")
                .logoUrl("https://cdn.sujula.gm/sample/brands/tecno.png")
                .website("https://www.tecno-mobile.com").active(true).sortOrder(1).build()));
        cat.brands.put("infinix", cat.save(Brand.builder()
                .name("Infinix").slug("infinix")
                .description("Large batteries, large screens, keen prices.")
                .logoUrl("https://cdn.sujula.gm/sample/brands/infinix.png")
                .website("https://www.infinixmobility.com").active(true).sortOrder(2).build()));
        cat.brands.put("samsung", cat.save(Brand.builder()
                .name("Samsung").slug("samsung")
                .description("Galaxy handsets and home appliances.")
                .logoUrl("https://cdn.sujula.gm/sample/brands/samsung.png")
                .website("https://www.samsung.com").active(true).sortOrder(3).build()));
        cat.brands.put("apple", cat.save(Brand.builder()
                .name("Apple").slug("apple")
                .description("Mostly refurbished on this marketplace.")
                .logoUrl("https://cdn.sujula.gm/sample/brands/apple.png")
                .website("https://www.apple.com").active(true).sortOrder(4).build()));
        cat.brands.put("xiaomi", cat.save(Brand.builder()
                .name("Xiaomi").slug("xiaomi")
                .description("Redmi and Poco handsets.")
                .active(true).sortOrder(5).build()));
        cat.brands.put("anker", cat.save(Brand.builder()
                .name("Anker").slug("anker")
                .description("Chargers, cables and power banks.")
                .active(true).sortOrder(6).build()));
        cat.brands.put("kirehun", cat.save(Brand.builder()
                .name("Kirehun").slug("kirehun")
                .description("Locally made cookware.")
                .active(true).sortOrder(7).build()));
        // Retired. Products already carrying it must still render, which is why
        // deactivating is not deleting.
        cat.brands.put("nokia-classic", cat.save(Brand.builder()
                .name("Nokia Classic").slug("nokia-classic")
                .description("Discontinued line. Kept for the listings that still reference it.")
                .active(false).sortOrder(99).build()));
    }

    // ── Categories ───────────────────────────────────────────────────────────

    private void categories(SeedCatalogue cat) {
        Category electronics = cat.save(Category.builder()
                .name("Electronics").slug("electronics")
                .description("Phones, computers and the things that charge them.")
                .imageUrl("https://cdn.sujula.gm/sample/categories/electronics.jpg")
                .active(true).sortOrder(1).build());
        cat.categories.put("electronics", electronics);

        cat.categories.put("phones", cat.save(Category.builder()
                .name("Mobile phones").slug("mobile-phones").parent(electronics)
                .description("Handsets, new and refurbished.")
                .active(true).sortOrder(1).build()));
        cat.categories.put("accessories", cat.save(Category.builder()
                .name("Phone accessories").slug("phone-accessories").parent(electronics)
                .description("Cases, cables, chargers and power banks.")
                .active(true).sortOrder(2).build()));
        cat.categories.put("parts", cat.save(Category.builder()
                .name("Spares and repairs").slug("spares-and-repairs").parent(electronics)
                .description("Screens, batteries and units sold for parts.")
                .active(true).sortOrder(3).build()));

        Category home = cat.save(Category.builder()
                .name("Home and kitchen").slug("home-and-kitchen")
                .description("Cookware, bedding and small appliances.")
                .active(true).sortOrder(2).build());
        cat.categories.put("home", home);
        cat.categories.put("cookware", cat.save(Category.builder()
                .name("Cookware").slug("cookware").parent(home)
                .active(true).sortOrder(1).build()));
        cat.categories.put("appliances", cat.save(Category.builder()
                .name("Small appliances").slug("small-appliances").parent(home)
                .active(true).sortOrder(2).build()));

        Category fashion = cat.save(Category.builder()
                .name("Fashion").slug("fashion")
                .description("Clothing, fabric and tailoring.")
                .active(true).sortOrder(3).build());
        cat.categories.put("fashion", fashion);
        cat.categories.put("fabric", cat.save(Category.builder()
                .name("Fabric").slug("fabric").parent(fashion)
                .active(true).sortOrder(1).build()));
        cat.categories.put("clothing", cat.save(Category.builder()
                .name("Clothing").slug("clothing").parent(fashion)
                .active(true).sortOrder(2).build()));

        cat.categories.put("crafts", cat.save(Category.builder()
                .name("Crafts").slug("crafts")
                .description("Baskets, carvings and dyed cloth.")
                .active(true).sortOrder(4).build()));
        cat.categories.put("food", cat.save(Category.builder()
                .name("Food and provisions").slug("food-and-provisions")
                .active(true).sortOrder(5).build()));
        // Switched off: nothing may be listed under it, but the products that
        // already are must not vanish from an order placed last month.
        cat.categories.put("retired", cat.save(Category.builder()
                .name("Seasonal (retired)").slug("seasonal-retired")
                .description("Closed to new listings.")
                .active(false).sortOrder(98).build()));
    }

    // ── Products ─────────────────────────────────────────────────────────────

    private void products(SeedCatalogue cat) {
        // Published and selling. The store's own currency, always.
        cat.products.put("spark10", cat.save(Product.builder()
                .name("Tecno Spark 10").slug("tecno-spark-10")
                .shortDescription("6.6-inch, 5000 mAh, dual SIM.")
                .description("The volume handset on this marketplace. Sealed, with a one-year "
                        + "Tecno warranty honoured at the Banjul service centre.")
                .price(SeedCatalogue.money("8500.00")).compareAtPrice(SeedCatalogue.money("9200.00"))
                .priceCurrency("GMD").sku("BP-TEC-SPK10")
                .stock(24).lowStockThreshold(4)
                .vendor(cat.vendor("banjul-phones")).category(cat.category("phones"))
                .brand(cat.brand("tecno"))
                .active(true).status(ProductStatus.PUBLISHED)
                .submittedForReviewAt(cat.daysAgo(170)).reviewedBy(cat.user("admin"))
                .reviewedAt(cat.daysAgo(169)).publishedAt(cat.daysAgo(169))
                .approvedContentHash("9f2c41b8e7a05d3c9f2c41b8e7a05d3c9f2c41b8e7a05d3c9f2c41b8e7a05d3c")
                .featured(true).weightKg(0.42).dimensions("165x76x8 mm")
                .country("GM").latitude(13.4530).longitude(-16.5775)
                .score(92).deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("4.50")).totalReviews(18).totalSold(96)
                .lastRestockedAt(cat.daysAgo(9))
                .build()));

        cat.products.put("hot30", cat.save(Product.builder()
                .name("Infinix Hot 30").slug("infinix-hot-30")
                .shortDescription("6.78-inch, 8 GB RAM, 5000 mAh.")
                .description("Sealed. Comes with the charger in the box.")
                .price(SeedCatalogue.money("9750.00"))
                .priceCurrency("GMD").sku("BP-INF-HOT30")
                .stock(11).lowStockThreshold(3)
                .vendor(cat.vendor("banjul-phones")).category(cat.category("phones"))
                .brand(cat.brand("infinix"))
                .active(true).status(ProductStatus.PUBLISHED)
                .submittedForReviewAt(cat.daysAgo(120)).reviewedBy(cat.user("admin"))
                .reviewedAt(cat.daysAgo(119)).publishedAt(cat.daysAgo(119))
                .featured(false).weightKg(0.46)
                .country("GM").latitude(13.4530).longitude(-16.5775)
                .score(84).deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("4.20")).totalReviews(7).totalSold(31)
                .lastRestockedAt(cat.daysAgo(20))
                .build()));

        cat.products.put("iphone11", cat.save(Product.builder()
                .name("iPhone 11 64GB (refurbished)").slug("iphone-11-64gb-refurbished")
                .shortDescription("Grade A refurbished, battery health 89% or better.")
                .description("Fully tested. Ninety-day shop warranty; Apple's own has long expired.")
                .price(SeedCatalogue.money("21500.00")).compareAtPrice(SeedCatalogue.money("24000.00"))
                .priceCurrency("GMD").sku("BP-APL-IP11-64")
                .stock(4).lowStockThreshold(2)
                .vendor(cat.vendor("banjul-phones")).category(cat.category("phones"))
                .brand(cat.brand("apple"))
                .active(true).status(ProductStatus.PUBLISHED)
                .submittedForReviewAt(cat.daysAgo(80)).reviewedBy(cat.user("support"))
                .reviewedAt(cat.daysAgo(79)).publishedAt(cat.daysAgo(79))
                .weightKg(0.20)
                .country("GM").latitude(13.4530).longitude(-16.5775)
                .score(77).deliveryScope(DeliveryScope.REGIIONAL)
                .condition(ProductCondition.REFURBISHED)
                .rating(new BigDecimal("4.00")).totalReviews(5).totalSold(12)
                .build()));

        cat.products.put("solarlamp", cat.save(Product.builder()
                .name("Solar lamp with phone charger").slug("solar-lamp-phone-charger")
                .shortDescription("Eight hours on a full charge; USB-A out.")
                .price(SeedCatalogue.money("1200.00"))
                .priceCurrency("GMD").sku("BP-SOL-LMP01")
                .stock(40)
                .vendor(cat.vendor("banjul-phones")).category(cat.category("accessories"))
                .active(true)
                // Submitted and waiting. Not visible to buyers yet.
                .status(ProductStatus.IN_REVIEW)
                .submittedForReviewAt(cat.daysAgo(2))
                .weightKg(0.55).country("GM")
                .deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .build()));

        cat.products.put("partslot", cat.save(Product.builder()
                .name("Assorted handset boards — sold for parts").slug("assorted-handset-boards-parts")
                .shortDescription("Untested. No returns.")
                .description("A box of logic boards pulled from water-damaged handsets. "
                        + "Sold as-is, for repairers only.")
                .price(SeedCatalogue.money("500.00"))
                .priceCurrency("GMD").sku("BP-PRT-LOT01")
                .stock(6)
                .vendor(cat.vendor("banjul-phones")).category(cat.category("parts"))
                .active(true).status(ProductStatus.PUBLISHED)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(30)).publishedAt(cat.daysAgo(30))
                .weightKg(1.20).country("GM")
                .deliveryScope(DeliveryScope.RECOGER).condition(ProductCondition.FOR_PARTS)
                .build()));

        // Dakar: priced in CFA, which has no minor unit.
        cat.products.put("galaxya15", cat.save(Product.builder()
                .name("Samsung Galaxy A15").slug("samsung-galaxy-a15")
                .shortDescription("6.5 pouces, 128 Go, double SIM.")
                .description("Neuf, scellé, garantie constructeur d'un an.")
                .price(SeedCatalogue.wholeUnits("95000"))
                .compareAtPrice(SeedCatalogue.wholeUnits("102000"))
                .priceCurrency("XOF").sku("DT-SAM-A15-128")
                .stock(15).lowStockThreshold(3)
                .vendor(cat.vendor("dakar-tech")).category(cat.category("phones"))
                .brand(cat.brand("samsung"))
                .active(true).status(ProductStatus.PUBLISHED)
                .submittedForReviewAt(cat.daysAgo(140)).reviewedBy(cat.user("admin"))
                .reviewedAt(cat.daysAgo(139)).publishedAt(cat.daysAgo(139))
                .featured(true).weightKg(0.20)
                .country("SN").latitude(14.6690).longitude(-17.4370)
                .score(88).deliveryScope(DeliveryScope.GLOBAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("4.40")).totalReviews(11).totalSold(44)
                .lastRestockedAt(cat.daysAgo(14))
                .build()));

        cat.products.put("powerbank", cat.save(Product.builder()
                .name("Anker PowerCore 20000").slug("anker-powercore-20000")
                .shortDescription("20 000 mAh, charge rapide 20 W.")
                .price(SeedCatalogue.wholeUnits("12500"))
                .priceCurrency("XOF").sku("DT-ANK-PC20K")
                .stock(32)
                .vendor(cat.vendor("dakar-tech")).category(cat.category("accessories"))
                .brand(cat.brand("anker"))
                .active(true).status(ProductStatus.PUBLISHED)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(100)).publishedAt(cat.daysAgo(100))
                .weightKg(0.35).country("SN")
                .deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("4.70")).totalReviews(6).totalSold(58)
                .build()));

        // Approved but the seller has not pressed publish. A real state, and the
        // one people most often assume cannot exist.
        cat.products.put("redminote12", cat.save(Product.builder()
                .name("Redmi Note 12 (boîte ouverte)").slug("redmi-note-12-boite-ouverte")
                .shortDescription("Ouvert pour démonstration, jamais utilisé.")
                .price(SeedCatalogue.wholeUnits("78000"))
                .priceCurrency("XOF").sku("DT-XIA-RN12-OB")
                .stock(2).lowStockThreshold(1)
                .vendor(cat.vendor("dakar-tech")).category(cat.category("phones"))
                .brand(cat.brand("xiaomi"))
                .active(true).status(ProductStatus.APPROVED)
                .submittedForReviewAt(cat.daysAgo(5)).reviewedBy(cat.user("admin"))
                .reviewedAt(cat.daysAgo(4))
                .weightKg(0.19).country("SN")
                .deliveryScope(DeliveryScope.REGIIONAL).condition(ProductCondition.OPEN_BOX)
                .build()));

        cat.products.put("castironpot", cat.save(Product.builder()
                .name("Cast iron cooking pot, 8 litre").slug("cast-iron-cooking-pot-8l")
                .shortDescription("Locally cast, seasoned, with a lid.")
                .price(SeedCatalogue.money("1450.00"))
                .priceCurrency("GMD").sku("SH-KIR-POT8L")
                .stock(18)
                .vendor(cat.vendor("serrekunda-home")).category(cat.category("cookware"))
                .brand(cat.brand("kirehun"))
                .active(true).status(ProductStatus.PUBLISHED)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(85)).publishedAt(cat.daysAgo(85))
                .weightKg(6.40).dimensions("340x340x260 mm")
                .country("GM").latitude(13.4390).longitude(-16.6790)
                .score(61).deliveryScope(DeliveryScope.REGIIONAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("4.80")).totalReviews(4).totalSold(22)
                .build()));

        // Pulled by the seller rather than by a moderator. Different field,
        // different meaning, and the difference shows in the seller's own list.
        cat.products.put("blender", cat.save(Product.builder()
                .name("Blender 1500 W with grinder").slug("blender-1500w-grinder")
                .shortDescription("Two jars and a dry grinder.")
                .price(SeedCatalogue.money("3200.00"))
                .priceCurrency("GMD").sku("SH-BLD-1500")
                .stock(0)
                .vendor(cat.vendor("serrekunda-home")).category(cat.category("appliances"))
                .active(false).status(ProductStatus.UNPUBLISHED)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(80))
                .publishedAt(cat.daysAgo(80)).unpublishedAt(cat.daysAgo(7))
                .allowBackorder(false)
                .weightKg(3.10).country("GM")
                .deliveryScope(DeliveryScope.REGIIONAL).condition(ProductCondition.NEW)
                .build()));

        // Suspended by a moderator while a complaint is looked at.
        cat.products.put("waxfabric", cat.save(Product.builder()
                .name("Wax print fabric, 6 yards").slug("wax-print-fabric-6-yards")
                .shortDescription("Full six-yard piece.")
                .price(SeedCatalogue.money("1850.00"))
                .priceCurrency("GMD").sku("KS-FAB-WAX6")
                .stock(9)
                .vendor(cat.vendor("kololi-style")).category(cat.category("fabric"))
                .active(false).status(ProductStatus.SUSPENDED)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(11))
                .publishedAt(cat.daysAgo(65))
                .rejectionReason("Listed as Dutch wax; the photographs show a local print.")
                .weightKg(1.10).country("GM")
                .deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .rating(new BigDecimal("3.00")).totalReviews(3).totalSold(14)
                .build()));

        cat.products.put("kaftan", cat.save(Product.builder()
                .name("Embroidered kaftan (second hand)").slug("embroidered-kaftan-second-hand")
                .shortDescription("Worn twice. Size L.")
                .price(SeedCatalogue.money("2400.00"))
                .priceCurrency("GMD").sku("KS-CLO-KAF-L")
                .stock(0)
                .vendor(cat.vendor("kololi-style")).category(cat.category("clothing"))
                .active(false).status(ProductStatus.ARCHIVED)
                .publishedAt(cat.daysAgo(200)).unpublishedAt(cat.daysAgo(40))
                .archivedAt(cat.daysAgo(35))
                .weightKg(0.80).country("GM")
                .deliveryScope(DeliveryScope.REGIIONAL).condition(ProductCondition.USED)
                .build()));

        // Never submitted. The seller is still writing it.
        cat.products.put("basket", cat.save(Product.builder()
                .name("Woven storage basket, large").slug("woven-storage-basket-large")
                .shortDescription("Hand woven in Kerewan.")
                .price(SeedCatalogue.money("650.00"))
                .priceCurrency("GMD").sku("KC-BSK-LG")
                .stock(12)
                .vendor(cat.vendor("kerewan-crafts")).category(cat.category("crafts"))
                .active(false).status(ProductStatus.DRAFT)
                .weightKg(0.90).country("GM")
                .deliveryScope(DeliveryScope.NATIONAL).condition(ProductCondition.NEW)
                .build()));

        // Refused, with the reason on the row. A rejection with no reason is a
        // support ticket waiting to be opened.
        cat.products.put("bonga", cat.save(Product.builder()
                .name("Dried bonga fish, 1 kg").slug("dried-bonga-fish-1kg")
                .shortDescription("Smoked and dried.")
                .price(SeedCatalogue.money("300.00"))
                .priceCurrency("GMD").sku("FF-FSH-BNG1")
                .stock(50)
                .vendor(cat.vendor("farafenni-foods")).category(cat.category("food"))
                .active(false).status(ProductStatus.REJECTED)
                .submittedForReviewAt(cat.daysAgo(22)).reviewedBy(cat.user("admin"))
                .reviewedAt(cat.daysAgo(21))
                .rejectionReason("Perishable food cannot be listed until the store holds a food "
                        + "handling certificate.")
                .weightKg(1.00).country("GM")
                .deliveryScope(DeliveryScope.RECOGER).condition(ProductCondition.NEW)
                .build()));
    }

    // ── Images ───────────────────────────────────────────────────────────────

    private void images(SeedCatalogue cat) {
        image(cat, "spark10", "spark10-front.jpg", "Tecno Spark 10, front", 0, true, MediaStatus.READY);
        image(cat, "spark10", "spark10-back.jpg", "Tecno Spark 10, back", 1, false, MediaStatus.READY);
        image(cat, "spark10", "spark10-box.jpg", "In the box", 2, false, MediaStatus.READY);
        image(cat, "hot30", "hot30-front.jpg", "Infinix Hot 30", 0, true, MediaStatus.READY);
        image(cat, "iphone11", "iphone11-front.jpg", "iPhone 11, refurbished", 0, true, MediaStatus.READY);
        image(cat, "galaxya15", "a15-front.jpg", "Galaxy A15", 0, true, MediaStatus.READY);
        image(cat, "galaxya15", "a15-angle.jpg", "Galaxy A15, angle", 1, false, MediaStatus.READY);
        image(cat, "powerbank", "powercore.jpg", "Anker PowerCore 20000", 0, true, MediaStatus.READY);
        image(cat, "castironpot", "pot-8l.jpg", "Cast iron pot", 0, true, MediaStatus.READY);
        image(cat, "waxfabric", "wax-6yd.jpg", "Wax print, six yards", 0, true, MediaStatus.READY);
        // Uploaded and not yet confirmed: the browser has a signed URL and the
        // confirmation callback has not arrived. Must not render anywhere.
        image(cat, "basket", "basket-large.jpg", "Woven basket", 0, true, MediaStatus.PENDING);
        // The upload failed. The row is kept so the seller is told, rather than
        // being left looking at a listing with a silently missing photograph.
        image(cat, "solarlamp", "solar-lamp.jpg", "Solar lamp", 0, true, MediaStatus.FAILED);
    }

    private void image(SeedCatalogue cat, String productKey, String file, String alt,
                       int sortOrder, boolean isDefault, MediaStatus status) {
        cat.save(ProductImage.builder()
                .product(cat.product(productKey))
                .imageUrl("https://cdn.sujula.gm/sample/products/" + file)
                .altText(alt).sortOrder(sortOrder).isDefault(isDefault).status(status)
                .originalFilename(file).contentType("image/jpeg")
                .sizeBytes(180_000L + sortOrder * 12_000L)
                .confirmedAt(status == MediaStatus.READY ? cat.daysAgo(30) : null)
                .build());
    }

    // ── Attributes ───────────────────────────────────────────────────────────

    private void attributes(SeedCatalogue cat) {
        attribute(cat, "spark10", "Screen", "6.6 inch, 90 Hz", 0);
        attribute(cat, "spark10", "Battery", "5000 mAh", 1);
        attribute(cat, "spark10", "SIM", "Dual nano-SIM", 2);
        attribute(cat, "spark10", "Warranty", "12 months, Banjul service centre", 3);
        attribute(cat, "iphone11", "Battery health", "89% or better", 0);
        attribute(cat, "iphone11", "Grade", "A — light marks only", 1);
        attribute(cat, "galaxya15", "Écran", "6.5 pouces Super AMOLED", 0);
        attribute(cat, "galaxya15", "Stockage", "128 Go", 1);
        attribute(cat, "castironpot", "Capacity", "8 litres", 0);
        attribute(cat, "castironpot", "Weight", "6.4 kg", 1);
        attribute(cat, "powerbank", "Capacité", "20 000 mAh", 0);
        attribute(cat, "waxfabric", "Length", "6 yards", 0);
    }

    private void attribute(SeedCatalogue cat, String productKey, String name, String value, int order) {
        cat.save(ProductAttribute.builder()
                .product(cat.product(productKey)).name(name).value(value).sortOrder(order)
                .build());
    }

    // ── Translations ─────────────────────────────────────────────────────────

    /**
     * The same listing in the languages the marketplace actually reads.
     *
     * <p>A buyer in Madrid reading a Banjul listing and a buyer in Banjul
     * reading a Dakar one are both normal here, so the pair matters in both
     * directions. Two of these are machine-translated and flagged as such,
     * because a machine translation presented as the seller's own words is a
     * description the seller never agreed to.
     */
    private void translations(SeedCatalogue cat) {
        cat.save(ProductTranslation.builder()
                .product(cat.product("spark10")).locale("fr")
                .name("Tecno Spark 10")
                .shortDescription("6,6 pouces, 5000 mAh, double SIM.")
                .description("Le téléphone le plus vendu sur cette place de marché. Scellé, "
                        + "garantie Tecno d'un an honorée au centre de Banjul.")
                .machineTranslated(true).build());
        cat.save(ProductTranslation.builder()
                .product(cat.product("spark10")).locale("es")
                .name("Tecno Spark 10")
                .shortDescription("6,6 pulgadas, 5000 mAh, doble SIM.")
                .machineTranslated(true).build());
        cat.save(ProductTranslation.builder()
                .product(cat.product("galaxya15")).locale("en")
                .name("Samsung Galaxy A15")
                .shortDescription("6.5-inch, 128 GB, dual SIM.")
                .description("New and sealed, with a one-year manufacturer's warranty.")
                .machineTranslated(false).build());
        cat.save(ProductTranslation.builder()
                .product(cat.product("powerbank")).locale("en")
                .name("Anker PowerCore 20000")
                .shortDescription("20,000 mAh, 20 W fast charge.")
                .machineTranslated(false).build());
        cat.save(ProductTranslation.builder()
                .product(cat.product("castironpot")).locale("fr")
                .name("Marmite en fonte, 8 litres")
                .shortDescription("Fonte locale, culottée, avec couvercle.")
                .machineTranslated(true).build());
        cat.save(ProductTranslation.builder()
                .product(cat.product("iphone11")).locale("es")
                .name("iPhone 11 64GB (reacondicionado)")
                .shortDescription("Grado A, batería al 89% o más.")
                .machineTranslated(true).build());
    }

    // ── Options, values and variants ─────────────────────────────────────────

    /**
     * Options and the variants they combine into.
     *
     * <p>{@code ProductOption.name} and {@code code} are both unique across the
     * whole table rather than per product, so every name here carries the SKU it
     * belongs to. That is a property of the mapping, not a stylistic choice —
     * two products both naming an option "Colour" is a constraint violation.
     */
    private void optionsAndVariants(SeedCatalogue cat) {
        // Tecno Spark 10 — storage × colour.
        ProductOption spark10Storage = cat.save(ProductOption.builder()
                .product(cat.product("spark10"))
                .name("Storage (BP-TEC-SPK10)").code("bp-tec-spk10-storage").sortOrder(0)
                .build());
        ProductOption spark10Colour = cat.save(ProductOption.builder()
                .product(cat.product("spark10"))
                .name("Colour (BP-TEC-SPK10)").code("bp-tec-spk10-colour").sortOrder(1)
                .build());

        ProductOptionValue s128 = value(cat, spark10Storage, "128GB", "128 GB", "0.00", null, 0);
        ProductOptionValue s256 = value(cat, spark10Storage, "256GB", "256 GB", "1100.00", null, 1);
        ProductOptionValue black = value(cat, spark10Colour, "BLACK", "Meta Black", "0.00", "#111111", 0);
        ProductOptionValue blue = value(cat, spark10Colour, "BLUE", "Meta Blue", "0.00", "#1B3A8C", 1);
        ProductOptionValue white = value(cat, spark10Colour, "WHITE", "Pearl White", "0.00", "#F2F2F2", 2);

        variant(cat, "spark10-128-black", "spark10", "BP-TEC-SPK10-128-BLK", 9, null, true, s128, black);
        variant(cat, "spark10-128-blue", "spark10", "BP-TEC-SPK10-128-BLU", 7, null, true, s128, blue);
        variant(cat, "spark10-256-black", "spark10", "BP-TEC-SPK10-256-BLK", 5, "9600.00", true, s256, black);
        // Out of stock but still listed: the buyer sees it greyed rather than
        // finding the whole product missing.
        variant(cat, "spark10-256-blue", "spark10", "BP-TEC-SPK10-256-BLU", 0, "9600.00", true, s256, blue);
        // Discontinued colour. Past orders still point at it, so it stays.
        variant(cat, "spark10-128-white", "spark10", "BP-TEC-SPK10-128-WHT", 3, null, false, s128, white);

        // Galaxy A15 — colour only, priced in CFA with no minor unit.
        ProductOption a15Colour = cat.save(ProductOption.builder()
                .product(cat.product("galaxya15"))
                .name("Couleur (DT-SAM-A15-128)").code("dt-sam-a15-128-colour").sortOrder(0)
                .build());
        ProductOptionValue a15Black = value(cat, a15Colour, "NOIR", "Noir", "0", "#000000", 0);
        ProductOptionValue a15Blue = value(cat, a15Colour, "BLEU", "Bleu", "0", "#2B4C9B", 1);
        variant(cat, "a15-noir", "galaxya15", "DT-SAM-A15-128-NOIR", 8, null, true, a15Black);
        variant(cat, "a15-bleu", "galaxya15", "DT-SAM-A15-128-BLEU", 7, null, true, a15Blue);

        // Wax fabric — length.
        ProductOption fabricLength = cat.save(ProductOption.builder()
                .product(cat.product("waxfabric"))
                .name("Length (KS-FAB-WAX6)").code("ks-fab-wax6-length").sortOrder(0)
                .build());
        ProductOptionValue sixYards = value(cat, fabricLength, "6YD", "6 yards", "0.00", null, 0);
        ProductOptionValue twelveYards = value(cat, fabricLength, "12YD", "12 yards", "1700.00", null, 1);
        variant(cat, "wax-6", "waxfabric", "KS-FAB-WAX-6", 6, null, true, sixYards);
        variant(cat, "wax-12", "waxfabric", "KS-FAB-WAX-12", 3, "3550.00", true, twelveYards);

        // Cast iron pot — a size option with no variants built off it yet. A
        // product can carry options a seller has not finished combining, and a
        // screen that assumes every option has variants breaks on this row.
        ProductOption potSize = cat.save(ProductOption.builder()
                .product(cat.product("castironpot"))
                .name("Capacity (SH-KIR-POT8L)").code("sh-kir-pot8l-capacity").sortOrder(0)
                .build());
        value(cat, potSize, "6L", "6 litres", "0.00", null, 0);
        value(cat, potSize, "8L", "8 litres", "0.00", null, 1);
        value(cat, potSize, "12L", "12 litres", "520.00", null, 2);

        // iPhone 11 — grade as an option, because a refurbished handset's
        // condition is what the buyer is actually choosing between.
        ProductOption iphoneGrade = cat.save(ProductOption.builder()
                .product(cat.product("iphone11"))
                .name("Grade (BP-APL-IP11-64)").code("bp-apl-ip11-64-grade").sortOrder(0)
                .build());
        ProductOptionValue gradeA = value(cat, iphoneGrade, "A", "Grade A", "0.00", null, 0);
        ProductOptionValue gradeB = value(cat, iphoneGrade, "B", "Grade B", "-1300.00", null, 1);
        variant(cat, "iphone11-a", "iphone11", "BP-APL-IP11-64-A", 2, null, true, gradeA);
        variant(cat, "iphone11-b", "iphone11", "BP-APL-IP11-64-B", 2, "20200.00", true, gradeB);
    }

    private ProductOptionValue value(SeedCatalogue cat, ProductOption option, String value,
                                     String display, String extra, String colourHex, int order) {
        return cat.save(ProductOptionValue.builder()
                .option(option).value(value).displayValue(display)
                .extraPrice(new BigDecimal(extra))
                .colorHex(colourHex).sortOrder(order)
                .build());
    }

    private void variant(SeedCatalogue cat, String key, String productKey, String sku,
                         int stock, String priceOverride, boolean active,
                         ProductOptionValue... values) {
        List<ProductOptionValue> selected = new ArrayList<>(List.of(values));
        ProductVariant variant = ProductVariant.builder()
                .product(cat.product(productKey))
                .sku(sku).stock(stock)
                .priceOverride(priceOverride == null ? null : new BigDecimal(priceOverride))
                .active(active)
                .selectedValues(selected)
                .build();
        cat.variants.put(key, cat.save(variant));

        // The same pairing again through the explicit join entity. Both mappings
        // exist in this schema and both are read, so sample data that populated
        // only one of them would leave half the queries returning nothing.
        for (ProductOptionValue optionValue : values) {
            cat.save(ProductVariantValue.builder()
                    .variant(variant).optionValue(optionValue).build());
        }
    }

    // ── Questions on listings ────────────────────────────────────────────────

    /**
     * Questions buyers asked, in every state moderation can leave one in.
     *
     * <p>Built out longhand rather than through a helper: a question carries
     * three independent timelines — asked, answered, moderated — and a method
     * with ten positional arguments hides which of them a given row actually
     * has.
     */
    private void questions(SeedCatalogue cat) {
        // Answered and approved. The only combination a buyer ever sees.
        cat.save(ProductQuestion.builder()
                .product(cat.product("spark10")).askedBy(cat.user("isatou"))
                .question("Does this take two SIM cards at the same time as a memory card?")
                .answer("Yes — two nano-SIMs and a microSD, three separate slots.")
                .answeredBy(cat.user("fatou")).answeredAt(cat.daysAgo(20))
                .approvedBy(cat.user("admin")).approvedAt(cat.daysAgo(20))
                .createdAt(cat.daysAgo(21))
                .build());
        cat.save(ProductQuestion.builder()
                .product(cat.product("spark10")).askedBy(cat.user("modou"))
                .question("Is the charger included in the box?")
                .answer("Yes, an 18 W charger and a USB-C cable.")
                .answeredBy(cat.user("fatou")).answeredAt(cat.daysAgo(15))
                .approvedBy(cat.user("support")).approvedAt(cat.daysAgo(15))
                .createdAt(cat.daysAgo(16))
                .build());
        cat.save(ProductQuestion.builder()
                .product(cat.product("powerbank")).askedBy(cat.user("modou"))
                .question("Combien de temps pour recharger le power bank lui-même ?")
                .answer("Environ six heures avec un chargeur 20 W.")
                .answeredBy(cat.user("omar")).answeredAt(cat.daysAgo(9))
                .approvedBy(cat.user("admin")).approvedAt(cat.daysAgo(9))
                .createdAt(cat.daysAgo(10))
                .build());

        // Answered by the seller, not yet moderated. Still invisible to buyers —
        // which is the row that proves publication is a separate decision.
        cat.save(ProductQuestion.builder()
                .product(cat.product("iphone11")).askedBy(cat.user("binta"))
                .question("What exactly does grade A mean here?")
                .answer("Light marks on the frame only; the screen has no scratches.")
                .answeredBy(cat.user("fatou")).answeredAt(cat.daysAgo(3))
                .createdAt(cat.daysAgo(4))
                .build());

        // Asked and unanswered. This is the seller's queue.
        cat.save(ProductQuestion.builder()
                .product(cat.product("galaxya15")).askedBy(cat.user("cheikh"))
                .question("Est-ce que la garantie est valable en Gambie ?")
                .createdAt(cat.daysAgo(2))
                .build());
        cat.save(ProductQuestion.builder()
                .product(cat.product("castironpot")).askedBy(cat.user("sally"))
                .question("Is this safe on a gas ring, or only on charcoal?")
                .createdAt(cat.daysAgo(1))
                .build());

        // Refused: an attempt to take the sale off the platform.
        cat.save(ProductQuestion.builder()
                .product(cat.product("hot30")).askedBy(cat.user("yankuba"))
                .question("Call me on 220 720 0222 and we can agree a cash price.")
                .rejectedReason("Asks to take the transaction off the platform.")
                .rejectedAt(cat.daysAgo(6))
                .createdAt(cat.daysAgo(7))
                .build());
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    private void reviews(SeedCatalogue cat) {
        cat.reviews.put("spark10-isatou", cat.save(Review.builder()
                .user(cat.user("isatou")).product(cat.product("spark10"))
                .rating(5).title("Arrived in Serrekunda in four days")
                .comment("Bought it from Madrid for my sister. The driver rang her, she read the "
                        + "code back, and that was that.")
                .verified(true)
                .vendorReply("Thank you Isatou — glad it reached her quickly.")
                .vendorRepliedAt(cat.daysAgo(8))
                .build()));
        cat.reviews.put("spark10-modou", cat.save(Review.builder()
                .user(cat.user("modou")).product(cat.product("spark10"))
                .rating(4).title("Good phone, slow to dispatch")
                .comment("No complaints about the handset. It sat two days before collection.")
                .verified(true)
                .build()));
        // Edited after the fact. The counter exists so an endless rewrite is
        // visible rather than silent.
        cat.reviews.put("spark10-binta", cat.save(Review.builder()
                .user(cat.user("binta")).product(cat.product("spark10"))
                .rating(3).title("Battery is not what I hoped")
                .comment("Updated after a month: it holds a day, not two. Still fine for the price.")
                .verified(true).editedAt(cat.daysAgo(4)).editCount(2)
                .build()));
        cat.reviews.put("a15-cheikh", cat.save(Review.builder()
                .user(cat.user("cheikh")).product(cat.product("galaxya15"))
                .rating(5).title("Parfait")
                .comment("Livré à Dakar le lendemain. Rien à redire.")
                .verified(true)
                .build()));
        cat.reviews.put("a15-sally", cat.save(Review.builder()
                .user(cat.user("sally")).product(cat.product("galaxya15"))
                .rating(4).title("Bien, mais la housse manquait")
                .comment("La housse annoncée n'était pas dans la boîte. Le vendeur l'a envoyée après.")
                .verified(true)
                .vendorReply("Désolé — envoyée le jour même de votre message.")
                .vendorRepliedAt(cat.daysAgo(12))
                .build()));
        cat.reviews.put("pot-binta", cat.save(Review.builder()
                .user(cat.user("binta")).product(cat.product("castironpot"))
                .rating(5).title("Heavy, and that is the point")
                .comment("Seasoned properly. Cooks benachin better than anything I have had.")
                .verified(true)
                .build()));
        // Not a verified purchase. Worth having: the badge means nothing unless
        // some rows lack it.
        cat.reviews.put("pot-yankuba", cat.save(Review.builder()
                .user(cat.user("yankuba")).product(cat.product("castironpot"))
                .rating(2).title("Too heavy")
                .comment("Could not lift it when full.")
                .verified(false)
                .build()));
        // Reported repeatedly and hidden by a moderator pending a decision.
        cat.reviews.put("wax-fanta", cat.save(Review.builder()
                .user(cat.user("fanta")).product(cat.product("waxfabric"))
                .rating(1).title("Fake — do not buy from this seller")
                .comment("Contact me on WhatsApp and I will tell you where to get the real thing.")
                .verified(false).reportCount(3).hiddenAt(cat.daysAgo(10))
                .build()));
        // Withdrawn by its author. Kept, so the product's rating history still
        // reconciles.
        cat.reviews.put("hot30-mariama", cat.save(Review.builder()
                .user(cat.user("mariama")).product(cat.product("hot30"))
                .rating(3).title("Changed my mind")
                .comment("Withdrawn.")
                .verified(true).deletedAt(cat.daysAgo(6))
                .build()));
        cat.reviews.put("iphone11-modou", cat.save(Review.builder()
                .user(cat.user("modou")).product(cat.product("iphone11"))
                .rating(4).title("Honest description")
                .comment("Marks were where they said they would be. Battery at 91%.")
                .verified(true)
                .build()));
    }

    // ── How often listings are looked at ─────────────────────────────────────

    private void viewStats(SeedCatalogue cat) {
        LocalDate today = LocalDate.now();
        long[] spark = {412, 388, 455, 501, 390, 366, 478};
        long[] a15 = {221, 205, 240, 262, 198, 187, 233};
        long[] pot = {44, 51, 38, 62, 40, 35, 48};
        for (int day = 0; day < 7; day++) {
            viewStat(cat, "spark10", "banjul-phones", today.minusDays(day), spark[day]);
            viewStat(cat, "galaxya15", "dakar-tech", today.minusDays(day), a15[day]);
            viewStat(cat, "castironpot", "serrekunda-home", today.minusDays(day), pot[day]);
        }
        // A listing that has never been looked at. Reports that divide by views
        // meet this row first.
        viewStat(cat, "partslot", "banjul-phones", today, 0L);
        viewStat(cat, "iphone11", "banjul-phones", today, 77L);
        viewStat(cat, "powerbank", "dakar-tech", today, 130L);
    }

    private void viewStat(SeedCatalogue cat, String productKey, String vendorKey,
                          LocalDate day, long views) {
        cat.save(ProductViewStat.builder()
                .product(cat.product(productKey)).vendor(cat.vendor(vendorKey))
                .viewedOn(day).views(views)
                .build());
    }

    // ── Wishlists ────────────────────────────────────────────────────────────

    private void wishlists(SeedCatalogue cat) {
        Wishlist isatou = cat.save(Wishlist.builder().user(cat.user("isatou")).build());
        Wishlist modou = cat.save(Wishlist.builder().user(cat.user("modou")).build());
        Wishlist binta = cat.save(Wishlist.builder().user(cat.user("binta")).build());
        Wishlist sally = cat.save(Wishlist.builder().user(cat.user("sally")).build());
        // Empty on purpose: a wishlist with no items is the first thing the page
        // has to render correctly.
        cat.save(Wishlist.builder().user(cat.user("cheikh")).build());

        cat.save(WishlistItem.builder().wishlist(isatou).product(cat.product("iphone11")).build());
        cat.save(WishlistItem.builder().wishlist(isatou).product(cat.product("castironpot")).build());
        cat.save(WishlistItem.builder().wishlist(isatou).product(cat.product("galaxya15")).build());
        cat.save(WishlistItem.builder().wishlist(modou).product(cat.product("spark10")).build());
        cat.save(WishlistItem.builder().wishlist(modou).product(cat.product("powerbank")).build());
        cat.save(WishlistItem.builder().wishlist(binta).product(cat.product("hot30")).build());
        // Saved before the seller pulled it. The page has to survive that.
        cat.save(WishlistItem.builder().wishlist(binta).product(cat.product("blender")).build());
        cat.save(WishlistItem.builder().wishlist(sally).product(cat.product("waxfabric")).build());
    }
}
