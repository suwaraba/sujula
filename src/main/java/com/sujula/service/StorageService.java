package com.sujula.service;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {


    String upload(MultipartFile file, String folder, String filename);

    String upload(MultipartFile file, String folder);

    void delete(String publicUrl);

    String publicUrl(String key);
}
