# USSD Gateway Cassandra Integration Guide
# For 20k TPS with 100k Concurrent Sessions

## Overview
This guide explains how to integrate Cassandra session persistence into existing SBBs.

## Architecture
```
┌─────────────────────────────────────────────────────────────────┐
│                    100k USSD Sessions                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    JAIN SLEE SBBs                               │
│  ChildSbb, ChildServerSbb                                       │
│  - Saves session on each MAP message                            │
│  - Loads session on dialog resume                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              UssdSessionHelper (Singleton)                       │
│  saveSession(sessionId, msisdn, state)                           │
│  loadSession(sessionId) → UssdSessionState                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              UssdSessionRepository (20k TPS)                    │
│  L1 Cache (50k, 1s TTL) → L2 Cache (10k, 3s TTL)               │
│  → Cassandra (120s TTL, batch writes)                           │
└─────────────────────────────────────────────────────────────────┘
```

## Integration Steps

### Step 1: Add Dependency
In `core/slee/sbbs/pom.xml`:
```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>xc7</artifactId>
    <version>${project.version}</version>
</dependency>
```

### Step 2: Initialize on Service Start
In your service deployer or SBB:
```java
import com.ussdgateway.cassandra.UssdSessionHelper;

public class MyServiceDeployer {
    public void deploy() {
        // Initialize Cassandra connection
        UssdSessionHelper.getInstance().init();
    }
    
    public void undeploy() {
        UssdSessionHelper.getInstance().shutdown();
    }
}
```

### Step 3: Modify ChildSbb to Persist Sessions

Add import:
```java
import com.ussdgateway.cassandra.UssdSessionHelper;
import org.mobicents.ussdgateway.UssdSessionState;
```

Add helper field:
```java
private static final UssdSessionHelper SESSION_HELPER = UssdSessionHelper.getInstance();
```

#### 3.1: Save Session on MAP Request
In `onProcessUnstructuredSSRequest`:
```java
public void onProcessUnstructuredSSRequest(ProcessUnstructuredSSRequest evt, ActivityContextInterface aci) {
    try {
        // ... existing code ...
        
        // Initialize session state if new
        String userObject = this.getUserObject();
        UssdSessionState state;
        
        if (userObject == null) {
            // New session
            String msisdn = evt.getMSISDNAddressString().getAddress();
            state = new UssdSessionState(msisdn);
            String sessionId = state.getSessionId();
            
            // Save to Cassandra immediately
            SESSION_HELPER.saveSession(sessionId, msisdn, state);
            
            // Keep in memory for this dialog
            this.setUserObject(state.toJson());
        } else {
            // Existing session - load from Cassandra
            UssdSessionState cachedState = SESSION_HELPER.loadSession(userObject);
            if (cachedState != null) {
                state = cachedState;
            } else {
                // Fallback: parse from userObject
                state = UssdSessionState.fromJson(userObject);
            }
        }
        
        // ... rest of existing code ...
    }
}
```

#### 3.2: Save Session on User Response
In `onUnstructuredSSResponse`:
```java
public void onUnstructuredSSResponse(UnstructuredSSResponse evt, ActivityContextInterface aci) {
    try {
        // ... existing code ...
        
        // Get session state
        String userObject = this.getUserObject();
        UssdSessionState state = UssdSessionState.fromJson(userObject);
        
        // Update state with user's selection
        String ussdString = evt.getUSSDString().getString(null);
        state.addSelection(ussdString);
        
        // Save to Cassandra
        SESSION_HELPER.saveSession(state.getSessionId(), state.getMsisdn(), state);
        
        // Update in-memory
        this.setUserObject(state.toJson());
        
        // ... rest of existing code ...
    }
}
```

#### 3.3: Save Session on Dialog Release
In `onDialogRelease`:
```java
public void onDialogRelease(DialogRelease evt, ActivityContextInterface aci) {
    try {
        // Get and delete session from Cassandra
        String userObject = this.getUserObject();
        if (userObject != null) {
            UssdSessionState state = UssdSessionState.fromJson(userObject);
            SESSION_HELPER.deleteSession(state.getSessionId());
        }
        
        // ... rest of existing code ...
    }
}
```

### Step 4: Handle Session Recovery
For failed SBBs recovering session state:
```java
// In onProcessUnstructuredSSRequest
public void onProcessUnstructuredSSRequest(ProcessUnstructuredSSRequest evt, ActivityContextInterface aci) {
    String sessionId = extractSessionId(evt); // From MSISDN or correlation ID
    
    // Try to recover from Cassandra
    UssdSessionState state = SESSION_HELPER.loadSession(sessionId);
    
    if (state != null) {
        // Session recovered
        logger.info("Recovered session: " + sessionId);
    } else {
        // New session
        state = new UssdSessionState(evt.getMSISDNAddressString().getAddress());
    }
    
    // Continue processing...
}
```

### Step 5: Monitor Performance
```java
// Get TPS stats
UssdSessionRepository.TPS20kStats stats = UssdSessionHelper.getInstance().getStats();

logger.info("L1 Cache: " + stats.l1HitRate + "% hit rate");
logger.info("L2 Cache: " + stats.l2HitRate + "% hit rate");
logger.info("Total reads: " + stats.totalReads);
logger.info("Total writes: " + stats.totalWrites);
logger.info("Pending writes: " + stats.pendingWrites);
logger.info("Circuit breaker: " + (stats.circuitOpen ? "OPEN" : "OK"));
```

## Performance Tuning

### For Higher TPS (20k+)
Edit `CassandraSessionManager.java`:
```java
// Increase connection pool
private static final int MAX_CONNECTIONS_PER_HOST = 500;

// Increase async threads
private static final int ASYNC_POOL_SIZE = 128;

// Decrease timeout for faster failure detection
private static final int REQUEST_TIMEOUT_MS = 200;
```

### For Higher Cache Hit Rate
Edit `UssdSessionRepository.java`:
```java
// Increase L1 cache size
private static final int L1_CACHE_MAX_SIZE = 100_000;
private static final long L1_CACHE_TTL_MS = 2_000; // 2 seconds
```

## Cassandra Schema
Run on Cassandra cluster:
```bash
cqlsh 10.60.10.41 -f cassandra_schema.cql
```

## Monitoring JMX
Expose stats via JMX:
```java
MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
mbs.registerMBean(new UssdSessionHelper(), new ObjectName("ussd:type=SessionRepository"));
```

## Troubleshooting

### Circuit Breaker Opens
If circuit breaker opens frequently:
1. Check Cassandracluster health
2. Increase `CIRCUIT_THRESHOLD`
3. Check network latency to Cassandra

### High Latency
If latency is high:
1. Check cache hit rate (should be > 95%)
2. Verify Cassandra nodes are healthy
3. Consider adding more Cassandra nodes

### OutOfMemory
If memory issues:
1. Reduce L1/L2 cache sizes
2. Reduce async queue size
3. Monitor pending writes

## Files Reference

| File | Purpose |
|------|---------|
| `CassandraSessionManager.java` | Driver singleton, connection pool |
| `UssdSessionRepository.java` | L1/L2 cache, batch writes |
| `UssdSessionHelper.java` | SBB-friendly wrapper |
| `cassandra_schema.cql` | Database schema |
