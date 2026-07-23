package com.sujula.service;


import com.sujula.dto.request.product.ProductRequest;
import com.sujula.dto.response.product.ProductCardResponse;
import com.sujula.dto.response.product.ProductResponse;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {

    // ── Read ──────────────────────────────────────────────────────────────────
    //Page<Product> findByVendor(Long vendorId, Pageable pageable);
    //Page<Product> findByCategory(Long categoryId, Pageable pageable);

    //Page<Product> search(String query, Pageable pageable);

    Page<ProductCardResponse> findFeaturedProducts(Boolean featured, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findNewArrivalsProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findBestSellersProducts(String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> findByCategoryProducts(Long categoryId, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    Page<ProductCardResponse> searchNearUser(String query, String deliveryCountry, String currency, Double userLat, Double userLng, Pageable pageable);

    ProductResponse findById(Long id);

    ProductResponse create(Long vendorUserId, ProductRequest request);

    ProductResponse update(Long productId, Long vendorUserId, ProductRequest request);


    void delete(Long productId, Long vendorUserId);

    ProductImage addImage(Long productId, Long vendorUserId, MultipartFile file,
                          String altText, boolean makeDefault);

    void deleteImage(Long imageId, Long vendorUserId);

    ProductImage setDefaultImage(Long imageId, Long vendorUserId);
}
