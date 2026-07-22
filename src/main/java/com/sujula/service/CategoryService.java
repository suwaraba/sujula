package com.sujula.service;


import com.sujula.dto.request.CreateCategoryRequest;
import com.sujula.dto.response.CategoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

public interface CategoryService    {

    @Transactional(readOnly = true)
    Page<CategoryResponse> findAll(Pageable pageable);

    @Transactional(readOnly = true)
    CategoryResponse findById(Long id);

    @Transactional
    CategoryResponse create(CreateCategoryRequest request);



    @Transactional
    CategoryResponse update(Long id, CreateCategoryRequest request);

    @Transactional
    void delete(Long id);
}