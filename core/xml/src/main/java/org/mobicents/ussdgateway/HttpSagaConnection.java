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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.ussdgateway;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * <p>
 * HTTP Saga pattern implementation with resilience features:
 * - Exponential backoff retry (max 3 attempts)
 * - Circuit breaker pattern (open after 5 failures, half-open after 30s)
 * - Timeout handling with configurable thresholds
 * </p>
 * 
 * <p>
 * Circuit Breaker States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Too many failures, requests fail fast without executing
 * - HALF_OPEN: After timeout, allow one request to test if service recovered
 * </p>
 * 
 * <p>
 * Usage:
 * <pre>
 * HttpSagaConnection saga = new HttpSagaConnection();
 * Request request = new Request.Builder().url(url).post(body).build();
 * Response response = saga.executeWithRetry(request);
 * </pre>
 * </p>
 * 
 * @author USSD Gateway Team
 */
public class HttpSagaConnection {

    // Retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 100;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    
    // Circuit breaker configuration
    private static final int FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_TIMEOUT_MS = 30000; // 30 seconds
    
    // Circuit breaker state per URL
    private static final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakerMap = new ConcurrentHashMap<>();
    
    // OkHttp client from connection pool
    private final OkHttpClient client;
    
    /**
     * Constructor - use shared OkHttpClient from connection pool.
     */
    public HttpSagaConnection() {
        this.client = OkHttpConnectionPool.getInstance().getClient();
    }
    
    /**
     * Execute HTTP request with retry and circuit breaker pattern.
     * 
     * @param request the HTTP request to execute
     * @return HTTP response
     * @throws IOException if request fails after all retries
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    public Response executeWithRetry(Request request) throws IOException, CircuitBreakerOpenException {
        String url = request.url().toString();
        CircuitBreakerState cbState = getCircuitBreakerState(url);
        
        // Check circuit breaker before attempting request
        if (!cbState.allowRequest()) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN for URL: " + url);
        }
        
        IOException lastException = null;
        long backoffMs = INITIAL_BACKOFF_MS;
        
        // Retry loop with exponential backoff
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Response response = client.newCall(request).execute();
                
                // Success - record and return
                if (response.isSuccessful()) {
                    cbState.recordSuccess();
                    return response;
                }
                
                // Non-successful HTTP status code
                cbState.recordFailure();
                lastException = new IOException("HTTP " + response.code() + ": " + response.message());
                
                // Don't retry on 4xx client errors (except 408 Request Timeout)
                if (response.code() >= 400 && response.code() < 500 && response.code() != 408) {
                    response.close();
                    throw lastException;
                }
                
                response.close();
                
            } catch (IOException e) {
                cbState.recordFailure();
                lastException = e;
            }
            
            // Don't sleep after last attempt
            if (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", ie);
                }
                backoffMs = (long) (backoffMs * BACKOFF_MULTIPLIER);
            }
        }
        
        // All retries exhausted
        throw new IOException("Request failed after " + MAX_RETRY_ATTEMPTS + " attempts", lastException);
    }
    
    /**
     * Get or create circuit breaker state for a URL.
     * 
     * @param url the URL to get circuit breaker for
     * @return CircuitBreakerState instance
     */
    private CircuitBreakerState getCircuitBreakerState(String url) {
        return circuitBreakerMap.computeIfAbsent(url, k -> new CircuitBreakerState());
    }
    
    /**
     * Get circuit breaker statistics for monitoring.
     * 
     * @param url the URL to get statistics for
     * @return formatted statistics string
     */
    public String getCircuitBreakerStats(String url) {
        CircuitBreakerState state = circuitBreakerMap.get(url);
        if (state == null) {
            return "No circuit breaker state for URL: " + url;
        }
        return state.getStats();
    }
    
    /**
     * Reset circuit breaker for a URL (useful for testing or manual recovery).
     * 
     * @param url the URL to reset circuit breaker for
     */
    public void resetCircuitBreaker(String url) {
        circuitBreakerMap.remove(url);
    }
    
    /**
     * Circuit breaker state machine.
     */
    private static class CircuitBreakerState {
        private enum State {
            CLOSED,      // Normal operation
            OPEN,        // Circuit tripped, fail fast
            HALF_OPEN    // Testing if service recovered
        }
        
        private volatile State state = State.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        
        /**
         * Check if request is allowed based on circuit breaker state.
         * 
         * @return true if request should proceed, false otherwise
         */
        public synchronized boolean allowRequest() {
            switch (state) {
                case CLOSED:
                    return true;
                    
                case OPEN:
                    // Check if timeout has elapsed to transition to HALF_OPEN
                    if (System.currentTimeMillis() - lastFailureTime.get() >= CIRCUIT_OPEN_TIMEOUT_MS) {
                        state = State.HALF_OPEN;
                        return true;
                    }
                    return false;
                    
                case HALF_OPEN:
                    return true;
                    
                default:
                    return false;
            }
        }
        
        /**
         * Record successful request.
         */
        public synchronized void recordSuccess() {
            successCount.incrementAndGet();
            
            if (state == State.HALF_OPEN) {
                // Successful request in HALF_OPEN state - close circuit
                state = State.CLOSED;
                failureCount.set(0);
            }
        }
        
        /**
         * Record failed request.
         */
        public synchronized void recordFailure() {
            lastFailureTime.set(System.currentTimeMillis());
            int failures = failureCount.incrementAndGet();
            
            if (state == State.HALF_OPEN) {
                // Failed request in HALF_OPEN state - reopen circuit
                state = State.OPEN;
            } else if (state == State.CLOSED && failures >= FAILURE_THRESHOLD) {
                // Too many failures in CLOSED state - open circuit
                state = State.OPEN;
            }
        }
        
        /**
         * Get circuit breaker statistics.
         * 
         * @return formatted statistics string
         */
        public String getStats() {
            return String.format("State: %s, Failures: %d, Successes: %d, Last failure: %d ms ago",
                state,
                failureCount.get(),
                successCount.get(),
                System.currentTimeMillis() - lastFailureTime.get());
        }
    }
    
    /**
     * Exception thrown when circuit breaker is open.
     */
    public static class CircuitBreakerOpenException extends Exception {
        private static final long serialVersionUID = 1L;
        
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
