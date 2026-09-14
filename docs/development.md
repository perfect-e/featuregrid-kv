# Development Guide

This guide covers the normal local workflow for FeatureGrid KV.

## Prerequisites

- JDK 21 or newer
- Docker Desktop with Compose
- Node.js 18 or newer
- Git

Verify the main tools:

```bash
java -version
docker --version
node --version
```

On Windows, use `gradlew.bat`. On macOS and Linux, use `./gradlew`.

## Build the backend

From the repository root:

```bash
# Windows PowerShell
.\gradlew.bat build

# macOS or Linux
./gradlew build
```

Run the full test suite:

```bash
.\gradlew.bat test
```

Run only Raft tests:

```bash
.\gradlew.bat :raft:test
```

The Raft module includes persistence tests for restart recovery and incomplete log tails.

## Run the cluster

The simplest route is Docker Compose:

```bash
docker compose up --build
```

Stop it with:

```bash
docker compose down
```

Use `docker compose down -v` only when you intentionally want to remove node data and monitoring volumes.

Each node stores its runtime data under its mounted `DATA_DIR`. The important files include:

- `raft.log`: CRC-protected replicated command log
- `raft.state`: current term, vote, commit index, and application progress
- WAL and SSTable files: storage-engine durability and reads

## Run the UI

```bash
cd ui
npm ci
npm run dev
```

Useful UI checks:

```bash
npm run lint
npm run build
```

The development console normally runs at `http://localhost:5173` and connects to the coordinator at `http://localhost:8080`.

## Suggested development order

1. Make a focused change in one module.
2. Run that module's tests.
3. Run `git diff --check`.
4. Start the Docker cluster for cross-module behavior.
5. Check the REST API and dashboard.
6. Run the full backend and UI checks before opening a pull request.

## Safe fault simulations

Coordinator-level isolation blocks routing to a node but does not stop the process. To simulate a real node failure, use Docker:

```bash
docker stop kv-node-2
docker start kv-node-2
```

Do not delete volumes during a restart test. Persistent Raft and storage recovery are only meaningful when the node data remains intact.

## Pull requests

A useful pull request should include:

- a short explanation of the behavior change
- focused tests for the changed module
- notes about failure or recovery behavior when relevant
- screenshots for dashboard changes
- confirmation that `./gradlew test` and the UI checks pass
