package com.example.storage_node;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class StorageNodeApplication {

	public static void main(String[] args) {
		SpringApplication.run(StorageNodeApplication.class, args);
	}

}
