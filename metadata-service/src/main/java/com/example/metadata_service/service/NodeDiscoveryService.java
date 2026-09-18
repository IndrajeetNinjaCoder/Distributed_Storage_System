package com.example.metadata_service.service;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NodeDiscoveryService {

    private final DiscoveryClient discoveryClient;

    public List<String> getActiveStorageNodeIds() {
        List<ServiceInstance> instances = discoveryClient.getInstances("STORAGE-NODE");
        return instances.stream()
                .map(i -> i.getHost() + ":" + i.getPort())
                .toList();
    }

    public String getNodeUrl(String nodeId) {
        return "http://" + nodeId;
    }
}