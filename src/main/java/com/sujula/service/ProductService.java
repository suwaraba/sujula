package com.sujula.service;

import com.sujula.dto.request.CreateProductRequest;
import com.sujula.model.Product;
import com.sujula.model.ProductImage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {

    // ── Read ──────────────────────────────────────────────────────────────────
    //Page<Product> findByVendor(Long vendorId, Pageable pageable);
    //Page<Product> findByCategory(Long categoryId, Pageable pageable);

    //Page<Product> search(String query, Pageable pageable);

    Page<Product> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<Product> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<Product> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<Product> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<Product> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Product findById(Long id);

   
   
    Product create(Long vendorUserId, CreateProductRequest request);

    /**
     * Update a product. Verifies ownership and APPROVED status.
     */
    Product update(Long productId, Long vendorUserId, CreateProductRequest request);

    /**
     * Soft-delete a product (sets active=false) and removes all R2 images.
     * Verifies ownership and APPROVED status.
     */
    void delete(Long productId, Long vendorUserId);

    ProductImage addImage(Long productId, Long vendorUserId, MultipartFile file,
                          String altText, boolean makeDefault);

    /**
     * Delete a product image by its ID, removing it from R2 as well.
     * If the deleted image was the default, promotes the next image.
     */
    void deleteImage(Long imageId, Long vendorUserId);

    /**
     * Set a specific image as the default for a product.
     */
    ProductImage setDefaultImage(Long imageId, Long vendorUserId);
}
