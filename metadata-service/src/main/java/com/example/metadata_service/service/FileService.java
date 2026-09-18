package com.example.metadata_service.service;

import com.example.metadata_service.dto.*;
import com.example.metadata_service.entity.*;
import com.example.metadata_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.metadata_service.hashing.ConsistentHashRing;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileMetadataRepository fileRepo;
    private final ChunkRepository chunkRepo;
    private final ConsistentHashRing hashRing;
    private final NodeDiscoveryService nodeDiscovery;

    private static final int REPLICATION_FACTOR = 3;

    public FileUploadResponse initiateUpload(FileUploadRequest request) {
        List<String> activeNodes = nodeDiscovery.getActiveStorageNodeIds();
        if (activeNodes.isEmpty()) {
            throw new RuntimeException("No storage nodes available");
        }
        hashRing.rebuild(activeNodes);

        int chunkCount = (int) Math.ceil((double) request.getTotalSize() / request.getChunkSize());

        FileMetadata file = new FileMetadata();
        file.setFileName(request.getFileName());
        file.setTotalSize(request.getTotalSize());
        file.setChunkSize(request.getChunkSize());
        file.setChunkCount(chunkCount);
        file.setStatus(FileStatus.UPLOADING);
        fileRepo.save(file);

        List<ChunkPlacement> placements = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            String chunkId = UUID.randomUUID().toString();

            int replicas = Math.min(REPLICATION_FACTOR, activeNodes.size());
            List<String> replicaNodes = hashRing.getReplicaNodes(chunkId, replicas);
            List<String> replicaUrls = replicaNodes.stream()
                    .map(nodeDiscovery::getNodeUrl)
                    .toList();

            Chunk chunk = new Chunk();
            chunk.setId(chunkId);
            chunk.setFileId(file.getId());
            chunk.setChunkIndex(i);
            chunk.setReplicaLocations(replicaNodes);
            chunkRepo.save(chunk);

            placements.add(new ChunkPlacement(i, chunkId, replicaUrls.get(0), replicaUrls));
        }

        return new FileUploadResponse(file.getId(), placements);
    }

    public void completeUpload(String fileId) {
        FileMetadata file = fileRepo.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found: " + fileId));
        file.setStatus(FileStatus.COMPLETE);
        fileRepo.save(file);
    }

    public FileDownloadResponse getFileForDownload(String fileId) {
        FileMetadata file = fileRepo.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found: " + fileId));

        List<Chunk> chunks = chunkRepo.findByFileIdOrderByChunkIndexAsc(fileId);

        List<ChunkLocation> locations = chunks.stream()
                .map(c -> {
                    List<String> replicas = c.getReplicaLocations();
                    String primaryNodeId = replicas.get(0);
                    String url = nodeDiscovery.getNodeUrl(primaryNodeId);
                    return new ChunkLocation(c.getChunkIndex(), c.getId(), url);
                })
                .toList();

        return new FileDownloadResponse(file.getId(), file.getFileName(), file.getStatus().name(), locations);
    }

}











// package com.example.metadata_service.service;

// import com.example.metadata_service.dto.*;
// import com.example.metadata_service.entity.*;
// import com.example.metadata_service.repository.*;
// import lombok.RequiredArgsConstructor;
// import org.springframework.stereotype.Service;

// import java.util.ArrayList;
// import java.util.List;
// import java.util.UUID;
// import com.example.metadata_service.hashing.ConsistentHashRing;

// @Service
// @RequiredArgsConstructor
// public class FileService {

//     private final FileMetadataRepository fileRepo;
//     private final ChunkRepository chunkRepo;
//     private final ConsistentHashRing hashRing;
//     private final NodeDiscoveryService nodeDiscovery;

//     private static final int REPLICATION_FACTOR = 3;

//     public FileUploadResponse initiateUpload(FileUploadRequest request) {
//         List<String> activeNodes = nodeDiscovery.getActiveStorageNodeIds();
//         if (activeNodes.isEmpty()) {
//             throw new RuntimeException("No storage nodes available");
//         }
//         hashRing.rebuild(activeNodes);

//         int chunkCount = (int) Math.ceil((double) request.getTotalSize() / request.getChunkSize());

//         FileMetadata file = new FileMetadata();
//         file.setFileName(request.getFileName());
//         file.setTotalSize(request.getTotalSize());
//         file.setChunkSize(request.getChunkSize());
//         file.setChunkCount(chunkCount);
//         file.setStatus(FileStatus.UPLOADING);
//         fileRepo.save(file);

//         List<ChunkPlacement> placements = new ArrayList<>();
//         for (int i = 0; i < chunkCount; i++) {
//             String chunkId = UUID.randomUUID().toString();

//             int replicas = Math.min(REPLICATION_FACTOR, activeNodes.size());
//             List<String> replicaNodes = hashRing.getReplicaNodes(chunkId, replicas);
//             List<String> replicaUrls = replicaNodes.stream()
//                     .map(nodeDiscovery::getNodeUrl)
//                     .toList();

//             Chunk chunk = new Chunk();
//             chunk.setId(chunkId);
//             chunk.setFileId(file.getId());
//             chunk.setChunkIndex(i);
//             chunk.setReplicaLocations(replicaNodes);
//             chunkRepo.save(chunk);

//             placements.add(new ChunkPlacement(i, chunkId, replicaUrls.get(0), replicaUrls));
//         }

//         return new FileUploadResponse(file.getId(), placements);
//     }

//     public void completeUpload(String fileId) {
//         FileMetadata file = fileRepo.findById(fileId)
//                 .orElseThrow(() -> new RuntimeException("File not found: " + fileId));
//         file.setStatus(FileStatus.COMPLETE);
//         fileRepo.save(file);
//     }

//     public FileDownloadResponse getFileForDownload(String fileId) {
//         FileMetadata file = fileRepo.findById(fileId)
//                 .orElseThrow(() -> new RuntimeException("File not found: " + fileId));

//         List<Chunk> chunks = chunkRepo.findByFileIdOrderByChunkIndexAsc(fileId);

//         List<ChunkLocation> locations = chunks.stream()
//                 .map(c -> new ChunkLocation(c.getChunkIndex(), c.getId(), "http://localhost:9001"))
//                 .toList();

//         return new FileDownloadResponse(file.getId(), file.getFileName(), file.getStatus().name(), locations);
//     }

// }