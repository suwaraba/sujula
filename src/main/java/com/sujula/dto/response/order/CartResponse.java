package com.sujula.dto.response.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import lombok.Builder;
import lombok.Data;

import java.util.Comparator;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartResponse {

    private Long id;
    private String name;
    private String slug;
    private String imageUrl;

    public static CartResponse from(Product product) {
        return CartResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .imageUrl(primaryImageUrl(product))
                .build();
    }

    private static String primaryImageUrl(Product product) {
        return product.getImages().stream()
                .filter(ProductImage::isDefault)
                .findFirst()
                .or(() -> product.getImages().stream()
                        .min(Comparator.comparing(ProductImage::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))))
                .map(ProductImage::getImageUrl)
                .orElse(null);
    }
}