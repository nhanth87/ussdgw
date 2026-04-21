# USSD Load Test Tool - Target 10k TPS

## Overview

High-performance USSD load testing module for the USSD Gateway, designed to achieve **10,000 TPS (Transactions Per Second)**.

## Architecture

### Components

1. **UssdLoadGenerator** (`UssdLoadGenerator.java`)
   - Multi-threaded USSD client load generator
   - Configurable target TPS and worker threads
   - Rate-limited using busy-sleep for sub-millisecond precision
   - End-to-end latency measurement
   - Concurrent dialog control (max 50,000 default)

2. **UssdLoadServer** (`UssdLoadServer.java`)
   - High-throughput USSD auto-responder
   - Optimized for minimal object allocation
   - No heavy logging in hot path
   - Immediate ProcessUnstructuredSSResponse

3. **LoadTestMetrics** (`LoadTestMetrics.java`)
   - Lock-free metrics using `LongAdder`
   - Real-time TPS reporting
   - Latency histogram support
   - Error rate tracking

4. **UssdLoadTestMain** (`UssdLoadTestMain.java`)
   - Standalone entry point
   - Supports server/client modes
   - Loopback mode for local benchmark

## Usage

### Build

```bash
cd ussdgateway/test/loadtest
mvn clean package
```

### Server Mode (Auto-Responder)

```bash
java -cp target/loadtest-7.2.1-SNAPSHOT.jar org.mobicents.ussd.loadtest.UssdLoadTestMain \
  --server --loopback --ssn 8
```

### Client Mode (Load Generator)

```bash
java -cp target/loadtest-7.2.1-SNAPSHOT.jar org.mobicents.ussd.loadtest.UssdLoadTestMain \
  --client --loopback --tps 10000 --threads 20 --ssn 8
```

### Real SS7 Mode

For production testing with real SCTP/M3UA/SCCP stacks:

1. Start `ussdgateway/test/bootstrap` with proper SS7 configuration
2. Integrate `UssdLoadGenerator` or `UssdLoadServer` via JMX or direct injection

## Performance Tuning for 10k TPS

### JVM Options
```bash
-XX:+UseG1GC
-Xms4g -Xmx4g
-XX:MaxGCPauseMillis=20
-XX:+UnlockExperimentalVMOptions
-XX:+UseStringDeduplication
```

### SCTP Tuning
- Increase send/receive buffer sizes: `SO_SNDBUF` / `SO_RCVBUF` to 4MB+
- Use multiple SCTP streams
- Enable `SCTP_NODELAY`

### TCAP/MAP Stack Tuning
- `setMaxDialogs(1000000)` - increase max dialogs
- Increase executor thread pool sizes
- Use JCTools queues (already migrated in SCTP)

### OS Tuning (Linux)
```bash
# Increase network buffers
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216
sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"

# Increase file descriptors
ulimit -n 100000
```

## Metrics Output

```
[LoadTest] TPS(req=9950|resp=9920) | Total(req=500000|resp=498000|err=50) | AvgLatency=2.345ms | Errors/sec=0
```

## Module Structure

```
loadtest/
├── pom.xml
├── README.md
└── src/main/java/org/mobicents/ussd/loadtest/
    ├── LoadTestMetrics.java
    ├── UssdLoadGenerator.java
    ├── UssdLoadServer.java
    └── UssdLoadTestMain.java
```

## Dependencies

- `map-api`, `map-impl` - MAP protocol stack
- `sccp-api`, `sccp-impl` - SCCP routing
- `tcap-api`, `tcap-impl` - TCAP dialog management
- `sctp-api`, `sctp-impl` - SCTP transport

## Notes

- Loopback mode does NOT send real SS7 messages. Use `test/bootstrap` for end-to-end testing.
- The existing `jSS7/map/load/ussd/` module provides alternative load test tools.
- For maximum throughput, run client and server on separate machines with dedicated network.
