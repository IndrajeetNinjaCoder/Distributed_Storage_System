package com.example.metadata_service.repository;

import com.example.metadata_service.entity.Chunk;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<Chunk, String> {
    List<Chunk> findByFileIdOrderByChunkIndexAsc(String fileId);
}