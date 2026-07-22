package com.sujula.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

import com.sujula.model.products.Category;

@Data
public class CategoryResponse {

    private Long          id;
    private String        name;
    private String        slug;
    private String        description;
    private String        imageUrl;
    private boolean       active;
    private Integer       sortOrder;
    private Long          parentId;
    private String        parentName;
    private LocalDateTime createdAt;
    
    
    

    public Long getId() {
		return id;
	}




	public void setId(Long id) {
		this.id = id;
	}




	public String getName() {
		return name;
	}




	public void setName(String name) {
		this.name = name;
	}




	public String getSlug() {
		return slug;
	}




	public void setSlug(String slug) {
		this.slug = slug;
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




	public boolean isActive() {
		return active;
	}




	public void setActive(boolean active) {
		this.active = active;
	}




	public Integer getSortOrder() {
		return sortOrder;
	}




	public void setSortOrder(Integer sortOrder) {
		this.sortOrder = sortOrder;
	}




	public Long getParentId() {
		return parentId;
	}




	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}




	public String getParentName() {
		return parentName;
	}




	public void setParentName(String parentName) {
		this.parentName = parentName;
	}




	public LocalDateTime getCreatedAt() {
		return createdAt;
	}




	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}




	public static CategoryResponse from(Category c) {
        CategoryResponse r = new CategoryResponse();
        r.setId(c.getId());
        r.setName(c.getName());
        r.setSlug(c.getSlug());
        r.setDescription(c.getDescription());
        r.setImageUrl(c.getImageUrl());
        r.setActive(c.isActive());
        r.setSortOrder(c.getSortOrder());
        r.setCreatedAt(c.getCreatedAt());
        if (c.getParent() != null) {
            r.setParentId(c.getParent().getId());
            r.setParentName(c.getParent().getName());
        }
        return r;
    }
}
