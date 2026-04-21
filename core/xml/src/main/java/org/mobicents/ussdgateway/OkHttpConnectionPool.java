/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mobicents.ussdgateway;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

/**
 * <p>
 * High-performance thread-safe singleton OkHttpClient with massive connection pooling.
 * </p>
 * 
 * <p>
 * Configuration for bulk data optimization:
 * - Connection pool: 100,000 max idle connections
 * - Keep-alive: 5 minutes
 * - Dispatcher: 100,000 max requests, 1000 concurrent per host
 * - Timeouts: Connect 5s, Read 30s, Write 30s
 * - HTTP/2 enabled for multiplexed connections
 * </p>
 * 
 * @author USSD Gateway Team
 */
public class OkHttpConnectionPool {

    // HIGH PERFORMANCE: 100k connections for bulk data processing
    private static final int MAX_IDLE_CONNECTIONS = 100000;
    private static final long KEEP_ALIVE_DURATION_MINUTES = 5;
    
    // Dispatcher configuration for high throughput
    private static final int MAX_REQUESTS = 100000;
    private static final int MAX_REQUESTS_PER_HOST = 1000;
    
    // Timeout configuration (extended for bulk data)
    private static final long CONNECT_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_SECONDS = 30;
    private static final long WRITE_TIMEOUT_SECONDS = 30;
    
    // Thread pool for async operations
    private static final int THREAD_POOL_SIZE = 200;
    
    // Thread-safe singleton instance
    private static volatile OkHttpConnectionPool instance = null;
    
    // Shared OkHttpClient instance
    private final OkHttpClient client;
    private final ExecutorService executorService;
    
    /**
     * Get singleton instance with double-check locking pattern.
     * 
     * @return singleton OkHttpConnectionPool instance
     */
    public static OkHttpConnectionPool getInstance() {
        if (instance == null) {
            synchronized (OkHttpConnectionPool.class) {
                if (instance == null) {
                    instance = new OkHttpConnectionPool();
                }
            }
        }
        return instance;
    }
    
    /**
     * Private constructor - initialize OkHttpClient with massive connection pooling.
     */
    private OkHttpConnectionPool() {
        // Custom thread pool for high throughput
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "OkHttp-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // High-performance dispatcher
        Dispatcher dispatcher = new Dispatcher(executorService);
        dispatcher.setMaxRequests(MAX_REQUESTS);
        dispatcher.setMaxRequestsPerHost(MAX_REQUESTS_PER_HOST);
        
        // Create massive connection pool
        ConnectionPool connectionPool = new ConnectionPool(
            MAX_IDLE_CONNECTIONS,
            KEEP_ALIVE_DURATION_MINUTES,
            TimeUnit.MINUTES
        );
        
        // Build OkHttpClient with optimized settings
        this.client = new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // Disable built-in retry (we use saga pattern)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    }
    
    /**
     * Get the shared OkHttpClient instance.
     * This client is thread-safe and can be used concurrently.
     * 
     * @return OkHttpClient instance with massive connection pooling
     */
    public OkHttpClient getClient() {
        return client;
    }
    
    /**
     * Get connection pool statistics for monitoring.
     * 
     * @return formatted statistics string
     */
    public String getPoolStats() {
        int connectionCount = client.connectionPool().connectionCount();
        int idleConnectionCount = client.connectionPool().idleConnectionCount();
        Dispatcher dispatcher = client.dispatcher();
        
        return String.format(
            "OkHttp Pool[100k] - Connections: %d/%d idle, Requests: queued=%d running=%d/%d, Timeouts: C=%ds/R=%ds/W=%ds",
            idleConnectionCount,
            MAX_IDLE_CONNECTIONS,
            dispatcher.queuedCallsCount(),
            dispatcher.runningCallsCount(),
            MAX_REQUESTS,
            CONNECT_TIMEOUT_SECONDS,
            READ_TIMEOUT_SECONDS,
            WRITE_TIMEOUT_SECONDS
        );
    }
    
    /**
     * Get detailed pool metrics for monitoring.
     * 
     * @return PoolMetrics object with detailed statistics
     */
    public PoolMetrics getMetrics() {
        Dispatcher dispatcher = client.dispatcher();
        return new PoolMetrics(
            client.connectionPool().connectionCount(),
            client.connectionPool().idleConnectionCount(),
            MAX_IDLE_CONNECTIONS,
            dispatcher.queuedCallsCount(),
            dispatcher.runningCallsCount(),
            MAX_REQUESTS,
            MAX_REQUESTS_PER_HOST
        );
    }
    
    /**
     * Shutdown the connection pool gracefully.
     * Call this during application shutdown to release resources.
     */
    public void shutdown() {
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    /**
     * Pool metrics data class for detailed monitoring.
     */
    public static class PoolMetrics {
        public final int totalConnections;
        public final int idleConnections;
        public final int maxConnections;
        public final long queuedRequests;
        public final long runningRequests;
        public final int maxRequests;
        public final int maxPerHost;
        
        public PoolMetrics(int totalConnections, int idleConnections, int maxConnections,
                          long queuedRequests, long runningRequests, int maxRequests, int maxPerHost) {
            this.totalConnections = totalConnections;
            this.idleConnections = idleConnections;
            this.maxConnections = maxConnections;
            this.queuedRequests = queuedRequests;
            this.runningRequests = runningRequests;
            this.maxRequests = maxRequests;
            this.maxPerHost = maxPerHost;
        }
        
        public double getUtilization() {
            return (double) totalConnections / maxConnections * 100;
        }
        
        public boolean isNearCapacity() {
            return totalConnections > (maxConnections * 0.9);
        }
        
        @Override
        public String toString() {
            return String.format(
                "PoolMetrics[total=%d, idle=%d/%d, queued=%d, running=%d/%d, perHost=%d]",
                totalConnections, idleConnections, maxConnections,
                queuedRequests, runningRequests, maxRequests, maxPerHost
            );
        }
    }
}