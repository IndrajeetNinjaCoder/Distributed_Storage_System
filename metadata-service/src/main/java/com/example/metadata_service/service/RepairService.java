package com.example.metadata_service.service;

import com.example.metadata_service.entity.Chunk;
import com.example.metadata_service.hashing.ConsistentHashRing;
import com.example.metadata_service.repository.ChunkRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RepairService {

    private final ChunkRepository chunkRepo;
    private final ConsistentHashRing hashRing;
    private final NodeDiscoveryService nodeDiscovery;
    private final RestTemplate restTemplate;

    @Value("${internal.running-in-docker}")
    private boolean runningInDocker;

    private static final int REPLICATION_FACTOR = 3;

    private String toInternalUrl(String url) {
        return runningInDocker ? url.replace("localhost", "host.docker.internal") : url;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void runRepair() {
        List<String> activeNodes = nodeDiscovery.getActiveStorageNodeIds();
        if (activeNodes.isEmpty()) {
            System.out.println("[Repair] No active nodes — skipping repair cycle");
            return;
        }
        hashRing.rebuild(activeNodes);

        List<Chunk> allChunks = chunkRepo.findAll();
        int repairedCount = 0;

        for (Chunk chunk : allChunks) {
            List<String> oldReplicas = chunk.getReplicaLocations();
            if (oldReplicas == null || oldReplicas.isEmpty()) continue;

            int replicas = Math.min(REPLICATION_FACTOR, activeNodes.size());
            List<String> idealReplicas = hashRing.getReplicaNodes(chunk.getId(), replicas);

            List<String> survivingOldReplicas = oldReplicas.stream()
                    .filter(activeNodes::contains)
                    .toList();

            if (survivingOldReplicas.isEmpty()) {
                System.out.println("[Repair] Chunk " + chunk.getId() +
                        " has NO surviving replicas — cannot repair, data may be lost!");
                continue;
            }

            List<String> missingReplicas = idealReplicas.stream()
                    .filter(node -> !survivingOldReplicas.contains(node))
                    .toList();

            if (missingReplicas.isEmpty()) {
                continue;
            }

            String sourceNode = survivingOldReplicas.get(0);
            String sourceUrl = nodeDiscovery.getNodeUrl(sourceNode);

            for (String targetNode : missingReplicas) {
                String targetUrl = nodeDiscovery.getNodeUrl(targetNode);
                try {
                    byte[] data = restTemplate.getForObject(
                            toInternalUrl(sourceUrl) + "/chunks/" + chunk.getId(), byte[].class);
                    restTemplate.put(
                            toInternalUrl(targetUrl) + "/chunks/" + chunk.getId() + "/internal", data);
                    System.out.println("[Repair] Chunk " + chunk.getId() +
                            " copied from " + sourceNode + " to " + targetNode);
                    repairedCount++;
                } catch (Exception e) {
                    System.err.println("[Repair] Failed to repair chunk " + chunk.getId() +
                            " to " + targetNode + ": " + e.getMessage());
                }
            }

            List<String> newReplicaList = new ArrayList<>(survivingOldReplicas);
            for (String node : missingReplicas) {
                if (!newReplicaList.contains(node)) newReplicaList.add(node);
            }
            chunk.setReplicaLocations(newReplicaList);
            chunkRepo.save(chunk);
        }

        if (repairedCount > 0) {
            System.out.println("[Repair] Cycle complete — " + repairedCount + " chunk(s) repaired");
        }
    }
}