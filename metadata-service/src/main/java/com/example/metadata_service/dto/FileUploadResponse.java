package com.example.metadata_service.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
public class FileUploadResponse {
    private String fileId;
    private List<ChunkPlacement> chunks;
}