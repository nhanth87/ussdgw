/*
 * TeleStax, Open Source Cloud Communications  Copyright 2024.
 * and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.ussd.cluster.cache;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Distributed cache interface for storing session data across cluster nodes.
 * Provides L1/L2 caching with optionalHazelcast/Infinispan backend.
 *
 * @author Matrix Agent
 */
public interface DistributedCache<K extends Serializable, V extends Serializable> {
    
    /**
     * Get a value from the cache.
     * 
     * @param key The cache key
     * @return Optional containing the value if found
     */
    Optional<V> get(K key);
    
    /**
     * Get a value asynchronously.
     * 
     * @param key The cache key
     * @return CompletableFuture with the value
     */
    CompletableFuture<Optional<V>> getAsync(K key);
    
    /**
     * Put a value in the cache.
     * 
     * @param key The cache key
     * @param value The value to store
     */
    void put(K key, V value);
    
    /**
     * Put a value with TTL.
     * 
     * @param key The cache key
     * @param value The value to store
     * @param ttl Time to live
     */
    void put(K key, V value, Duration ttl);
    
    /**
     * Put a value asynchronously.
     * 
     * @param key The cache key
     * @param value The value to store
     * @return CompletableFuture that completes when done
     */
    CompletableFuture<Void> putAsync(K key, V value);
    
    /**
     * Put a value with TTL asynchronously.
     * 
     * @param key The cache key
     * @param value The value to store
     * @param ttl Time to live
     * @return CompletableFuture that completes when done
     */
    CompletableFuture<Void> putAsync(K key, V value, Duration ttl);
    
    /**
     * Remove a value from the cache.
     * 
     * @param key The cache key
     * @return true if the key was present
     */
    boolean remove(K key);
    
    /**
     * Remove a value asynchronously.
     * 
     * @param key The cache key
     * @return CompletableFuture with true if key was present
     */
    CompletableFuture<Boolean> removeAsync(K key);
    
    /**
     * Check if a key exists in the cache.
     * 
     * @param key The cache key
     * @return true if the key exists
     */
    boolean containsKey(K key);
    
    /**
     * Clear all entries in the cache.
     */
    void clear();
    
    /**
     * Clear all entries asynchronously.
     * 
     * @return CompletableFuture that completes when done
     */
    CompletableFuture<Void> clearAsync();
    
    /**
     * Get the current size of the cache.
     * 
     * @return Number of entries
     */
    long size();
    
    /**
     * Get cache statistics.
     * 
     * @return CacheStats object with hit/miss/eviction counts
     */
    CacheStats getStats();
    
    /**
     * Cache statistics record.
     */
    class CacheStats implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final long hits;
        private final long misses;
        private final long evictions;
        private final long size;
        private final long totalPuts;
        
        public CacheStats(long hits, long misses, long evictions, long size, long totalPuts) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.size = size;
            this.totalPuts = totalPuts;
        }
        
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
        public long getSize() { return size; }
        public long getTotalPuts() { return totalPuts; }
        
        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, size=%d, totalPuts=%d}",
                    hits, misses, getHitRate() * 100, evictions, size, totalPuts);
        }
    }
}