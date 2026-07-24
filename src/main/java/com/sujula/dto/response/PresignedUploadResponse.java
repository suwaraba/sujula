package com.sujula.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresignedUploadResponse {
    private String uploadUrl;
    private String publicUrl;
}