/*
 * Externalized Cassandra Configuration for USSD Gateway
 * Loads from system properties with sensible defaults.
 */
package com.ussdgateway.cassandra;

import java.time.Duration;

/**
 * Immutable configuration for Cassandra session manager.
 * Reads from JVM system properties; falls back to production-safe defaults.
 */
public final class CassandraConfig {

    // Contact & topology
    private final String contactPoints;
    private final int port;
    private final String localDatacenter;
    private final String keyspace;
    private final int replicationFactor;
    private final boolean durableWrites;

    // Pooling
    private final int maxConnectionsPerHost;
    private final int coreConnectionsPerHost;

    // Async executor
    private final int asyncPoolSize;
    private final int asyncQueueSize;

    // Timeouts
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration heartbeatInterval;
    private final Duration heartbeatTimeout;
    private final Duration controlConnectionTimeout;

    // Consistency & load-policy
    private final String consistency;
    private final boolean tokenAwareRouting;

    // Schema TTL / compaction
    private final int defaultTtlSeconds;
    private final int gcGraceSeconds;

    private CassandraConfig(Builder b) {
        this.contactPoints = b.contactPoints;
        this.port = b.port;
        this.localDatacenter = b.localDatacenter;
        this.keyspace = b.keyspace;
        this.replicationFactor = b.replicationFactor;
        this.durableWrites = b.durableWrites;
        this.maxConnectionsPerHost = b.maxConnectionsPerHost;
        this.coreConnectionsPerHost = b.coreConnectionsPerHost;
        this.asyncPoolSize = b.asyncPoolSize;
        this.asyncQueueSize = b.asyncQueueSize;
        this.requestTimeout = b.requestTimeout;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.heartbeatInterval = b.heartbeatInterval;
        this.heartbeatTimeout = b.heartbeatTimeout;
        this.controlConnectionTimeout = b.controlConnectionTimeout;
        this.consistency = b.consistency;
        this.tokenAwareRouting = b.tokenAwareRouting;
        this.defaultTtlSeconds = b.defaultTtlSeconds;
        this.gcGraceSeconds = b.gcGraceSeconds;
    }

    public static CassandraConfig fromSystemProperties() {
        Builder b = new Builder();
        b.contactPoints = sysProp("cassandra.contact.points", "127.0.0.1");
        b.port = intProp("cassandra.port", 9042);
        b.localDatacenter = sysProp("cassandra.local.datacenter", "datacenter1");
        b.keyspace = sysProp("cassandra.keyspace", "ussd_gateway");
        b.replicationFactor = intProp("cassandra.replication.factor", 3);
        b.durableWrites = boolProp("cassandra.durable.writes", true);

        b.maxConnectionsPerHost = intProp("cassandra.pool.max.connections", 64);
        b.coreConnectionsPerHost = intProp("cassandra.pool.core.connections", 16);

        b.asyncPoolSize = intProp("cassandra.async.pool.size", 32);
        b.asyncQueueSize = intProp("cassandra.async.queue.size", 10000);

        b.requestTimeout = durProp("cassandra.timeout.request.ms", 2000);
        b.connectTimeout = durProp("cassandra.timeout.connect.ms", 5000);
        b.readTimeout = durProp("cassandra.timeout.read.ms", 2000);
        b.heartbeatInterval = durProp("cassandra.heartbeat.interval.ms", 30000);
        b.heartbeatTimeout = durProp("cassandra.heartbeat.timeout.ms", 60000);
        b.controlConnectionTimeout = durProp("cassandra.control.connection.timeout.ms", 5000);

        b.consistency = sysProp("cassandra.consistency", "LOCAL_QUORUM");
        b.tokenAwareRouting = boolProp("cassandra.token.aware.routing", true);

        b.defaultTtlSeconds = intProp("cassandra.schema.default.ttl.seconds", 120);
        b.gcGraceSeconds = intProp("cassandra.schema.gc.grace.seconds", 864000);
        return new CassandraConfig(b);
    }

    // Property helpers
    private static String sysProp(String key, String def) {
        String v = System.getProperty(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    private static int intProp(String key, int def) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolProp(String key, boolean def) {
        String v = System.getProperty(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v);
    }

    private static Duration durProp(String key, long defMs) {
        return Duration.ofMillis(longProp(key, defMs));
    }

    private static long longProp(String key, long def) {
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // Getters
    public String getContactPoints() { return contactPoints; }
    public int getPort() { return port; }
    public String getLocalDatacenter() { return localDatacenter; }
    public String getKeyspace() { return keyspace; }
    public int getReplicationFactor() { return replicationFactor; }
    public boolean isDurableWrites() { return durableWrites; }
    public int getMaxConnectionsPerHost() { return maxConnectionsPerHost; }
    public int getCoreConnectionsPerHost() { return coreConnectionsPerHost; }
    public int getAsyncPoolSize() { return asyncPoolSize; }
    public int getAsyncQueueSize() { return asyncQueueSize; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public Duration getHeartbeatTimeout() { return heartbeatTimeout; }
    public Duration getControlConnectionTimeout() { return controlConnectionTimeout; }
    public String getConsistency() { return consistency; }
    public boolean isTokenAwareRouting() { return tokenAwareRouting; }
    public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public int getGcGraceSeconds() { return gcGraceSeconds; }

    @Override
    public String toString() {
        return "CassandraConfig{" +
                "contactPoints='" + contactPoints + '\'' +
                ", port=" + port +
                ", dc='" + localDatacenter + '\'' +
                ", keyspace='" + keyspace + '\'' +
                ", rf=" + replicationFactor +
                ", durableWrites=" + durableWrites +
                ", maxConns=" + maxConnectionsPerHost +
                ", coreConns=" + coreConnectionsPerHost +
                ", asyncPool=" + asyncPoolSize +
                ", consistency='" + consistency + '\'' +
                ", requestTimeout=" + requestTimeout +
                ", connectTimeout=" + connectTimeout +
                '}';
    }

    private static class Builder {
        String contactPoints;
        int port;
        String localDatacenter;
        String keyspace;
        int replicationFactor;
        boolean durableWrites;
        int maxConnectionsPerHost;
        int coreConnectionsPerHost;
        int asyncPoolSize;
        int asyncQueueSize;
        Duration requestTimeout;
        Duration connectTimeout;
        Duration readTimeout;
        Duration heartbeatInterval;
        Duration heartbeatTimeout;
        Duration controlConnectionTimeout;
        String consistency;
        boolean tokenAwareRouting;
        int defaultTtlSeconds;
        int gcGraceSeconds;
    }
}
