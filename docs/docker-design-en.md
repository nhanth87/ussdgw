# USSD Gateway — Docker Deployment Design

> **Document version:** 1.0 (implemented)  
> **USSD Gateway:** 7.2.1-SNAPSHOT  
> **WildFly:** 10.0.0.Final · **Java:** Eclipse Temurin 8  
> **Status:** Reference documentation for the Docker stack under `release-wildfly/`

---

## Table of contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [System requirements](#3-system-requirements)
4. [Host directory layout](#4-host-directory-layout)
5. [File inventory](#5-file-inventory)
6. [Quick start](#6-quick-start)
7. [Dockerfile](#7-dockerfile)
8. [Entrypoint — 5-phase startup](#8-entrypoint--5-phase-startup)
9. [Helper scripts](#9-helper-scripts)
10. [Docker Compose profiles](#10-docker-compose-profiles)
11. [JVM tuning — 3-layer merge](#11-jvm-tuning--3-layer-merge)
12. [Hot-patch JAR overlay](#12-hot-patch-jar-overlay)
13. [Persistence and config lifecycle](#13-persistence-and-config-lifecycle)
14. [SCTP / SS7 networking](#14-sctp--ss7-networking)
15. [Performance tuning](#15-performance-tuning)
16. [Save / load image (dev → production)](#16-save--load-image-dev--production)
17. [Troubleshooting](#17-troubleshooting)

Vietnamese version: [`dockerDesignForUssd.md`](../dockerDesignForUssd.md)

---

## 1. Overview

This document describes the **production-ready Docker deployment** for the USSD Gateway:

- Build a self-contained WildFly + JAIN SLEE + jSS7 image
- Persist SS7 config, logs, CDR, JVM flags, and hot-patched JARs on the host
- Auto-tune JVM heap/GC from container cgroup limits at startup
- Support SCTP/SIGTRAN via host networking
- Overlay patched JARs without rebuilding the image

### Goals

| Goal | Description |
|------|-------------|
| **Portable** | Build once on dev; deploy via `docker save/load` or a registry |
| **Persistent** | All runtime state under `/opt/ussdgw/` on the host |
| **Safe config** | Read-only seed templates; runtime writes go to `data/` only |
| **Auto JVM** | Heap and GC threads derived from container RAM/CPU limits |
| **Flexible override** | `USER_CONFIG_JVM` env var appends user flags at the end |
| **SS7-ready** | Host network + SCTP kernel module on the host |

---

## 2. Architecture

```
Host /opt/ussdgw/
  data/              ← bind mount → WildFly standalone/data (SS7 XML)
  log/               ← bind mount → WildFly standalone/log + CDR
  standalone.conf    ← JVM base flags (jSS7, SCTP, Netty)
  patched_jar/mirror/← JAR overlay mirror tree
  config-seed/       ← read-only templates (first-start seed)

Container entrypoint:
  init-host-dirs → apply-patched-jars → compute-jvm → merge JVM → standalone.sh
```

### JVM merge order

```
JAVA_OPTS = AUTO_JVM_OPTS + BASE (standalone.conf) + PRODUCTION_EXTRAS + USER_CONFIG_JVM
```

**Important:** Do **not** set `JAVA_OPTS` directly in Compose — it bypasses the merge and drops jSS7 flags. Use `USER_CONFIG_JVM` instead.

---

## 3. System requirements

### Host

| Component | Minimum | Recommended (high load) |
|-----------|---------|-------------------------|
| OS | Ubuntu 20.04/22.04, RHEL 8+ | Ubuntu 22.04 LTS |
| RAM | 4 GB | 8–32 GB |
| CPU | 2 cores | 4–32 cores |
| Docker | 24.x+ | Same version on dev and prod |
| SCTP | `modprobe sctp` on **host** | Persistent via `/etc/modules-load.d/` |

### Container

| Setting | Production | Dev |
|---------|------------|-----|
| Base image | `eclipse-temurin:8-jdk-jammy` | same |
| User | `ussdgw` UID/GID **2000:2000** | same |
| Network | `host` | bridge + port map |
| Privileged | `true` | `false` |
| Caps | `NET_ADMIN`, `NET_RAW` | `NET_ADMIN` |

---

## 4. Host directory layout

```
/opt/ussdgw/
├── data/                    # persistence — SS7/USSD XML + runtime state
├── log/                     # persistence — server.log, cdr-yyyy-MM-dd.log
├── standalone.conf          # persistence — JVM base layer
├── patched_jar/
│   └── mirror/              # JAR/files mirrored onto release tree
└── config-seed/             # read-only templates copied when data/ is empty
```

Ownership must be **2000:2000** (`setup-server.sh` handles this).

---

## 5. File inventory

| Path | Purpose |
|------|---------|
| `release-wildfly/Dockerfile` | Image definition |
| `release-wildfly/docker-entrypoint.sh` | 5-phase startup orchestrator |
| `release-wildfly/scripts/compute-jvm.sh` | Cgroup-based AUTO JVM |
| `release-wildfly/scripts/init-host-dirs.sh` | Seed + symlinks |
| `release-wildfly/scripts/apply-patched-jars.sh` | Mirror overlay |
| `release-wildfly/scripts/print-banner.sh` | Startup diagnostics |
| `release-wildfly/standalone.conf` | BASE JVM flags (no heap) |
| `release-wildfly/docker-compose.yml` | Production (host network) |
| `release-wildfly/docker-compose.dev.yml` | Dev/lab (HTTP, bridge) |
| `release-wildfly/setup-server.sh` | One-time host preparation |
| `release-wildfly/build-docker.sh` | Ant release + docker build |
| `release-wildfly/config-seed/*.xml` | Default SS7/USSD templates |

---

## 6. Quick start

```bash
# 1. Build image (requires Maven/Ant toolchain + release zip)
cd ussdgateway/release-wildfly
./build-docker.sh

# 2. Prepare host directories (once)
sudo ./setup-server.sh

# 3. Edit SS7 config for your environment
sudo vi /opt/ussdgw/data/SCTPManagement_sctp.xml

# 4a. Production with SCTP
sudo modprobe sctp
docker compose up -d

# 4b. Dev HTTP-only
docker compose -f docker-compose.dev.yml up -d

# 5. Monitor
docker logs -f ussdgw
curl -fs http://localhost:9990/health
```

---

## 7. Dockerfile

Key properties:

- **Base:** `eclipse-temurin:8-jdk-jammy`
- **Packages:** `lksctp-tools`, `gosu`, `curl`, `procps`, `iproute2`, `inetutils-ping`
- **User:** `ussdgw:2000`
- **Binary:** `restcomm-ussd-${USSD_VERSION}-linux.zip` → `/opt/restcomm/`
- **Env defaults:** `AUTO_JVM_ENABLED=true`, `USSDGW_PROFILE=lab`, `PATCH_FORCE=true`

Exposed ports: `8080 8443 9990 9993 8009 2905 2906 8011 8012`

Health check: `curl http://localhost:9990/health` (180s start period)

---

## 8. Entrypoint — 5-phase startup

| Phase | Script | Action |
|-------|--------|--------|
| 1 | `init-host-dirs.sh` | Create dirs; seed `data/` if empty; symlink data/log |
| 2 | `apply-patched-jars.sh` | Copy files from `patched_jar/mirror/` |
| 3 | `compute-jvm.sh` | Set `AUTO_JVM_OPTS` from cgroup |
| 4 | entrypoint | Merge JVM; apply production profile extras; print banner |
| 5 | entrypoint | `gosu ussdgw standalone.sh` |

---

## 9. Helper scripts

### `compute-jvm.sh` — auto heap table

| Container RAM | Heap | Direct memory | G1 pause target | GC threads |
|---------------|------|---------------|-----------------|------------|
| ≤ 4 GB | 2g | 512m | 200ms | 2 |
| 4–8 GB | 4g | 1024m | 100ms | 4 |
| 8–16 GB | 6g | 1536m | 50ms | 4 |
| 16–32 GB | 8g | 2048m | 20ms | 8 |
| > 32 GB | 12g | 3072m | 20ms | 16 |

Disable auto sizing: `AUTO_JVM_ENABLED=false`

### `apply-patched-jars.sh`

Mirror path under `patched_jar/mirror/` is relative to the WildFly tree:

```
/opt/ussdgw/patched_jar/mirror/wildfly-10.0.0.Final/standalone/deployments/my-du.jar
  → /opt/restcomm/restcomm-ussd-*/wildfly-10.0.0.Final/standalone/deployments/my-du.jar
```

Set `PATCH_BACKUP=true` to keep timestamped `.bak` copies before overwrite.

---

## 10. Docker Compose profiles

### Production — `docker-compose.yml`

- `network_mode: host` (required for SCTP)
- `privileged: true`, `NET_ADMIN`, `NET_RAW`
- `USSDGW_PROFILE=production` (Disruptor event router + SBB pool)
- Memory limit 8g (feeds AUTO JVM)

### Dev — `docker-compose.dev.yml`

- Bridge network, ports `8080`, `9990` published
- `USSDGW_PROFILE=lab`
- No SCTP requirement

---

## 11. JVM tuning — 3-layer merge

### Layer 1 — AUTO (`compute-jvm.sh`)

Heap, direct memory, G1GC, container support, heap dump path.

### Layer 2 — BASE (`standalone.conf`)

Fixed flags (always applied):

```
-Djss7.m3ua.byteBufEnabled=true
-Djss7.sccp.byteBufEnabled=true
-Djss7.asn.nettyEncodeEnabled=true
-Djss7.asn.flatIndexEnabled=true
-Dsctp.nodelay=true
-Dsctp.sndbuf=2097152
-Dsctp.rcvbuf=2097152
-Djava.net.preferIPv4Stack=true
```

### Layer 3 — USER (`USER_CONFIG_JVM`)

Append-only override in Compose, e.g.:

```yaml
environment:
  - USER_CONFIG_JVM=-agentlib:jdwp=transport=dt_socket,address=*:8787,server=y,suspend=n
```

### Production profile extras

When `USSDGW_PROFILE=production`:

```
-Djainslee.eventrouter.useDisruptor=true
-Djainslee.eventrouter.threads=32
-Djainslee.eventrouter.ringsize=65536
-Djainslee.sbb.pool.min=500
-Djainslee.sbb.pool.max=50000
-Dio.netty.leakDetectionLevel=disabled
```

See also: [`docs/JVM_TUNING_10K_TPS.md`](docs/JVM_TUNING_10K_TPS.md)

---

## 12. Hot-patch JAR overlay

Workflow:

```bash
# Build patched DU on dev
mvn package -DskipTests -pl core/slee/services-du

# Copy to mirror path on host
sudo cp target/ussd-services-du-*.jar \
  /opt/ussdgw/patched_jar/mirror/wildfly-10.0.0.Final/standalone/deployments/

# Restart — entrypoint overlays before WildFly starts
docker restart ussdgw
docker logs ussdgw 2>&1 | grep '\[patch\]'
```

No image rebuild required.

---

## 13. Persistence and config lifecycle

| Path | Writable by GW | Survives restart |
|------|----------------|------------------|
| `/opt/ussdgw/data/` | yes | yes (bind mount) |
| `/opt/ussdgw/log/` | yes | yes |
| `/opt/ussdgw/standalone.conf` | via host edit | yes |
| `/opt/ussdgw/patched_jar/` | host only | yes |

**Seed policy:** `config-seed/*` is copied into `data/` only when `data/` is empty (first install). Subsequent restarts preserve runtime state.

**Reset config:** `sudo rm -rf /opt/ussdgw/data/*` then restart container.

---

## 14. SCTP / SS7 networking

SCTP through Docker bridge/NAT is unreliable for SIGTRAN. **Production must use `network_mode: host`.**

Configure real host IP in `data/SCTPManagement_sctp.xml`:

```xml
<SctpAssociation localIp="192.168.1.100" localPort="8012" ... />
```

Verify after start:

```bash
cat /proc/net/sctp/eps | grep 8012
docker logs ussdgw 2>&1 | grep -i sctp
```

---

## 15. Performance tuning

**Host (one-time, root):** see `test/loadtest/tune-system.sh` — somaxconn, file-max, swappiness, huge pages.

**Compose:** `ulimits.nofile=1048576`, `stop_grace_period=120s`, resource limits drive AUTO JVM.

**JVM:** use `USSDGW_PROFILE=production` on high-core servers.

---

## 16. Save / load image (dev → production)

```bash
# Dev — export
docker save restcomm-ussd:7.2.1-SNAPSHOT | gzip > restcomm-ussd-7.2.1-SNAPSHOT.tar.gz
scp restcomm-ussd-7.2.1-SNAPSHOT.tar.gz prod:/tmp/

# Prod — import
gunzip -c /tmp/restcomm-ussd-7.2.1-SNAPSHOT.tar.gz | docker load
sudo ./setup-server.sh
docker compose up -d
```

Registry alternative:

```bash
docker tag restcomm-ussd:7.2.1-SNAPSHOT registry.example.com/ussdgw:7.2.1-SNAPSHOT
docker push registry.example.com/ussdgw:7.2.1-SNAPSHOT
```

---

## 17. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Permission denied on `/opt/ussdgw` | `sudo chown -R 2000:2000 /opt/ussdgw` |
| Health check timeout | Wait 3 min; check RAM limit ≥ 4g; read `/opt/ussdgw/log/server.log` |
| SCTP not listening | `sudo modprobe sctp`; use host network; fix XML local IP |
| Low throughput / missing jSS7 flags | Remove `JAVA_OPTS` from Compose; use `USER_CONFIG_JVM` |
| Patch not applied | Check mirror path; `docker logs ussdgw \| grep patch`; restart container |

---

*Implemented in `release-wildfly/` — USSD Gateway 7.2.1-SNAPSHOT*
