# Distributed File Storage Service

A backend system built with **Spring Boot and Microservices Architecture** that implements the core mechanics of a real object store like S3 — chunked storage, consistent-hash-based placement, real multi-node replication, integrity verification, presigned access URLs, and automated self-healing on node failure.

This project was built to go beyond typical CRUD/microservices demos into the genuinely hard parts of distributed systems: replication correctness, failure detection, data integrity, and the trade-offs that come with all three — each one implemented, broken on purpose, debugged, and proven with real evidence rather than assumed to work.

---

## 🚀 What This System Actually Does

- Splits uploaded files into fixed-size chunks and places them across multiple storage nodes using **consistent hashing** (150 virtual nodes per physical node)
- **Replicates** every chunk to N nodes (default 3) — real bytes verified on real, independent nodes, not just recorded intent
- Computes a **SHA-256 checksum** per chunk and verifies it on every download — proven to catch real corruption, not just designed to
- Generates **HMAC-SHA256 presigned URLs**, time-limited and tamper-proof, for all chunk access — proven to reject both expired and altered URLs
- Runs a **self-healing repair job** (scheduled + manually triggerable) that detects chunks with dead-node replicas via Eureka and re-replicates them from a surviving copy — proven by killing a live container mid-lifecycle and confirming the file stayed fully downloadable
- Ships as **one command** via Docker Compose — Postgres, Eureka, Metadata Service, and 4 Storage Nodes, all wired together
- Has been **load tested** with k6 under concurrent traffic, including killing a node mid-test, with real throughput/latency numbers and a documented capacity bottleneck
- Has an automated **JUnit + Mockito test suite** (16 tests) validating the consistent-hashing algorithm and repair logic in isolation

---

## 🏗️ Architecture

```text
                                Client
                                  |
                       +----------+-----------+
                       |                       |
                       v                       v
              Metadata Service          Storage Node Service
                 (:8090)                 (4 instances:
                       |                  :9001, :9002, :9003, :9004)
                       |                        ^
                       +------ discovers -------+
                              via Eureka
                       |
                       v
                +--------------+          +--------------+
                |   Eureka     |          |  PostgreSQL  |
                |   Server     |          |    (:5432)   |
                |   (:8761)    |          +--------------+
                +--------------+
```

**Dependency direction:**

- **Client → Metadata Service** — for all file/chunk operations (create file, get placement plan, list/download files)
- **Client → Storage Node(s) directly** — for actual chunk byte transfer, bypassing Metadata Service so it never becomes a throughput bottleneck
- **Metadata Service → Eureka** — to discover live storage nodes for placement, replication targeting, and repair decisions
- **Storage Nodes replicate to each other directly** (server-side, triggered by the client's initial upload) — not orchestrated per-byte by Metadata Service
- Each storage node is a **dumb blob store** — no knowledge of files, other nodes, or replication logic. It only stores/serves chunk bytes by ID, and verifies presigned signatures before serving them.

The entire stack — Postgres, Eureka, Metadata Service, and all 4 Storage Nodes — runs via a single `docker-compose.yml`.

---

## 🧩 Microservices

### 1. Eureka Server
Service registry every storage node and Metadata Service registers with, enabling discovery-based routing instead of hardcoded hosts.

### 2. Metadata Service
Owns all file/chunk metadata and orchestrates the interesting logic:
- Consistent-hashing-based chunk placement
- Tracking replica locations and checksums
- Generating and verifying presigned URL signing keys
- Running the scheduled self-healing repair job

```text
POST   /files                              → initiate upload, get chunk placement + replica plan
POST   /files/{id}/complete                → mark upload complete
GET    /files/{id}                         → get chunk list with presigned URLs (all replicas, in order) for download
PATCH  /files/{id}/chunks/{chunkId}/checksum → record a chunk's checksum after upload
POST   /admin/repair                       → manually trigger a repair cycle (also runs on a schedule)
```

### 3. Storage Node Service
Owns raw chunk bytes on local disk. Multiple instances of the identical service, each an independent node on the hash ring.

```text
PUT  /chunks/{chunkId}            → store chunk bytes, compute + return SHA-256 checksum, replicate to peers
PUT  /chunks/{chunkId}/internal   → internal-only endpoint used for node-to-node replication and repair copies
GET  /chunks/{chunkId}            → retrieve chunk bytes; validates presigned signature + expiry when present
```

---

## 🗄️ Entity Structure

### File (`file_metadata`)

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

### Chunk (`chunk` + `chunk_replica_locations`)

| Column           | Type          | Constraints                                          |
|------------------|---------------|--------------------------------------------------------|
| id               | UUID          | Primary Key                                            |
| fileId           | UUID          | Not Null (plain foreign key, no JPA relation)          |
| chunkIndex       | Integer       | Not Null                                               |
| checksum         | String        | SHA-256 of chunk bytes, set after upload                |
| replicaLocations | List\<String> | Storage node IDs currently holding this chunk           |

> `fileId` is a plain foreign key, not a JPA relation — File and Chunk metadata live together in Metadata Service's own database, but chunk *bytes* live entirely outside it, on independent Storage Node services.

> Chunk placement is never hardcoded — it is computed at upload time by hashing `chunkId` onto a consistent-hashing ring built from currently active storage nodes, and re-verified/healed by the repair job as node membership changes.

---

## 🔄 Upload Flow

```text
Client
   |
   v
POST /files (Metadata Service)
   |
   v
Rebuild hash ring from currently active nodes (via Eureka)
Split file into chunks; for each, walk the ring to assign
N replica targets (default N=3)
   |
   v
Return chunk-to-node placement + full replica list to client
   |
   v
Client uploads each chunk directly to its primary storage node,
passing the other replica URLs as a query parameter
   |
   v
Primary node saves locally, computes SHA-256, replicates
the bytes to the other N-1 assigned nodes, returns checksum
   |
   v
Client reports the checksum back to Metadata Service
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
Returns chunk list, each with a presigned, time-limited URL
per replica (in order) and the expected checksum
   |
   v
Client fetches each chunk, trying replicas in order —
if the primary is unreachable, it automatically fails over
to the next healthy replica
   |
   v
Client verifies each chunk's SHA-256 against the expected
checksum, then reassembles the file in order
```

## 🛠️ Repair Flow (Self-Healing)

```text
Scheduled job (Metadata Service, every 60s — also triggerable via
POST /admin/repair)
   |
   v
Rebuild the hash ring from currently active nodes
For each chunk: compare its stored replicaLocations against
which of those nodes are still active
   |
   v
If some replicas are on dead nodes:
   - identify a surviving replica to copy from
   - compute the ideal replacement target(s) via the hash ring
   - copy chunk bytes from the survivor to the new target(s)
   - update replicaLocations to reflect the healed placement
   |
   v
If NO replica of a chunk survived: logged explicitly as
unrecoverable — the system never silently loses data without
saying so
```

---

## 🔑 Consistent Hashing (Placement Strategy)

- A hash ring is built from all currently active storage nodes, each mapped to 150 **virtual nodes** for even load distribution even with a small physical node count.
- `hash(chunkId)` is placed on the ring; the primary replica is the next node clockwise, and the next `N-1` distinct physical nodes clockwise become the additional replicas.
- When a node joins or leaves, only the chunks that fall within that node's range on the ring need to move — not the entire dataset.
- The ring (`ConsistentHashRing`) is backed by a `ConcurrentSkipListMap` rather than a plain `TreeMap` — **this was not a design choice made in advance, but a real bug found under load testing**: concurrent uploads mutating and reading a shared `TreeMap` simultaneously threw `ConcurrentModificationException`. Switching to a thread-safe sorted map (plus synchronizing `rebuild()`) fixed it, and this is now covered by regression tests.

---

## 🔗 Presigned URLs

- Metadata Service signs each chunk URL: `{nodeUrl}/chunks/{chunkId}?expires={timestamp}&sig={HMAC-SHA256(chunkId + expires, secretKey)}`
- Signing binds **both** the chunk ID and the expiry into one signature — this stops a valid signature from being paired with a longer expiry to extend access.
- The Storage Node independently verifies the signature and expiry before serving bytes — no callback to Metadata Service needed on every read.
- Internal node-to-node calls (replication, repair copies) skip signature verification by design, since they're trusted, non-client-facing traffic — a deliberate scope decision, not an oversight.
- **Verified, not assumed**: manually tampering one character of a signature returns `403 Forbidden`; a URL used after its expiry also returns `403`.

---

## 🧪 Load Testing (k6)

Real concurrent upload/download traffic was scripted and run against the full Docker Compose stack.

| Scenario | VUs | Throughput | Upload p95 | HTTP p95 | Errors | Checksum integrity |
|---|---|---|---|---|---|---|
| Baseline | 5 | 22.3 req/s | 2.31s | 478ms | ~0.1% | 100% |
| Higher concurrency | 20 | 31.4 req/s | **8.86s** | 1.85s | ~0.1% | 100% |
| Node killed mid-test | 10 | ~29 req/s | 4.05s | 836ms | concentrated on the dead node | 100% |

**Finding 1 — a real capacity bottleneck, not a bug**: throughput barely improved from 5→20 VUs while p95 upload latency got ~4x worse — a classic sign of resource contention rather than genuine scaling. The most likely cause is HikariCP's default connection pool size (10) under concurrent multi-row writes per upload (file + per-chunk + per-replica-location inserts). Checksum integrity and error rate were unaffected — this is a throughput ceiling, not a correctness issue.

**Finding 2 — failure detection has real, compounding latency**: killing a live storage node mid-test caused upload failures to persist far longer than expected — not because of a bug, but because of **two stacked layers of Eureka lag**: the server's own ~90s eviction timeout, plus the client-side registry cache's own ~30s refresh interval, together allowing up to ~120 seconds between a node dying and the system routing around it. This is a deliberate architectural trade-off in Eureka (an **AP system** per the CAP theorem — it favors availability during partitions over fast, consistent failure detection), not a flaw in this implementation. Despite this, **download failover to healthy replicas worked correctly whenever a chunk's primary was affected**, and checksum integrity remained 100% throughout every single test run.

---

## ✅ Automated Tests

16 JUnit + Mockito tests, all passing, requiring no Docker/database to run:

**`ConsistentHashRingTest`** (10 tests) — pure unit tests on the placement algorithm:
- Empty-ring and insufficient-node edge cases
- Replica count correctness and distinctness
- **Determinism** — the same key always maps to the same nodes on an unchanged ring
- Distribution across different keys (not collapsing onto one node)
- Correct behavior on rebuild when nodes join/leave
- `removeNode` genuinely excludes that node from all future lookups

**`RepairServiceTest`** (6 tests) — Mockito-based tests on the self-healing logic:
- No-op when no active nodes exist or a chunk is already correctly placed
- Correct, graceful handling when a chunk has **no surviving replicas** (logged, not crashed)
- The core repair path: copying bytes from a surviving replica to a new target and updating the chunk record
- Resilience to a single target failing mid-repair (doesn't abort the whole cycle)

```text
Tests run: 16, Failures: 0, Errors: 0
BUILD SUCCESS
```

---

## 🛠️ Technology Stack

- **Language:** Java 21
- **Framework:** Spring Boot 4.1, Spring Cloud 2025.1
- **Data:** Spring Data JPA / Hibernate, PostgreSQL
- **Service Discovery:** Netflix Eureka
- **Concurrency:** `ConcurrentSkipListMap` for the thread-safe hash ring
- **Integrity:** `MessageDigest` (SHA-256) for chunk checksums
- **Security:** `javax.crypto.Mac` (HMAC-SHA256) for presigned URL signing
- **Scheduling:** Spring `@Scheduled` for the repair job
- **Containerization:** Docker + Docker Compose — full stack, one command
- **Load Testing:** k6, with custom metrics (checksum mismatch rate, failover count, upload/download duration)
- **Testing:** JUnit 5, Mockito, AssertJ
- **Build tool:** Maven
- **Client tooling:** a Python client script (chunking, upload, download-with-failover, checksum verification) used for manual testing, demos, and as the load test's behavioral reference

---

## ▶️ Running the Project

### Prerequisites
```text
Docker Desktop
Java 21+ and Maven (only needed if rebuilding from source)
```

### Start the entire stack with one command
```bash
docker compose up -d --build
```

This starts Postgres, Eureka Server, Metadata Service, and all 4 Storage Nodes. Give it ~30-60 seconds for Eureka registration to settle, then check `http://localhost:8761` — you should see `METADATA-SERVICE` (1) and `STORAGE-NODE` (4), both `UP`.

### Upload and download a real file
```bash
python dfs_client.py upload "path/to/your/file.png"
python dfs_client.py download <fileId> "output/path.png"
```

### Run the automated test suite
```bash
cd metadata-service
./mvnw test
```

### Run a load test
```bash
k6 run --vus 10 --duration 30s load-test.js
```

---

## 📌 Known Limitations / Honest Next Steps

- **HikariCP pool sizing** hasn't been tuned — the 20-VU load test result above points directly at this as the next optimization to make and re-benchmark.
- **Failure detection latency** (~90-120s) is inherent to relying on Eureka's default settings alone; a production system would layer active health-checking or a shorter heartbeat interval on top.
- **Chunk data on storage nodes is not in a persistent Docker volume** — only Postgres is. A container recreation currently loses locally stored chunks; a real deployment would mount volumes per storage node.
- Multipart streaming for very large files, a configurable per-file replication factor, and centralized tracing/logging are still on the roadmap.

---

## 📖 Learning Objective

This project's goal was to move beyond request/response CRUD microservices and get genuinely hands-on with the mechanics of a distributed storage system — chunking, consistent-hash-based placement, real replication, integrity verification, and failure detection/repair — the concepts underlying real systems like S3, HDFS, and Cassandra. Nearly every non-trivial piece of this system was built, broken under real conditions (concurrency, killed containers, tampered requests), diagnosed, and fixed — which is, deliberately, the actual point.
