package com.example.metadata_service.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
public class ChunkLocation {
    private Integer chunkIndex;
    private String chunkId;
    private List<String> presignedUrls; // one signed URL per replica, in order
    private String checksum;
}