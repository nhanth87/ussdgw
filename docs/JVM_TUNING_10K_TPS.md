# JVM Tuning Guide: USSD Gateway 10k TPS — Test Lab (8GB RAM / 4 CPU)

> **Target**: 10,000 TPS sustained | **Latency**: P99 < 50ms | **GC Pause**: < 20ms

---

## 📊 Resource Allocation Strategy (8GB Total)

```
┌─────────────────────────────────────────────────────────────┐
│                    8 GB RAM Allocation                       │
├─────────────────────────────────────────────────────────────┤
│  Java Heap (G1 Eden + Survivor + Old)  │  5.0 GB  (62%)    │
│  Direct Memory (Netty off-heap)        │  1.5 GB  (19%)    │
│  Metaspace + Code Cache + Threads      │  0.5 GB  (6%)     │
│  OS + Native + Buffer Cache            │  1.0 GB  (13%)    │
└─────────────────────────────────────────────────────────────┘
```

| Region | Size | Purpose |
|--------|------|---------|
| **Heap** | 5 GB | SBB objects, MAP dialogs, XML payloads, routing tables |
| **Direct Memory** | 1.5 GB | Netty direct ByteBuf (SCTP/HTTP I/O), zero-copy buffers |
| **Metaspace** | 512 MB | Class metadata, SLEE container classes, RAs |
| **OS Reserve** | 1 GB | Kernel buffers, thread stacks, JVM native |

---

## 🚀 Optimal JAVA_OPTS (Java 8)

### Recommended Configuration

```bash
# ========== CORE MEMORY ==========
-Xms5g                          # Fixed heap = no resize pauses
-Xmx5g                          # Match Xms to prevent heap expansion GC
-XX:MaxDirectMemorySize=1536m   # Netty direct buffers (1.5 GB)
-XX:MetaspaceSize=256m          # Initial metaspace
-XX:MaxMetaspaceSize=512m       # Cap metaspace

# ========== GARBAGE COLLECTOR (G1GC — Low Latency) ==========
-XX:+UseG1GC                    # Best GC for < 100ms pauses on Java 8
-XX:MaxGCPauseMillis=20         # Target 20ms max pause (aggressive for 10k TPS)
-XX:G1HeapRegionSize=16m        # Large regions = fewer regions = less remembered set overhead
-XX:InitiatingHeapOccupancyPercent=30   # Start concurrent mark at 30% heap (early)
-XX:G1MixedGCCountTarget=4      # Spread mixed GC over 4 cycles (smoother)
-XX:G1ReservePercent=15         # Reserve 15% free to prevent evacuation failures
-XX:+UseStringDeduplication     # Deduplicate USSD strings (*100#, MSISDNs) — huge savings

# ========== GC THREADING (4 CPU) ==========
-XX:ParallelGCThreads=4         # Use all cores for parallel GC phases
-XX:ConcGCThreads=2             # 2 threads for concurrent marking (leave 2 for app)

# ========== JIT COMPILATION ==========
-XX:+AlwaysPreTouch             # Touch all heap pages at startup — zero pause later
-XX:+UseLargePages              # Use 2MB huge pages (if enabled in OS) — reduce TLB misses
-XX:+UseNUMA                    # NUMA-aware allocation (if NUMA hardware)
-XX:+AggressiveOpts             # Enable experimental optimizations
-XX:+UseFastAccessorMethods     # Faster getter/setter
-XX:+OptimizeStringConcat       # Optimize string concatenation (XML serialization)

# ========== GC LOGGING & DIAGNOSTICS ==========
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCTimeStamps
-XX:+PrintTenuringDistribution
-XX:+PrintGCCause
-XX:+PrintGCApplicationStoppedTime
-Xloggc:/var/log/ussd/gc.log
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=10
-XX:GCLogFileSize=100M
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/ussd/heapdump.hprof

# ========== SAFETY & STABILITY ==========
-XX:+DisableExplicitGC          # Block System.gc() calls from libraries
-XX:+UnlockExperimentalVMOptions
-XX:+UseCGroupMemoryLimitForHeap # Respect cgroup limits (for containers)

# ========== NETTY TUNING (SCTP/HTTP Transport) ==========
-Dio.netty.allocator.type=pooled              # Pooled allocator (default)
-Dio.netty.allocator.numDirectArenas=4        # Match CPU count
-Dio.netty.allocator.numHeapArenas=4
-Dio.netty.allocator.smallCacheSize=1024      # Larger thread-local cache
-Dio.netty.allocator.normalCacheSize=512
-Dio.netty.allocator.maxOrder=9               # 4MB chunks for large buffers
-Dio.netty.noPreferDirect=false               # Prefer direct buffers for I/O

# ========== WILDFLY / JBOSS ==========
-Djava.net.preferIPv4Stack=true
-Dorg.jboss.resolver.warning=true
-Dsun.rmi.dgc.client.gcInterval=3600000
-Dsun.rmi.dgc.server.gcInterval=3600000
-Djava.security.egd=file:/dev/./urandom   # Faster SecureRandom (Linux)

# ========== SS7 / TCAP TUNING ==========
-Dsctp.nodelay=true                # Disable Nagle for SCTP
-Dsctp.sndbuf=2097152              # 2MB send buffer
-Dsctp.rcvbuf=2097152              # 2MB receive buffer
```

### One-Liner (Copy-Paste)

```bash
JAVA_OPTS="-server \
  -Xms5g -Xmx5g \
  -XX:MaxDirectMemorySize=1536m \
  -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -XX:G1MixedGCCountTarget=4 \
  -XX:G1ReservePercent=15 \
  -XX:+UseStringDeduplication \
  -XX:ParallelGCThreads=4 \
  -XX:ConcGCThreads=2 \
  -XX:+AlwaysPreTouch \
  -XX:+UseLargePages \
  -XX:+UseNUMA \
  -XX:+AggressiveOpts \
  -XX:+UseFastAccessorMethods \
  -XX:+OptimizeStringConcat \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -XX:+PrintGCTimeStamps \
  -XX:+PrintTenuringDistribution \
  -XX:+PrintGCCause \
  -XX:+PrintGCApplicationStoppedTime \
  -Xloggc:/var/log/ussd/gc.log \
  -XX:+UseGCLogFileRotation \
  -XX:NumberOfGCLogFiles=10 \
  -XX:GCLogFileSize=100M \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/ussd/ \
  -XX:+DisableExplicitGC \
  -XX:+UnlockExperimentalVMOptions \
  -Dio.netty.allocator.numDirectArenas=4 \
  -Dio.netty.allocator.numHeapArenas=4 \
  -Dio.netty.allocator.smallCacheSize=1024 \
  -Dio.netty.allocator.normalCacheSize=512 \
  -Dio.netty.noPreferDirect=false \
  -Djava.net.preferIPv4Stack=true \
  -Dorg.jboss.resolver.warning=true \
  -Dsun.rmi.dgc.client.gcInterval=3600000 \
  -Dsun.rmi.dgc.server.gcInterval=3600000 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dsctp.nodelay=true \
  -Dsctp.sndbuf=2097152 \
  -Dsctp.rcvbuf=2097152"
```

---

## 🔥 WildFly 10 Startup Script

Create `/opt/wildfly/bin/standalone.conf` (or append to existing):

```bash
# ========== USSD Gateway 10k TPS JVM Config ==========
# Test Lab: 8GB RAM / 4 CPU / Java 8
# =====================================================

if [ "x$JAVA_OPTS" = "x" ]; then
   JAVA_OPTS="-Xms5g -Xmx5g \
      -XX:MaxDirectMemorySize=1536m \
      -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m \
      -XX:+UseG1GC -XX:MaxGCPauseMillis=20 \
      -XX:G1HeapRegionSize=16m \
      -XX:InitiatingHeapOccupancyPercent=30 \
      -XX:G1MixedGCCountTarget=4 \
      -XX:G1ReservePercent=15 \
      -XX:+UseStringDeduplication \
      -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 \
      -XX:+AlwaysPreTouch \
      -XX:+DisableExplicitGC \
      -XX:+HeapDumpOnOutOfMemoryError \
      -XX:HeapDumpPath=/var/log/ussd/ \
      -XX:+PrintGCDetails -XX:+PrintGCDateStamps \
      -Xloggc:/var/log/ussd/gc.log \
      -XX:+UseGCLogFileRotation \
      -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=100M \
      -Djava.net.preferIPv4Stack=true \
      -Djava.security.egd=file:/dev/./urandom \
      -Dio.netty.allocator.numDirectArenas=4 \
      -Dio.netty.allocator.numHeapArenas=4 \
      -Dsctp.nodelay=true \
      -Dsctp.sndbuf=2097152 \
      -Dsctp.rcvbuf=2097152"
fi
```

---

## ⚙️ Linux Kernel Tuning (Required)

```bash
# /etc/sysctl.conf
# ===== Network =====
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_keepalive_time = 300
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 3

# ===== Memory =====
vm.swappiness = 1              # Minimize swapping
vm.dirty_ratio = 40
vm.dirty_background_ratio = 10
vm.max_map_count = 262144      # For G1GC large heap

# ===== File Descriptors =====
fs.file-max = 2097152
fs.nr_open = 2097152

# ===== Huge Pages (optional but recommended) =====
vm.nr_hugepages = 2560         # 5GB / 2MB = 2560 huge pages
```

Apply:
```bash
sudo sysctl -p

# Huge pages (persistent across reboot)
echo 2560 | sudo tee /proc/sys/vm/nr_hugepages
```

### Ulimits

```bash
# /etc/security/limits.conf
* soft nofile 1048576
* hard nofile 1048576
* soft nproc 65536
* hard nproc 65536
* soft memlock unlimited
* hard memlock unlimited
```

---

## 📈 Monitoring & Verification

### GC Health Check

```bash
# Watch GC pauses in real-time
tail -f /var/log/ussd/gc.log | grep "Pause"

# Expected output (healthy):
# [GC pause (G1 Evacuation Pause) (young) 12ms]
# [GC pause (G1 Evacuation Pause) (mixed) 18ms]
# [GC pause (G1 Humongous Allocation) (young) 8ms]

# ALERT if you see:
# [Full GC ...]                    # → Heap too small or memory leak
# [GC pause > 100ms]               # → Tune G1ReservePercent, reduce target TPS
# [G1 Evacuation Failure]          # → Increase G1ReservePercent, reduce heap usage
```

### JMX Monitoring

```bash
# Add to JAVA_OPTS for remote JMX
-Dcom.sun.management.jmxremote.port=9999 \
-Dcom.sun.management.jmxremote.authenticate=false \
-Dcom.sun.management.jmxremote.ssl=false

# Connect with VisualVM or jconsole
jvisualvm --openjmx localhost:9999
```

### Key Metrics to Watch

| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| GC Pause (P99) | < 20ms | 20-50ms | > 50ms |
| GC Pause (Max) | < 50ms | 50-100ms | > 100ms |
| Heap Usage | < 70% | 70-85% | > 85% |
| Direct Memory | < 1GB | 1-1.3GB | > 1.4GB |
| Load Average | < 3.5 | 3.5-3.9 | >= 4.0 |
| Context Switches | < 50k/s | 50k-100k/s | > 100k/s |

---

## 🔬 Alternative: Shenandoah GC (Java 11+)

If upgrading to Java 11+ is possible, **Shenandoah GC** provides sub-millisecond pauses:

```bash
-XX:+UseShenandoahGC
-XX:ShenandoahGCHeuristics=compact
-XX:+ShenandoahPacing
-XX:ShenandoahGuaranteedGCInterval=10000
```

> ⚠️ **Note**: Java 8 does not include Shenandoah. Use Red Hat OpenJDK 8u222+ or switch to Java 11/17.

---

## 🎯 Load Test Validation

After applying tuning, validate with:

```bash
# 1. HTTP-Level load test
java -cp "loadtest.jar" org.mobicents.ussd.loadtest.UssdHttpLoadGenerator \
  http://localhost:8080/ussdhttpdemo/ 10000 32 50000 300 "*100#"

# 2. Watch GC simultaneously
tail -f /var/log/ussd/gc.log

# 3. Watch system metrics
vmstat 1
top -H -p $(pgrep -f standalone)
```

### Success Criteria

| Criteria | Target |
|----------|--------|
| Sustained TPS | >= 10,000 |
| P99 Latency | < 50ms |
| Max GC Pause | < 20ms |
| Heap Usage | < 70% at steady state |
| Zero Full GCs | During 5-min test |

---

*Generated: 2026-04-21 | For USSD Gateway 7.2.1 | Test Lab Environment*
