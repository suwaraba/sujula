package com.sujula.service;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

public interface StorageService {


    //String upload(MultipartFile file, String folder, String filename);

    String presignUpload(String folder, String filename, String contentType, Duration ttl);

    void delete(String publicUrl);

     String publicUrl(String folder, String filename);

    boolean isManagedUrl(String url, String folder);
}
