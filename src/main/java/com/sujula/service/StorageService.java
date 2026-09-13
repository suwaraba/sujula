package com.sujula.service;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

public interface StorageService {


    //String upload(MultipartFile file, String folder, String filename);

    String presignUpload(String folder, String filename, String contentType, Duration ttl);

    void delete(String publicUrl);

     String publicUrl(String folder, String filename);

    boolean isManagedUrl(String url, String folder);

    /**
     * Reads an object back.
     *
     * <p>Needed by bulk import: the seller's spreadsheet goes straight into
     * storage and the worker picks it up from there, so the file never passes
     * through a request thread. Empty when there is nothing at that key, which
     * is the ordinary case for an upload the client abandoned.
     */
    java.util.Optional<byte[]> download(String url);

    /**
     * Writes an object this server generated, and returns where it landed.
     *
     * <p>For an export, which is produced by a worker with no client on the
     * other end to presign for. Everything a user uploads still goes through
     * {@link #presignUpload} instead: bytes that pass through an application
     * server are bytes in an access log and a heap dump.
     */
    String upload(String folder, String filename, byte[] content, String contentType);
}
