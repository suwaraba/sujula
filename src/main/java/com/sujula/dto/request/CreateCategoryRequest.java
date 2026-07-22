package com.sujula.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import com.sujula.model.products.Category;

@Data
public class CreateCategoryRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 1000)
    private String description;

    @URL(message = "imageUrl must be a valid URL")
    @Size(max = 500)
    private String imageUrl;

    private Long parentId;
    private Integer sortOrder;
    private boolean active = true;
    
    
    
    public String getName() {
		return name;
	}



	public void setName(String name) {
		this.name = name;
	}



	public String getDescription() {
		return description;
	}



	public void setDescription(String description) {
		this.description = description;
	}



	public String getImageUrl() {
		return imageUrl;
	}



	public void setImageUrl(String imageUrl) {
		this.imageUrl = imageUrl;
	}



	public Long getParentId() {
		return parentId;
	}



	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}



	public Integer getSortOrder() {
		return sortOrder;
	}



	public void setSortOrder(Integer sortOrder) {
		this.sortOrder = sortOrder;
	}



	public boolean isActive() {
		return active;
	}



	public void setActive(boolean active) {
		this.active = active;
	}



	private Category toEntity(CreateCategoryRequest request, Category parent) {
	    Category category = new Category();

	    category.setName(request.getName());
	    category.setSlug(generateSlug(request.getName()));
	    category.setDescription(request.getDescription());
	    category.setImageUrl(request.getImageUrl());
	    category.setParent(parent);
	    category.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
	    category.setActive(request.isActive());

	    return category;
	}
	
    private String generateSlug(String name) {
        return name.toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
    }

      
    

}
