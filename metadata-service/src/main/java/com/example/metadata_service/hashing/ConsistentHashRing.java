package com.example.metadata_service.hashing;

import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class ConsistentHashRing {

    private static final int VIRTUAL_NODES = 150;
    private final SortedMap<Long, String> ring = new TreeMap<>();

    public void addNode(String nodeId) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            long hash = hash(nodeId + "#VN" + i);
            ring.put(hash, nodeId);
        }
    }

    public void removeNode(String nodeId) {
        ring.entrySet().removeIf(entry -> entry.getValue().equals(nodeId));
    }

    public void rebuild(List<String> activeNodeIds) {
        ring.clear();
        activeNodeIds.forEach(this::addNode);
    }

    public List<String> getReplicaNodes(String key, int replicaCount) {
        if (ring.isEmpty()) return List.of();

        long hash = hash(key);
        Set<String> result = new LinkedHashSet<>();
        SortedMap<Long, String> tailMap = ring.tailMap(hash);

        var iterator = tailMap.values().iterator();
        while (result.size() < replicaCount && result.size() < countDistinctNodes()) {
            if (!iterator.hasNext()) {
                iterator = ring.values().iterator();
            }
            result.add(iterator.next());
        }

        return List.copyOf(result);
    }

    private long countDistinctNodes() {
        return ring.values().stream().distinct().count();
    }

    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes());
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xFF);
            }
            return h;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}