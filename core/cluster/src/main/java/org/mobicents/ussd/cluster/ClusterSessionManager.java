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

package org.mobicents.ussd.cluster;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for distributed session management across cluster nodes.
 * This interface enables horizontal scaling for 1M+ TPS with multiple nodes.
 *
 * @author Matrix Agent
 */
public interface ClusterSessionManager {
    
    /**
     * Register this node with the cluster.
     * 
     * @param nodeAddress The address of this node
     * @return CompletableFuture that completes when registration is done
     */
    CompletableFuture<Void> registerNode(InetSocketAddress nodeAddress);
    
    /**
     * Unregister this node from the cluster.
     */
    void unregisterNode();
    
    /**
     * Get all active nodes in the cluster.
     * 
     * @return List of active cluster nodes
     */
    List<ClusterNode> getActiveNodes();
    
    /**
     * Get the current node's identifier.
     * 
     * @return This node's ID
     */
    String getLocalNodeId();
    
    /**
     * Check if this node is the coordinator.
     * 
     * @return true if this is the coordinator node
     */
    boolean isCoordinator();
    
    /**
     * Route a session operation to the appropriate node based on session key.
     * Uses consistent hashing to determine target node.
     * 
     * @param sessionKey The session identifier (e.g., MSISDN)
     * @param operation The operation to perform
     * @param <T> Return type
     * @return Result of the operation
     */
    <T> CompletableFuture<T> routeSessionOperation(String sessionKey, SessionOperation<T> operation);
    
    /**
     * Broadcast a message to all nodes in the cluster.
     * 
     * @param message The message to broadcast
     * @return CompletableFuture that completes when broadcast is done
     */
    CompletableFuture<Void> broadcast(Object message);
    
    /**
     * Register a session change listener that will be notified
     * when sessions are created, updated, or deleted on any node.
     * 
     * @param listener The listener to register
     */
    void addSessionChangeListener(SessionChangeListener listener);
    
    /**
     * Remove a session change listener.
     * 
     * @param listener The listener to remove
     */
    void removeSessionChangeListener(SessionChangeListener listener);
    
    /**
     * Get the session owner node for a given session key.
     * Returns Optional.empty() if no node owns the session.
     * 
     * @param sessionKey The session identifier
     * @return Optional containing the node if found
     */
    Optional<ClusterNode> getSessionOwner(String sessionKey);
    
    /**
     * Transfer session ownership to another node.
     * Used for load balancing and failover.
     * 
     * @param sessionKey The session to transfer
     * @param targetNodeId The target node
     * @return CompletableFuture that completes when transfer is done
     */
    CompletableFuture<Void> transferSession(String sessionKey, String targetNodeId);
    
    /**
     * Check if the cluster is healthy and all nodes are responsive.
     * 
     * @return true if cluster is healthy
     */
    boolean isClusterHealthy();
    
    /**
     * Functional interface for session operations.
     */
    @FunctionalInterface
    interface SessionOperation<T> {
        T execute(ClusterNode targetNode);
    }
    
    /**
     * Listener interface for session changes across the cluster.
     */
    interface SessionChangeListener {
        void onSessionCreated(String sessionKey, String nodeId);
        void onSessionUpdated(String sessionKey, String nodeId);
        void onSessionDeleted(String sessionKey, String nodeId);
    }
}