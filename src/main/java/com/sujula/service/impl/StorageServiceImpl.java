package com.sujula.service.impl;


import com.sujula.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final S3Client r2Client;
    private final R2Properties r2Properties;

    @Override
    public String upload(MultipartFile file, String folder, String filename) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File must not be empty");
        }

        String key = folder + "/" + filename;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(r2Properties.getBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            r2Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new BadRequestException("Failed to read uploaded file: " + e.getMessage());
        } catch (Exception e) {
            log.error("R2 upload failed for key={}: {}", key, e.getMessage(), e);
            throw new BadRequestException("Storage upload failed: " + e.getMessage());
        }

        // Return the public CDN URL
        return r2Properties.getPublicUrl().stripTrailing() + "/" + key;
    }

    @Override
    public void delete(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) return;

        // Derive the object key from the public URL
        String prefix = r2Properties.getPublicUrl().stripTrailing() + "/";
        if (!publicUrl.startsWith(prefix)) {
            log.warn("Cannot delete R2 object — URL does not match public base: {}", publicUrl);
            return;
        }

        String key = publicUrl.substring(prefix.length());
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(r2Properties.getBucketName())
                    .key(key)
                    .build());
        } catch (Exception e) {
            // Log but do not throw — deletion failures should not block business operations
            log.warn("R2 delete failed for key={}: {}", key, e.getMessage());
        }
    }

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"
    );

    private final S3Client r2Client;
    private final R2Properties r2Properties;

    @Override
    public String upload(MultipartFile file, String folder) {
        validateContentType(file);

        String key = buildKey(folder, file.getOriginalFilename());

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(r2Properties.getBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            r2Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new BadRequestException("Failed to read uploaded file: " + e.getMessage());
        }

        return publicUrl(key);
    }


    @Override
    public void delete(String urlOrKey) {
        String key = extractKey(urlOrKey);

        r2Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(r2Properties.getBucketName())
                .key(key)
                .build());
    }

    @Override
    public String publicUrl(String key) {
        String base = r2Properties.getPublicUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildKey(String folder, String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return folder.replaceAll("/$", "") + "/" + UUID.randomUUID() + ext;
    }

    private String extractKey(String urlOrKey) {
        String base = r2Properties.getPublicUrl();
        if (!base.endsWith("/")) base = base + "/";
        return urlOrKey.startsWith(base) ? urlOrKey.substring(base.length()) : urlOrKey;
    }

    private void validateContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BadRequestException(
                    "Unsupported file type: " + contentType + ". Allowed: " + ALLOWED_TYPES
            );
        }
    }
}
