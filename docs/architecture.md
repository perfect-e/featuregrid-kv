# FeatureGrid KV Architecture

This document describes the current implementation. Planned work is listed in the repository README and is intentionally marked as future work.

## System Components

### Coordinator
- **Role:** Stateless routing layer. Does NOT store data.
- **Responsibilities:** Hash key → resolve owning node → forward gRPC → relay response.
- **Current behavior:** Consistent-hash routing with a static cluster configuration.
- **Port:** REST :8080, Prometheus metrics at `/actuator/prometheus`.

### Storage Node
- **Role:** Owns a slice of the key space. Stores data durably.
- **Current engine:** Full LSM-tree (`LsmStorageEngine`: WAL, memtable, flush, compaction, Bloom filter, LRU cache, and TTL reaper).
- **Ports:** gRPC :9090, HTTP actuator :8080.

### Raft
- **Role:** Leader election and log replication for the current three-node group.
- **States:** FOLLOWER → CANDIDATE → LEADER
- **Heartbeat:** 50ms leader → follower keepalive.
- **Election timeout:** 150–300ms randomized.

---

## Data Flow

### PUT user:1 "Shubh"
```
Client
  → PUT /api/v1/kv/user:1 (REST)
  → Coordinator hashes key → routes to the owning node
  → gRPC Put RPC to leader node
  → Node: append command to durable Raft log
  → Node: replicate to followers and wait for quorum ack
  → Node: apply committed command to WAL and memtable
  → Return PutResponse(success=true, version=N)
```

### GET user:1
```
Client
  → GET /api/v1/kv/user:1 (REST)
  → Coordinator hashes key → routes to owning node
  → gRPC Get RPC to node
  → Node read path: LRU cache → Memtable → Bloom filter → SSTable binary search
  → Return GetResponse(found=true, value=..., version=N)
```

---

## Storage Engine (LSM-Tree)

```
Write path:
  PUT key=k, value=v
    → Raft quorum commit
    → WAL append (crash durability)
    → Memtable insert (ConcurrentSkipListMap)
    → IF memtable size > threshold:
        → Freeze memtable
        → Flush to immutable SSTable file (sorted)
        → Truncate WAL segment

Read path:
  GET key=k
    → LRU cache hit? → return immediately
    → Memtable check
    → For each SSTable (newest first):
        → Bloom filter: "definitely not present"? → skip file
        → Index block: binary search for key offset
        → Data block: read and return value
```

---

## SSTable File Format

```
┌────────────────────────────────────────┐
│  DATA BLOCK                            │
│  [key_len(4)][key][val_len(4)][value]  │  sorted by key
│  ...                                   │
├────────────────────────────────────────┤
│  INDEX BLOCK (sparse)                  │
│  [key][offset_into_data_block(8)]      │  one entry per N data entries
│  ...                                   │
├────────────────────────────────────────┤
│  BLOOM FILTER                          │
│  [BitSet bytes] (1% false positive)    │
├────────────────────────────────────────┤
│  FOOTER                                │
│  index_offset(8) bloom_offset(8)       │
│  magic(8) = 0xKVSTORE1                 │
└────────────────────────────────────────┘
```

---

## Raft State Machine

```
                  timeout / no leader
FOLLOWER ──────────────────────────────► CANDIDATE
   ▲                                         │
   │ discovers higher term                   │ receives majority votes
   │ or leader                               ▼
   └────────────────────────────────────── LEADER
                                             │
                                             │ sends heartbeats every 50ms
                                             │ replicates log entries
                                             ▼
                                          FOLLOWER(s)
```

---

## Technology Stack

| Layer       | Technology             |
|------------|------------------------|
| Language   | Java 21                |
| Build      | Gradle 8.9 (multi-module) |
| API        | Spring Boot 3.4 + gRPC |
| Serialization | Protocol Buffers    |
| Storage    | Custom LSM engine      |
| Consensus  | Custom Raft with durable log recovery |
| Metrics    | Micrometer + Prometheus |
| Dashboards | Grafana                |
| Deployment | Docker Compose         |
| Testing    | JUnit 5 + Testcontainers |
