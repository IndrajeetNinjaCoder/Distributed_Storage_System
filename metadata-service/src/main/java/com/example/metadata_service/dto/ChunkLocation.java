package com.example.metadata_service.dto;

import lombok.Data;
import lombok.AllArgsConstructor;

@Data
@AllArgsConstructor
public class ChunkLocation {
    private Integer chunkIndex;
    private String chunkId;
    private String storageNodeUrl; // still hardcoded to one node for now
}