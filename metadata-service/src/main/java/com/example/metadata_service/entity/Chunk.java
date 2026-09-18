package com.example.metadata_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Entity
@Getter
@Setter
public class Chunk {

    @Id
    private String id;

    private String fileId;
    private Integer chunkIndex;
    private String checksum;

    @ElementCollection
    private List<String> replicaLocations;
}