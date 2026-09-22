# Distributed File Storage Service

A backend system built using **Spring Boot and Microservices Architecture** that implements the core mechanics of an object store like S3 — chunked storage, replication, consistent-hashing-based placement, failure repair, and presigned access URLs.

This project was built as a hands-on implementation of distributed storage concepts, going beyond typical CRUD/microservices projects into replication, consistency, and fault tolerance.

---

## 🚀 Project Overview

The system currently supports:

* Splitting uploaded files into fixed-size chunks
* Placing chunks across multiple storage nodes using consistent hashing
* Replicating each chunk to N storage nodes for durability
* Verifying chunk integrity via checksums
* Detecting dead storage nodes and repairing (re-replicating) their chunks
* Generating time-limited presigned URLs for chunk access
* Downloading files by reassembling chunks from any healthy replica

---

## 🏗️ Architecture

```text
                                Client
                                  |
                       +----------+-----------+
                       |                       |
                       v                       v
              Metadata Service          Storage Node Service
                 (:8090)                 (multiple instances:
                       |                  :9001, :9002, :9003, :9004)
                       |                        ^
                       +------ discovers -------+
                              via Eureka
                       |
                       v
                +--------------+
                |   Eureka     |
                |   Server     |
                |   (:8761)    |
                +--------------+
```

**Dependency direction:**

* **Client → Metadata Service** — for all file/chunk operations (create file, get placement info, list files).
* **Client → Storage Node(s) directly** — for actual chunk byte transfer (upload/download), bypassing Metadata Service to avoid it becoming a throughput bottleneck.
* **Metadata Service → Eureka** — to discover which storage nodes are alive and to pick placement targets via the hash ring.
* **Storage Nodes** do not call each other directly for client-facing reads; replication on upload is server-side (primary node replicates to secondaries), and repair-driven copying is orchestrated by the Metadata Service.

Each storage node is a **dumb blob store** — it has no knowledge of files, chunks belonging to other nodes, or replication logic. It only stores and serves chunk bytes by ID.

---

## 🧩 Microservices

### 1. Eureka Server
Service registry that all storage nodes register with, enabling the Metadata Service to discover live nodes for placement and health checks — no hardcoded node lists.

### 2. Metadata Service
Owns all file and chunk metadata. Responsible for:
- Splitting files into chunks and assigning replica placement via consistent hashing
- Tracking chunk locations and replica health
- Generating presigned URLs
- Running the scheduled repair job

```text
POST /files                          → initiate upload, get chunk placement plan
GET  /files/{id}                     → get file metadata + chunk locations for download
GET  /files/{id}/chunks/{index}/url  → get presigned URL for a chunk
```

### 3. Storage Node Service
Owns raw chunk bytes on local disk. Multiple instances of the same service, each an independent node on the hash ring.

```text
PUT /chunks/{chunkId}   → store chunk bytes
GET /chunks/{chunkId}   → retrieve chunk bytes (validates presigned signature + expiry)
```

---

## 🗄️ Entity Structure

### File

| Column       | Type          | Constraints                                    |
|--------------|---------------|-------------------------------------------------|
| id           | UUID          | Primary Key                                    |
| fileName     | String        | Not Null                                       |
| totalSize    | Long          | Not Null                                       |
| chunkSize    | Long          | Not Null                                       |
| chunkCount   | Integer       | Not Null                                       |
| status       | Enum (String) | Not Null — `UPLOADING`, `COMPLETE`, `FAILED`   |
| createdAt    | LocalDateTime | Not updatable                                  |
| updatedAt    | LocalDateTime |                                                 |

### Chunk

| Column           | Type          | Constraints                                          |
|------------------|---------------|--------------------------------------------------------|
| id               | UUID          | Primary Key                                            |
| fileId           | UUID          | Not Null (foreign key reference, no JPA relation)      |
| chunkIndex       | Integer       | Not Null                                               |
| checksum         | String        | Not Null (SHA-256 of chunk bytes)                      |
| replicaLocations | List\<String> | Not Null — storage node IDs currently holding this chunk |
| createdAt        | LocalDateTime | Not updatable                                          |

### StorageNode

| Column        | Type          | Constraints                              |
|---------------|---------------|---------------------------------------------|
| id            | String        | Primary Key (matches Eureka instance ID) |
| host          | String        | Not Null                                 |
| port          | Integer       | Not Null                                 |
| status        | Enum (String) | Not Null — `ACTIVE`, `DEAD`              |
| lastHeartbeat | LocalDateTime |                                           |

> `fileId` on `Chunk` is a plain foreign key, not a JPA relation — File and Chunk metadata live together in the Metadata Service's own database, but chunk *bytes* live entirely outside it, on separate Storage Node services.

> Chunk placement is never hardcoded — it is computed at upload time by hashing `chunkId` onto a consistent-hashing ring built from currently `ACTIVE` storage nodes.

---

## 🔄 Upload Flow

```text
Client
   |
   v
POST /files (Metadata Service)
   |
   v
Split file into chunks, hash each chunk onto the ring,
assign N replica targets per chunk (e.g. N=3)
   |
   v
Return chunk-to-node placement plan to client
   |
   v
Client uploads each chunk directly to its primary storage node
   |
   v
Primary node replicates chunk to the other N-1 assigned nodes
   |
   v
Metadata Service marks File status COMPLETE
```

## 🔽 Download Flow

```text
Client
   |
   v
GET /files/{id} (Metadata Service)
   |
   v
Returns chunk list with all replica locations per chunk
   |
   v
Client fetches each chunk from any one healthy replica (failover if one is down)
   |
   v
Client reassembles chunks in order into the original file
```

## 🛠️ Repair Flow

```text
Scheduled job (Metadata Service, every N minutes)
   |
   v
For each chunk, check replicaLocations against Eureka node health
   |
   v
If a replica's node is DEAD:
   - pick a new target via the hash ring
   - fetch chunk bytes from a surviving healthy replica
   - push bytes to the new target node
   - update replicaLocations
```

---

## 🔑 Consistent Hashing (Placement Strategy)

* A hash ring is built from all `ACTIVE` storage nodes, with each physical node mapped to multiple **virtual nodes** (e.g. 100–200 points) for even load distribution.
* `hash(chunkId)` is placed on the ring; the primary replica is the next node clockwise, and the next `N-1` distinct physical nodes clockwise become the additional replicas.
* When a node joins or leaves, only the chunks that fall within that node's range on the ring need to be moved — not the entire dataset.

---

## 🔗 Presigned URLs

* Metadata Service generates a signed URL per chunk request:
  `{nodeUrl}/chunks/{chunkId}?expires={timestamp}&sig={HMAC-SHA256(chunkId+expires, secretKey)}`
* The Storage Node independently verifies the signature and expiry before serving the chunk — no callback to the Metadata Service needed on every read.

---

## 🛠️ Technology Stack

* **Language:** Java 21
* **Framework:** Spring Boot
* **Data:** Spring Data JPA / Hibernate (Metadata Service)
* **Service Discovery:** Netflix Eureka
* **File I/O:** Java NIO / `RandomAccessFile` for chunked read-write on Storage Nodes
* **Integrity:** `MessageDigest` (SHA-256) for chunk checksums
* **Security:** `javax.crypto.Mac` (HMAC-SHA256) for presigned URL signing
* **Scheduling:** Spring `@Scheduled` for the repair job
* **Containerization:** Docker + Docker Compose (for running multiple storage node instances)
* **Load Testing:** k6
* **Build tool:** Maven
* **Testing tool:** Postman

---

## ▶️ Running the Project

### Prerequisites

```text
Java 21+
Maven
Docker (optional, for running multiple storage node instances)
```

### Start order

```text
1. Eureka Server            (localhost:8761)
2. Storage Node instances   (localhost:9001, 9002, 9003, 9004)
3. Metadata Service         (localhost:8090)
```

Once all storage nodes show as `UP` on the Eureka dashboard (`http://localhost:8761`), files can be uploaded and downloaded through the Metadata Service:

```text
POST http://localhost:8090/files
GET  http://localhost:8090/files/{id}
```

---

## 📌 Roadmap / Planned Improvements

* Multipart streaming upload support for very large files (avoid full in-memory buffering)
* Configurable replication factor per file
* Metrics/dashboard for storage node utilization and ring balance
* Load test results (throughput, latency under node failure) published in this README
* Docker Compose file for one-command startup of the full cluster
* Basic authentication on the Metadata Service API
* Unit/integration tests (JUnit + Mockito) for placement and repair logic

---

## 📖 Learning Objective

This project's goal was to move beyond request/response CRUD microservices and get hands-on with the mechanics of a distributed storage system — chunking, consistent-hash-based placement, replication, and failure detection/repair — the concepts underlying real systems like S3, HDFS, and Cassandra, built incrementally and understood well enough to defend design decisions in detail.
