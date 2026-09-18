package com.example.metadata_service.dto;

import lombok.Data;

import java.util.List;

import lombok.AllArgsConstructor;

@Data
@AllArgsConstructor
public class ChunkPlacement {
    private Integer chunkIndex;
    private String chunkId;
    private String storageNodeUrl;      // primary — client uploads here
    private List<String> replicaUrls;   // ALL replica URLs including primary, for replication
}