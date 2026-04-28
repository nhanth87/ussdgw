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

import java.io.Serializable;
import java.net.InetSocketAddress;

/**
 * Represents a node in the cluster.
 * Used for distributed session management and load balancing.
 *
 * @author Matrix Agent
 */
public class ClusterNode implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private final String nodeId;
    private final InetSocketAddress address;
    private final long startedAt;
    private volatile boolean active;
    private volatile int currentLoad;
    
    public ClusterNode(String nodeId, InetSocketAddress address) {
        this.nodeId = nodeId;
        this.address = address;
        this.startedAt = System.currentTimeMillis();
        this.active = true;
        this.currentLoad = 0;
    }
    
    public String getNodeId() {
        return nodeId;
    }
    
    public InetSocketAddress getAddress() {
        return address;
    }
    
    public long getStartedAt() {
        return startedAt;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public int getCurrentLoad() {
        return currentLoad;
    }
    
    public void setCurrentLoad(int load) {
        this.currentLoad = load;
    }
    
    public void incrementLoad() {
        this.currentLoad++;
    }
    
    public void decrementLoad() {
        if (this.currentLoad > 0) {
            this.currentLoad--;
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterNode that = (ClusterNode) o;
        return nodeId != null ? nodeId.equals(that.nodeId) : that.nodeId == null;
    }
    
    @Override
    public int hashCode() {
        return nodeId != null ? nodeId.hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return "ClusterNode{" +
                "nodeId='" + nodeId + '\'' +
                ", address=" + address +
                ", active=" + active +
                ", load=" + currentLoad +
                '}';
    }
}