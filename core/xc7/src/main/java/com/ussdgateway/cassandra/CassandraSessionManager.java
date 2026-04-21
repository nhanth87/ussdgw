/*
 * High-Performance Cassandra Driver Manager for USSD Gateway
 * Optimized for production stability with externalized configuration.
 *
 * Key improvements:
 * - Externalized config via JVM system properties (see CassandraConfig)
 * - Production-safe defaults: LOCAL_QUORUM, durable_writes=true
 * - Token-aware routing for balanced distribution
 * - Prepared statement caching
 * - Bounded async executor with caller-runs fallback
 */
package com.ussdgateway.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.cql.*;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton Cassandra Session Manager.
 *
 * Configuration is loaded from JVM system properties (see CassandraConfig).
 * If no properties are set, sensible production defaults are used.
 */
public class CassandraSessionManager {

    private static final Logger tracer = Logger.getLogger(CassandraSessionManager.class);

    // ===== Singleton Instance =====
    private static volatile CassandraSessionManager instance;

    public static CassandraSessionManager getInstance() {
        if (instance == null) {
            synchronized (CassandraSessionManager.class) {
                if (instance == null) {
                    instance = new CassandraSessionManager();
                }
            }
        }
        return instance;
    }

    // ===== Config =====
    private final CassandraConfig config;

    // ===== Driver Components =====
    private CqlSession session;
    private PreparedStatementCache statementCache;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    // Async executor
    private final ExecutorService asyncExecutor;

    // Statistics
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    private CassandraSessionManager() {
        this.config = CassandraConfig.fromSystemProperties();
        this.asyncExecutor = new ThreadPoolExecutor(
            config.getAsyncPoolSize(),
            config.getAsyncPoolSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(config.getAsyncQueueSize()),
            r -> {
                Thread t = new Thread(r, "cassandra-async-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Initialize Cassandra connection.
     * Call once during service startup.
     */
    public synchronized void init() {
        if (initialized.get()) {
            return;
        }
        tracer.info("Initializing Cassandra with config: " + config);

        try {
            session = CqlSession.builder()
                .withLocalDatacenter(config.getLocalDatacenter())
                .addContactPoints(parseContactPoints())
                .withConfigLoader(buildConfigLoader())
                .build();

            statementCache = new PreparedStatementCache(session, config.getKeyspace());
            createSchema();

            connected.set(true);
            initialized.set(true);
            tracer.info("Cassandra initialized successfully. Keyspace: " + config.getKeyspace());
        } catch (Exception e) {
            tracer.error("Failed to initialize Cassandra", e);
            connected.set(false);
            throw new RuntimeException("Cassandra initialization failed", e);
        }
    }

    /**
     * Build driver config loader from externalized settings.
     */
    private DriverConfigLoader buildConfigLoader() {
        ProgrammaticDriverConfigLoaderBuilder builder = DriverConfigLoader.programmaticBuilder()
            .withString(DefaultDriverOption.PROTOCOL_VERSION, "V4")

            // Pooling
            .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, config.getMaxConnectionsPerHost())
            .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, config.getCoreConnectionsPerHost())

            // Timeouts
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, config.getConnectTimeout())
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, config.getRequestTimeout())

            // Heartbeat
            .withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, config.getHeartbeatInterval())
            .withDuration(DefaultDriverOption.HEARTBEAT_TIMEOUT, config.getHeartbeatTimeout())

            // Control connection
            .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, config.getControlConnectionTimeout())

            // Consistency
            .withString(DefaultDriverOption.REQUEST_CONSISTENCY, config.getConsistency())

            // Reconnection: exponential backoff with sane defaults
            .withDuration(DefaultDriverOption.RECONNECTION_BASE_DELAY, Duration.ofMillis(100))
            .withDuration(DefaultDriverOption.RECONNECTION_MAX_DELAY, Duration.ofSeconds(10))

            // Metrics & node state listener
            .withBoolean(DefaultDriverOption.METRICS_NODE_ENABLED, true)
            .withBoolean(DefaultDriverOption.METRICS_SESSION_ENABLED, true);

        return builder.build();
    }

    private List<InetSocketAddress> parseContactPoints() {
        String[] hosts = config.getContactPoints().split(",");
        return java.util.Arrays.stream(hosts)
            .map(String::trim)
            .filter(h -> !h.isEmpty())
            .map(h -> new InetSocketAddress(h, config.getPort()))
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Create keyspace and tables with production-safe settings.
     */
    private void createSchema() {
        String ks = config.getKeyspace();
        int rf = config.getReplicationFactor();

        // Keyspace: allow durable_writes to be toggled via config (default true for safety)
        String createKs = String.format(
            "CREATE KEYSPACE IF NOT EXISTS %s " +
            "WITH replication = {'class': 'NetworkTopologyStrategy', '%s': %d} " +
            "AND durable_writes = %s",
            ks, config.getLocalDatacenter(), rf, config.isDurableWrites()
        );
        session.execute(SimpleStatement.builder(createKs).build());

        // Sessions table optimized for USSD short-lived sessions
        String createTable = String.format(
            "CREATE TABLE IF NOT EXISTS %s.ussd_sessions (" +
            "  session_id text PRIMARY KEY," +
            "  msisdn text," +
            "  state_json text," +
            "  menu_level int," +
            "  selections_json text," +
            "  data_json text," +
            "  created_at timestamp," +
            "  updated_at timestamp" +
            ") WITH default_time_to_live = %d" +
            "  AND gc_grace_seconds = %d" +
            "  AND compaction = {'class': 'TimeWindowCompactionStrategy', " +
            "                     'compaction_window_unit': 'MINUTES', " +
            "                     'compaction_window_size': 1}" +
            "  AND caching = {'keys': 'ALL', 'rows_per_partition': '10'}",
            ks, config.getDefaultTtlSeconds(), config.getGcGraceSeconds()
        );
        session.execute(SimpleStatement.builder(createTable).build());

        // Secondary index on MSISDN (use with caution on high-cardinality columns)
        String createIdx = String.format(
            "CREATE INDEX IF NOT EXISTS idx_ussd_sessions_msisdn ON %s.ussd_sessions(msisdn)",
            ks
        );
        session.execute(SimpleStatement.builder(createIdx).build());

        tracer.info("Schema created/verified for keyspace: " + ks);
    }

    // ===== Public API =====

    public CqlSession getSession() {
        return session;
    }

    public boolean isConnected() {
        return connected.get() && session != null;
    }

    public PreparedStatement getPreparedStatement(String query) {
        return statementCache.get(query);
    }

    /**
     * Build a bound SAVE statement.
     */
    public BoundStatement buildSaveStatement(String sessionId, String msisdn,
            String stateJson, int menuLevel, String selectionsJson,
            String dataJson, long timestamp) {
        PreparedStatement ps = statementCache.get(PreparedStatementCache.SAVE_SESSION);
        return ps.bind(sessionId, msisdn, stateJson, menuLevel,
                      selectionsJson, dataJson,
                      java.time.Instant.ofEpochMilli(timestamp),
                      java.time.Instant.ofEpochMilli(timestamp));
    }

    /**
     * Execute async with statistics tracking.
     */
    public <T> CompletableFuture<T> executeAsync(Callable<T> callable) {
        totalQueries.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, asyncExecutor);
    }

    /**
     * Fire-and-forget async execute (for writes).
     */
    public void executeAsyncVoid(Runnable runnable) {
        totalQueries.incrementAndGet();
        asyncExecutor.execute(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                tracer.warn("Async execute failed", e);
            }
        });
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public CassandraConfig getConfig() {
        return config;
    }

    // ===== Statistics =====

    public TPSStats getStats() {
        return new TPSStats(
            totalQueries.get(),
            totalErrors.get(),
            asyncExecutor instanceof ThreadPoolExecutor ?
                ((ThreadPoolExecutor) asyncExecutor).getActiveCount() : 0,
            config.getMaxConnectionsPerHost()
        );
    }

    public void shutdown() {
        if (session != null) {
            tracer.info("Shutting down Cassandra...");
            session.close();
            session = null;
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        connected.set(false);
        initialized.set(false);
        tracer.info("Shutdown complete. Stats: " + getStats());
    }

    // ===== Prepared Statement Cache =====

    static class PreparedStatementCache {
        private final ConcurrentHashMap<String, PreparedStatement> cache = new ConcurrentHashMap<>();
        private final CqlSession session;

        static String SAVE_SESSION;
        static String GET_SESSION;
        static String DELETE_SESSION;
        static String GET_BY_MSISDN;

        PreparedStatementCache(CqlSession session, String keyspace) {
            this.session = session;
            SAVE_SESSION =
                "INSERT INTO " + keyspace + ".ussd_sessions " +
                "(session_id, msisdn, state_json, menu_level, selections_json, data_json, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            GET_SESSION =
                "SELECT * FROM " + keyspace + ".ussd_sessions WHERE session_id = ?";
            DELETE_SESSION =
                "DELETE FROM " + keyspace + ".ussd_sessions WHERE session_id = ?";
            GET_BY_MSISDN =
                "SELECT * FROM " + keyspace + ".ussd_sessions WHERE msisdn = ?";

            cache.put(SAVE_SESSION, session.prepare(SAVE_SESSION));
            cache.put(GET_SESSION, session.prepare(GET_SESSION));
            cache.put(DELETE_SESSION, session.prepare(DELETE_SESSION));
            cache.put(GET_BY_MSISDN, session.prepare(GET_BY_MSISDN));
        }

        PreparedStatement get(String query) {
            return cache.computeIfAbsent(query, session::prepare);
        }
    }

    // ===== Stats Class =====

    public static class TPSStats {
        public final long totalQueries;
        public final long totalErrors;
        public final int activeThreads;
        public final int maxConnections;

        public TPSStats(long totalQueries, long totalErrors,
                       int activeThreads, int maxConnections) {
            this.totalQueries = totalQueries;
            this.totalErrors = totalErrors;
            this.activeThreads = activeThreads;
            this.maxConnections = maxConnections;
        }

        public double getErrorRate() {
            return totalQueries > 0 ?
                (double) totalErrors / totalQueries * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "TPSStats[q=%d, err=%d (%.2f%%), threads=%d/%d]",
                totalQueries, totalErrors, getErrorRate(),
                activeThreads, maxConnections
            );
        }
    }
}
