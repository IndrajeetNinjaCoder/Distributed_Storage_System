package com.example.metadata_service.dto;

import lombok.Data;

@Data
public class FileUploadRequest {
    private String fileName;
    private Long totalSize;
    private Long chunkSize;
}