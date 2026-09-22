package com.example.storage_node.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Arrays;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.example.storage_node.util.PresignedUrlSigner;

@RestController
@RequestMapping("/chunks")
public class ChunkController {

    @Value("${storage.base-path}")
    private String basePath;

    @Value("${server.port}")
    private String selfPort;

    private final RestTemplate restTemplate;
    private final PresignedUrlSigner urlSigner;

    public ChunkController(RestTemplate restTemplate, PresignedUrlSigner urlSigner) {
        this.restTemplate = restTemplate;
        this.urlSigner = urlSigner;
    }

    @PutMapping("/{chunkId}")
    public ResponseEntity<String> storeChunk(@PathVariable String chunkId,
                                              @RequestParam(required = false) String replicas,
                                              @RequestBody byte[] data) throws IOException {
        Path dir = Paths.get(basePath);
        Files.createDirectories(dir);
        Files.write(dir.resolve(chunkId), data);

        String checksum = computeChecksum(data);

        if (replicas != null && !replicas.isBlank()) {
            List<String> replicaUrls = Arrays.asList(replicas.split(","));
            for (String url : replicaUrls) {
                if (url.contains(getSelfIdentifier())) continue;

                String internalUrl = url.replace("localhost", "host.docker.internal");

                try {
                    restTemplate.put(internalUrl + "/chunks/" + chunkId + "/internal", data);
                } catch (Exception e) {
                    System.err.println("Replication to " + internalUrl + " failed: " + e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(checksum);
    }

    @PutMapping("/{chunkId}/internal")
    public ResponseEntity<Void> storeChunkInternal(@PathVariable String chunkId,
                                                     @RequestBody byte[] data) throws IOException {
        Path dir = Paths.get(basePath);
        Files.createDirectories(dir);
        Files.write(dir.resolve(chunkId), data);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{chunkId}")
    public ResponseEntity<byte[]> getChunk(@PathVariable String chunkId,
                                            @RequestParam(required = false) Long expires,
                                            @RequestParam(required = false) String sig) throws IOException {

        if (expires != null && sig != null) {
            if (!urlSigner.isValid(chunkId, expires, sig)) {
                return ResponseEntity.status(403).build();
            }
        }

        Path file = Paths.get(basePath).resolve(chunkId);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readAllBytes(file));
    }

    private String getSelfIdentifier() {
        return ":" + selfPort;
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}