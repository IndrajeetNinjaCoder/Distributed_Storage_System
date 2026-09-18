// DTO
package com.example.metadata_service.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
public class FileDownloadResponse {
    private String fileId;
    private String fileName;
    private String status;
    private List<ChunkLocation> chunks;
}