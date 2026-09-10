package com.sujula.dto.request.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Attaches an image that has already been uploaded.
 *
 * <p>The bytes never pass through the API: the vendor presigns an upload, puts
 * the file straight into object storage, then posts the resulting URL here. The
 * service refuses any URL it does not recognise as its own.
 */
public class ProductImageRequest {

    @NotBlank(message = "Image URL is required")
    @Size(max = 500, message = "Image URL cannot exceed 500 characters")
    private String imageUrl;

    @Size(max = 200, message = "Alt text cannot exceed 200 characters")
    private String altText;

    /** Make this the card image. The first image of a product becomes default regardless. */
    private boolean makeDefault;

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public boolean isMakeDefault() { return makeDefault; }
    public void setMakeDefault(boolean makeDefault) { this.makeDefault = makeDefault; }
}
