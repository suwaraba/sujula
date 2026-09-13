package com.sujula.service.fulfilment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.delivery.HandoverCode;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.delivery.HandoverCodeRepository;
import com.sujula.repository.inventory.ImeiUnitRepository;
import com.sujula.repository.order.OrderItemRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of this surface only a real database can answer.
 *
 * <p>Three of them. Whether a handover code may hang off a vendor order with no
 * delivery — a nullable foreign key is a schema fact, not a Java one. Whether
 * the release-code queries really filter used, invalidated and expired rows, as
 * opposed to parsing without error and returning everything. And whether a line
 * of two handsets survives a round trip, which is a question about a column
 * width that a mock cannot have an opinion about.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReleaseCodePersistenceTest {

    @Autowired private HandoverCodeRepository handoverCodes;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private ImeiUnitRepository imeiUnits;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private VendorOrder slice;
    private OrderItem line;
    private Vendor vendor;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        User seller = new User();
        seller.setEmail("lamin@sujula.gm");
        seller.setPassword("x");
        seller.setFirstName("Lamin");
        seller.setLastName("Jallow");
        seller.setRole(UserRole.VENDOR);
        seller = users.save(seller);

        vendor = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").pickupCountryCode("GM")
                .build());

        Product product = new Product();
        product.setName("Galaxy A16");
        product.setSku("GA16");
        product.setSlug("galaxy-a16");
        product.setPrice(new BigDecimal("8500.00"));
        product.setPriceCurrency("GMD");
        product.setVendor(vendor);
        product.setStock(5);
        product = products.save(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("GA16-128-BLK");
        variant.setStock(5);
        variant.setPriceOverride(new BigDecimal("8500.00"));
        variant = variants.save(variant);

        Order order = new Order();
        order.setOrderNumber("SJL-JPA0001");
        order.setSubtotal(new BigDecimal("8500.00"));
        order.setTotal(new BigDecimal("8500.00"));
        order.setCurrency("GMD");
        order = orders.save(order);

        slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotal(new BigDecimal("8500.00")).total(new BigDecimal("8500.00"))
                .build());

        line = orderItems.save(OrderItem.builder()
                .order(order).vendorOrder(slice).product(product).variant(variant).vendor(vendor)
                .quantity(2)
                .unitPrice(new BigDecimal("8500.00")).totalPrice(new BigDecimal("17000.00"))
                .currency("GMD").productName("Galaxy A16").productSku("GA16")
                .build());

        entityManager.flush();
    }

    private HandoverCode code(String value) {
        return handoverCodes.save(HandoverCode.builder()
                .vendorOrder(slice)
                .codeType(HandoverCodeType.VENDOR_RELEASE)
                .code(value)
                .expiresAt(LocalDateTime.now().plusDays(3))
                .build());
    }

    @Test
    void aReleaseCodeHangsOffAVendorOrderWithNoDelivery() {
        HandoverCode saved = code("123456");
        entityManager.flush();
        entityManager.clear();

        HandoverCode read = handoverCodes.findById(saved.getId()).orElseThrow();

        // The whole reason delivery_id had to become nullable: a seller packs
        // one parcel for the slice, and there is no delivery yet to hang it on.
        assertNull(read.getDelivery());
        assertEquals(slice.getId(), read.getVendorOrder().getId());
        assertEquals(HandoverCodeType.VENDOR_RELEASE, read.getCodeType());
    }

    @Test
    void theLiveCodeQuerySkipsUsedInvalidatedAndExpiredRows() {
        HandoverCode used = code("111111");
        used.setUsed(true);

        HandoverCode invalidated = code("222222");
        invalidated.setInvalidatedAt(LocalDateTime.now());

        HandoverCode live = code("333333");
        entityManager.flush();
        entityManager.clear();

        // Filtered in the query, not after it. A check after the fact is the one
        // that ships missing.
        Optional<HandoverCode> found = handoverCodes.findLiveReleaseCode(slice.getId());
        assertTrue(found.isPresent());
        assertEquals("333333", found.get().getCode());
        assertEquals(live.getId(), found.get().getId());
    }

    @Test
    void aPresentedCodeMustBeTheLiveOne() {
        HandoverCode invalidated = code("222222");
        invalidated.setInvalidatedAt(LocalDateTime.now());
        code("333333");

        HandoverCode expired = code("444444");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        LocalDateTime now = LocalDateTime.now();
        assertTrue(handoverCodes.findPresentedReleaseCode(slice.getId(), "333333", now).isPresent());

        // A reissued code is dead, not one of several that all work.
        assertTrue(handoverCodes.findPresentedReleaseCode(slice.getId(), "222222", now).isEmpty());
        assertTrue(handoverCodes.findPresentedReleaseCode(slice.getId(), "444444", now).isEmpty());
        assertTrue(handoverCodes.findPresentedReleaseCode(slice.getId(), "999999", now).isEmpty());
    }

    @Test
    void theRateLimitCountsEveryCodeIssuedIncludingTheDeadOnes() {
        code("111111").setInvalidatedAt(LocalDateTime.now());
        code("222222").setInvalidatedAt(LocalDateTime.now());
        code("333333");
        entityManager.flush();

        // Counting only the live one would let a seller reissue without limit:
        // each reissue kills the last, so the live count never grows.
        assertEquals(3, handoverCodes.countReleaseCodesSince(
                slice.getId(), LocalDateTime.now().minusHours(1)));
        assertEquals(0, handoverCodes.countReleaseCodesSince(
                slice.getId(), LocalDateTime.now().plusMinutes(1)));
    }

    @Test
    void aCodeOnOneSliceIsNotFoundOnAnother() {
        code("333333");
        VendorOrder other = vendorOrders.save(VendorOrder.builder()
                .order(slice.getOrder()).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .nativeCurrency("GMD")
                .subtotal(BigDecimal.ONE).total(BigDecimal.ONE)
                .build());
        entityManager.flush();

        assertTrue(handoverCodes.findLiveReleaseCode(other.getId()).isEmpty());
    }

    @Test
    void aLineOfTwoHandsetsSurvivesARoundTrip() {
        line.bindImei("356938035643809");
        line.bindImei("356938035643791");
        orderItems.save(line);
        entityManager.flush();
        entityManager.clear();

        OrderItem read = orderItems.findById(line.getId()).orElseThrow();

        // One column would have held the first and silently lost the second.
        assertEquals(List.of("356938035643809", "356938035643791"), read.assignedImeiList());
        assertTrue(read.getImeiAssignedAt() != null);
    }

    @Test
    void aHandsetBoundToALineIsFoundFromTheUnitSide() {
        ImeiUnit unit = imeiUnits.save(ImeiUnit.builder()
                .imei("356938035643809").vendor(vendor).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.RESERVED)
                .orderItem(line).assignedAt(LocalDateTime.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        // The authoritative direction, and the one that is indexed.
        List<ImeiUnit> bound = imeiUnits.findByOrderItemId(line.getId());
        assertEquals(1, bound.size());
        assertEquals(unit.getId(), bound.get(0).getId());
        assertEquals(line.getId(), bound.get(0).getOrderItem().getId());
    }

    @Test
    void aHandsetOnTheShelfIsBoundToNoLine() {
        imeiUnits.save(ImeiUnit.builder()
                .imei("356938035643791").vendor(vendor).product(variant.getProduct())
                .variant(variant).status(ImeiStatus.IN_STOCK)
                .build());
        entityManager.flush();
        entityManager.clear();

        assertTrue(imeiUnits.findByOrderItemId(line.getId()).isEmpty());
    }
}
