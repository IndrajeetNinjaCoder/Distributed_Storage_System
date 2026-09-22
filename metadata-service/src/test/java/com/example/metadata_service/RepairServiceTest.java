package com.example.metadata_service;

import com.example.metadata_service.entity.Chunk;
import com.example.metadata_service.hashing.ConsistentHashRing;
import com.example.metadata_service.repository.ChunkRepository;
import com.example.metadata_service.service.NodeDiscoveryService;
import com.example.metadata_service.service.RepairService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepairServiceTest {

    @Mock
    private ChunkRepository chunkRepo;
    @Mock
    private NodeDiscoveryService nodeDiscovery;
    @Mock
    private RestTemplate restTemplate;

    private ConsistentHashRing hashRing; // real instance -- it's pure logic, no need to mock
    private RepairService repairService;

    @BeforeEach
    void setUp() {
        hashRing = new ConsistentHashRing();
        repairService = new RepairService(chunkRepo, hashRing, nodeDiscovery, restTemplate);

        // node URL resolution is a simple, deterministic mapping in the real
        // implementation -- stub it the same way for every test
        lenient().when(nodeDiscovery.getNodeUrl(anyString()))
                .thenAnswer(invocation -> "http://" + invocation.getArgument(0));
    }

    private Chunk chunkWithReplicas(String id, List<String> replicas) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setChunkIndex(0);
        chunk.setReplicaLocations(new ArrayList<>(replicas));
        return chunk;
    }

    @Test
    void runRepair_withNoActiveNodes_skipsCycleWithoutTouchingChunks() {
        when(nodeDiscovery.getActiveStorageNodeIds()).thenReturn(List.of());

        repairService.runRepair();

        verify(chunkRepo, never()).findAll();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void runRepair_whenChunkHasNoReplicaLocations_isSkipped() {
        when(nodeDiscovery.getActiveStorageNodeIds())
                .thenReturn(List.of("node1", "node2", "node3"));

        Chunk chunkWithNoReplicas = new Chunk();
        chunkWithNoReplicas.setId("orphan-chunk");
        chunkWithNoReplicas.setReplicaLocations(null);

        when(chunkRepo.findAll()).thenReturn(List.of(chunkWithNoReplicas));

        repairService.runRepair();

        verifyNoInteractions(restTemplate);
        verify(chunkRepo, never()).save(any());
    }

    @Test
    void runRepair_whenChunkAlreadyCorrectlyPlaced_doesNothing() {
        List<String> activeNodes = List.of("node1", "node2", "node3");
        when(nodeDiscovery.getActiveStorageNodeIds()).thenReturn(activeNodes);
        hashRing.rebuild(activeNodes);

        String chunkId = "already-healthy-chunk";
        // whatever the ring says is ideal for this chunk IS its current placement
        List<String> idealReplicas = hashRing.getReplicaNodes(chunkId, 3);
        Chunk chunk = chunkWithReplicas(chunkId, idealReplicas);

        when(chunkRepo.findAll()).thenReturn(List.of(chunk));

        repairService.runRepair();

        verifyNoInteractions(restTemplate);
        verify(chunkRepo, never()).save(any());
    }

    @Test
    void runRepair_whenNoSurvivingReplicaExists_logsDataLossAndDoesNotCrash() {
        when(nodeDiscovery.getActiveStorageNodeIds())
                .thenReturn(List.of("node1", "node2", "node3"));

        // every replica this chunk had is now a dead node not in the active list
        Chunk chunk = chunkWithReplicas("unrecoverable-chunk",
                List.of("dead-node-a", "dead-node-b"));

        when(chunkRepo.findAll()).thenReturn(List.of(chunk));

        repairService.runRepair();

        // nothing to copy from -- must not attempt any HTTP calls or save a
        // fabricated replica list
        verifyNoInteractions(restTemplate);
        verify(chunkRepo, never()).save(any());
    }

    @Test
    void runRepair_withMissingReplica_copiesFromSurvivorAndUpdatesChunk() {
        List<String> activeNodes = List.of("node1", "node2", "node3");
        when(nodeDiscovery.getActiveStorageNodeIds()).thenReturn(activeNodes);
        hashRing.rebuild(activeNodes);

        String chunkId = "needs-repair-chunk";
        // Only ONE of this chunk's original replicas is still alive --
        // simulating that node2 and node3 (or whichever) died and were
        // replaced in the active set.
        Chunk chunk = chunkWithReplicas(chunkId, List.of("node1"));

        when(chunkRepo.findAll()).thenReturn(List.of(chunk));
        byte[] fakeBytes = "chunk-bytes".getBytes();
        when(restTemplate.getForObject(contains("node1"), eq(byte[].class)))
                .thenReturn(fakeBytes);

        repairService.runRepair();

        // bytes must be read from the surviving node and written to at
        // least one new target to fill out the replica set
        verify(restTemplate, atLeastOnce())
                .getForObject(contains("node1"), eq(byte[].class));
        verify(restTemplate, atLeastOnce())
                .put(contains("/internal"), eq(fakeBytes));

        verify(chunkRepo).save(argThat(saved ->
                saved.getReplicaLocations().contains("node1") &&
                saved.getReplicaLocations().size() > 1
        ));
    }

    @Test
    void runRepair_whenTargetCopyFails_doesNotCrashAndSkipsThatTarget() {
        List<String> activeNodes = List.of("node1", "node2", "node3");
        when(nodeDiscovery.getActiveStorageNodeIds()).thenReturn(activeNodes);
        hashRing.rebuild(activeNodes);

        Chunk chunk = chunkWithReplicas("flaky-repair-chunk", List.of("node1"));
        when(chunkRepo.findAll()).thenReturn(List.of(chunk));

        when(restTemplate.getForObject(anyString(), eq(byte[].class)))
                .thenReturn("data".getBytes());
        doThrow(new RuntimeException("target node unreachable"))
                .when(restTemplate).put(anyString(), any());

        // must not propagate the exception -- a single failed target should
        // not abort the whole repair cycle
        assertThat(catchException(() -> repairService.runRepair())).isNull();
    }

    private static Throwable catchException(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
