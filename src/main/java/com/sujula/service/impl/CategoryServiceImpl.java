package com.sujula.service.impl;

import com.sujula.repository.product.CategoryRepository;
import com.sujula.service.CategoryService;
import com.sujula.util.Utils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.CreateCategoryRequest;
import com.sujula.dto.response.CategoryResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.products.Category;


@Service
public class CategoryServiceImpl implements CategoryService {


	    private final CategoryRepository categoryRepository;

	    

	    public CategoryServiceImpl(CategoryRepository categoryRepository) {
			this.categoryRepository = categoryRepository;
		}



		private Category getCategory(Long id) {
	        return categoryRepository.findByIdFetchParent(id)
	                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
	    }

	 

	    @Transactional(readOnly = true)
	    @Override
	    public Page<CategoryResponse> findAll(Pageable pageable) {
	        return categoryRepository.findAllFetchParent(pageable)
	                .map(CategoryResponse::from);
	    }

	    @Transactional(readOnly = true)
	    @Override
	    public CategoryResponse findById(Long id) {
	        return CategoryResponse.from(getCategory(id));
	    }

	    @Transactional
	    @Override
	    public CategoryResponse create(CreateCategoryRequest request) {
	        String slug = Utils.toSlug(request.getName());
	        if (categoryRepository.existsBySlug(slug)) {
	            slug = slug + "-" + System.currentTimeMillis();
	        }

	        Category parent = null;
	        if (request.getParentId() != null) {
	            parent = getCategory(request.getParentId());
	        }

	        Category c = new Category();
	        
	                c.setName(request.getName());
	                c.setSlug(slug);
	                c.setDescription(request.getDescription());
	                c.setImageUrl(request.getImageUrl());
	                c.setParent(parent);
	                c.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
	                c.setActive(request.isActive());

	        return CategoryResponse.from(categoryRepository.save(c));
	    }

	    @Transactional
	    @Override
	    public CategoryResponse update(Long id, CreateCategoryRequest request) {
	        Category category = getCategory(id);

	        if (request.getParentId() != null) {
	            if (request.getParentId().equals(id)) {
	                throw new BadRequestException("A category cannot be its own parent");
	            }
	            category.setParent(getCategory(request.getParentId()));
	        } else {
	            category.setParent(null);
	        }

	        category.setName(request.getName());
	        category.setDescription(request.getDescription());
	        category.setImageUrl(request.getImageUrl());
	        category.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : category.getSortOrder());
	        category.setActive(request.isActive());

	        return CategoryResponse.from(categoryRepository.save(category));
	    }

	    @Transactional
	    @Override
	    public void delete(Long id) {
	        Category category = categoryRepository.findById(id)
	                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
	        if (!category.getChildren().isEmpty()) {
	            throw new BadRequestException(
	                    "Cannot delete a category that has sub-categories. Remove or reassign them first.");
	        }
	        categoryRepository.delete(category);
	    }


	}
