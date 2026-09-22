package com.example.metadata_service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.metadata_service.hashing.ConsistentHashRing;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
    }

    @Test
    void getReplicaNodes_onEmptyRing_returnsEmptyList() {
        List<String> replicas = ring.getReplicaNodes("some-chunk-id", 3);
        assertThat(replicas).isEmpty();
    }

    @Test
    void rebuild_withNodes_allowsPlacement() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));

        List<String> replicas = ring.getReplicaNodes("chunk-abc", 3);

        assertThat(replicas).isNotEmpty();
    }

    @Test
    void getReplicaNodes_returnsRequestedCount_whenEnoughNodesExist() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));

        List<String> replicas = ring.getReplicaNodes("chunk-xyz", 3);

        assertThat(replicas).hasSize(3);
    }

    @Test
    void getReplicaNodes_neverReturnsMoreThanAvailableDistinctNodes() {
        ring.rebuild(List.of("node1", "node2"));

        // Ask for 3 replicas but only 2 physical nodes exist
        List<String> replicas = ring.getReplicaNodes("chunk-limited", 3);

        assertThat(replicas).hasSize(2);
    }

    @Test
    void getReplicaNodes_returnsDistinctPhysicalNodes() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));

        List<String> replicas = ring.getReplicaNodes("chunk-distinct", 3);

        assertThat(replicas).doesNotHaveDuplicates();
    }

    @Test
    void getReplicaNodes_isDeterministic_forSameKeyAndSameRing() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));

        List<String> firstCall = ring.getReplicaNodes("chunk-stable", 3);
        List<String> secondCall = ring.getReplicaNodes("chunk-stable", 3);

        // Same key against an unchanged ring must always resolve to the
        // same replica set — this is the whole point of consistent hashing.
        assertThat(firstCall).isEqualTo(secondCall);
    }

    @Test
    void getReplicaNodes_differentKeys_canMapToDifferentPrimaries() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));

        // Not a strict guarantee for any two arbitrary keys, but across a
        // reasonable sample of distinct keys we expect more than one
        // distinct primary node to be selected -- this is what proves the
        // ring actually distributes rather than collapsing onto one node.
        var primaries = new java.util.HashSet<String>();
        for (int i = 0; i < 50; i++) {
            List<String> replicas = ring.getReplicaNodes("chunk-" + i, 3);
            primaries.add(replicas.get(0));
        }

        assertThat(primaries.size()).isGreaterThan(1);
    }

    @Test
    void rebuild_withFewerNodes_changesPlacementForAffectedKeys() {
        ring.rebuild(List.of("node1", "node2", "node3", "node4"));
        List<String> before = ring.getReplicaNodes("chunk-shrink", 3);

        // Simulate a node leaving the cluster
        ring.rebuild(List.of("node1", "node2", "node3"));
        List<String> after = ring.getReplicaNodes("chunk-shrink", 3);

        // The dead node must never appear in a rebuilt ring's results
        assertThat(after).doesNotContain("node4");
        assertThat(after).hasSize(3);
    }

    @Test
    void rebuild_isIdempotent_whenCalledWithSameNodesTwice() {
        ring.rebuild(List.of("node1", "node2", "node3"));
        List<String> firstResult = ring.getReplicaNodes("chunk-repeat", 2);

        ring.rebuild(List.of("node1", "node2", "node3"));
        List<String> secondResult = ring.getReplicaNodes("chunk-repeat", 2);

        assertThat(firstResult).isEqualTo(secondResult);
    }

    @Test
    void removeNode_excludesThatNodeFromFutureLookups() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        ring.removeNode("node2");

        // Run many keys through the ring; node2 must never be selected
        for (int i = 0; i < 30; i++) {
            List<String> replicas = ring.getReplicaNodes("key-" + i, 2);
            assertThat(replicas).doesNotContain("node2");
        }
    }
}
