package com.sujula.service;


import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.product.ProductCardResponse;
import com.sujula.dto.response.product.ProductImageResponse;
import com.sujula.dto.response.product.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ProductService {

    Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findSimilarProducts(Long productId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    /**
     * Full detail for one product, whether or not it is published. For the
     * owning vendor and for admins.
     */
    ProductResponse findById(Long id);

    /**
     * The public product page. An unpublished product is reported as not found
     * rather than forbidden: a shopper has no business learning that a listing
     * exists but is hidden.
     */
    ProductResponse findPublishedById(Long id);

    /**
     * The vendor's own catalogue, unpublished listings included, priced in the
     * vendor's own currency — never converted into a buyer's.
     */
    Page<ProductResponse> findMyProducts(Long vendorUserId, Pageable pageable);

    /**
     * One of the vendor's own products, for the edit screen. Another vendor's
     * product is reported as not found: stock levels and margins are the
     * seller's business, and a probe should not tell them the id was real.
     */
    ProductResponse findMineById(Long vendorUserId, Long productId);

    // ── Vendor writes ────────────────────────────────────────────────────────
    // Every one of these takes the owning vendor's user id and refuses a product
    // belonging to anyone else. Slug, price currency and country are derived from
    // the vendor at save time rather than accepted from the request.

    ProductResponse create(Long vendorUserId, ProductRequest request);

    ProductResponse update(Long productId, Long vendorUserId, ProductRequest request);

    /** Unpublishes the product. Orders already placed keep their own snapshot of it. */
    void delete(Long productId, Long vendorUserId);

    /**
     * Attaches an already-uploaded image.
     *
     * <p>Takes a URL rather than the file itself: images go straight to object
     * storage through a presigned upload, so the bytes never pass through here.
     */
    ProductImageResponse addImage(Long productId, Long vendorUserId, String imageUrl,
                                  String altText, boolean makeDefault);

    /** Sets the display order from a list of image ids, first one shown first. */
    List<ProductImageResponse> reorderImages(Long productId, Long vendorUserId, List<Long> imageIdsInOrder);

    void deleteImage(Long imageId, Long vendorUserId);

    ProductImageResponse setDefaultImage(Long imageId, Long vendorUserId);
}
