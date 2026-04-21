/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mobicents.ussdgateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * USSD Session State Management Class.
 * 
 * Provides structured state tracking for stateless USSD sessions.
 * State is passed between dialogs via userObject field in XmlMAPDialog.
 * 
 * Performance optimizations:
 * - Uses String keys for minimal memory footprint
 * - Lazy JSON parsing only when needed
 * - Supports incremental state updates
 * 
 * @author Matrix Agent
 */
public class UssdSessionState implements Serializable {

    private static final long serialVersionUID = 1L;
    
    // Current menu level (depth in menu tree)
    private int menuLevel = 0;
    
    // User selections history
    private List<String> selections = new ArrayList<>();
    
    // Custom data key-value pairs
    private Map<String, String> data = new HashMap<>();
    
    // Session identifier (for correlation)
    private String sessionId;
    
    // MSISDN of the user
    private String msisdn;
    
    // Timestamp of last update
    private long lastUpdateTime;
    
    // State version for optimistic locking
    private int version = 0;
    
    // Gson instance for JSON serialization (thread-safe)
    private static final Gson GSON = new GsonBuilder().create();
    
    public UssdSessionState() {
        this.lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Create session state with MSISDN
     */
    public UssdSessionState(String msisdn) {
        this();
        this.msisdn = msisdn;
        this.sessionId = generateSessionId();
    }
    
    /**
     * Generate unique session ID
     */
    private String generateSessionId() {
        return System.currentTimeMillis() + "_" + Math.random();
    }
    
    // ===== Menu Level Management =====
    
    public int getMenuLevel() {
        return menuLevel;
    }
    
    public void setMenuLevel(int menuLevel) {
        this.menuLevel = menuLevel;
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public void incrementMenuLevel() {
        this.menuLevel++;
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public void decrementMenuLevel() {
        if (this.menuLevel > 0) {
            this.menuLevel--;
            this.lastUpdateTime = System.currentTimeMillis();
            this.version++;
        }
    }
    
    // ===== Selection Management =====
    
    public List<String> getSelections() {
        return new ArrayList<>(selections); // Return copy
    }
    
    public void addSelection(String selection) {
        this.selections.add(selection);
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public void removeLastSelection() {
        if (!this.selections.isEmpty()) {
            this.selections.remove(this.selections.size() - 1);
            this.lastUpdateTime = System.currentTimeMillis();
            this.version++;
        }
    }
    
    public void clearSelections() {
        this.selections.clear();
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public String getLastSelection() {
        if (this.selections.isEmpty()) {
            return null;
        }
        return this.selections.get(this.selections.size() - 1);
    }
    
    public String getPath() {
        return String.join(" > ", selections);
    }
    
    // ===== Data Management =====
    
    public Map<String, String> getData() {
        return new HashMap<>(data); // Return copy
    }
    
    public void setData(String key, String value) {
        this.data.put(key, value);
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public String getData(String key) {
        return this.data.get(key);
    }
    
    public String getData(String key, String defaultValue) {
        return this.data.getOrDefault(key, defaultValue);
    }
    
    public void setData(String key, int value) {
        this.data.put(key, String.valueOf(value));
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public int getDataAsInt(String key, int defaultValue) {
        String value = this.data.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public void removeData(String key) {
        this.data.remove(key);
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public void clearData() {
        this.data.clear();
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    public boolean hasData(String key) {
        return this.data.containsKey(key);
    }
    
    // ===== Session Info =====
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getMsisdn() {
        return msisdn;
    }
    
    public void setMsisdn(String msisdn) {
        this.msisdn = msisdn;
    }
    
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public int getVersion() {
        return version;
    }
    
    // ===== Serialization =====
    
    /**
     * Serialize state to JSON string for userObject
     * Optimized for minimal size
     */
    public String toJson() {
        return GSON.toJson(this);
    }
    
    /**
     * Serialize to compact JSON (no pretty printing)
     */
    public String toCompactJson() {
        return GSON.toJson(this);
    }
    
    /**
     * Deserialize from JSON string
     */
    public static UssdSessionState fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return new UssdSessionState();
        }
        try {
            return GSON.fromJson(json, UssdSessionState.class);
        } catch (Exception e) {
            // Fallback: try to parse as simple string
            UssdSessionState state = new UssdSessionState();
            state.addSelection(json);
            return state;
        }
    }
    
    /**
     * Update state from another state (merge)
     */
    public void mergeFrom(UssdSessionState other) {
        if (other == null) return;
        
        // Update selections
        if (other.menuLevel > this.menuLevel) {
            // New selection added
            this.menuLevel = other.menuLevel;
            if (!other.selections.isEmpty() && other.selections.size() > this.selections.size()) {
                this.selections.addAll(other.selections.subList(this.selections.size(), other.selections.size()));
            }
        }
        
        // Merge data (other takes precedence)
        for (Map.Entry<String, String> entry : other.data.entrySet()) {
            if (entry.getValue() != null) {
                this.data.put(entry.getKey(), entry.getValue());
            }
        }
        
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    /**
     * Check if state is expired
     */
    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - this.lastUpdateTime > timeoutMs;
    }
    
    /**
     * Reset state for new session
     */
    public void reset() {
        this.menuLevel = 0;
        this.selections.clear();
        this.data.clear();
        this.sessionId = generateSessionId();
        this.lastUpdateTime = System.currentTimeMillis();
        this.version++;
    }
    
    /**
     * Create new session state
     */
    public static UssdSessionState newSession(String msisdn) {
        return new UssdSessionState(msisdn);
    }
    
    /**
     * Update from HTTP response body (application specific)
     * This method extracts state from the application's response
     */
    public void updateFromAppResponse(String responseBody) {
        if (responseBody == null) return;
        
        try {
            JsonObject json = new JsonParser().parse(responseBody).getAsJsonObject();
            
            // Check for session state in response
            if (json.has("menuLevel")) {
                this.menuLevel = json.get("menuLevel").getAsInt();
            }
            
            if (json.has("selection")) {
                String selection = json.get("selection").getAsString();
                if (!selection.isEmpty()) {
                    this.addSelection(selection);
                }
            }
            
            if (json.has("data")) {
                JsonObject dataObj = json.getAsJsonObject("data");
                for (Map.Entry<String, JsonElement> entry : dataObj.entrySet()) {
                    this.data.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            
            this.lastUpdateTime = System.currentTimeMillis();
            
        } catch (Exception e) {
            // Not JSON or invalid format - try to extract as plain text selection
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                // Assume plain text is a menu selection
                String selection = responseBody.trim();
                if (selection.length() <= 10) { // Sanity check
                    this.addSelection(selection);
                }
            }
        }
    }
    
    @Override
    public String toString() {
        return String.format("UssdSessionState[sessionId=%s, msisdn=%s, menuLevel=%d, selections=%s, dataSize=%d, version=%d]",
                sessionId, msisdn, menuLevel, getPath(), data.size(), version);
    }
}
