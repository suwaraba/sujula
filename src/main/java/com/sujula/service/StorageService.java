package com.sujula.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StorageService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"
    );

    private final S3Client r2Client;
    private final R2Properties r2Properties;

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

    /**
     * Deletes an object from R2 by its public URL or object key.
     *
     * @param urlOrKey full public URL or bare object key
     */
    public void delete(String urlOrKey) {
        String key = extractKey(urlOrKey);

        r2Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(r2Properties.getBucketName())
                .key(key)
                .build());
    }

    /**
     * Builds the public URL for a given object key.
     */
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
