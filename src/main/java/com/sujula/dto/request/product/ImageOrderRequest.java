package com.sujula.dto.request.product;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** New display order for a product's images: every one of them, listed once, first shown first. */
public class ImageOrderRequest {

    @NotEmpty(message = "imageIds is required")
    private List<Long> imageIds;

    public List<Long> getImageIds() { return imageIds; }
    public void setImageIds(List<Long> imageIds) { this.imageIds = imageIds; }
}
