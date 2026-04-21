/*
 * USSD Session Helper for JAIN SLEE SBB Integration
 * Optimized for 20k TPS
 * 
 * Usage:
 *   UssdSessionHelper helper = UssdSessionHelper.getInstance();
 *   helper.saveSession(sessionId, msisdn, ussdSessionState);
 *   UssdSessionState state = helper.loadSession(sessionId);
 */
package com.ussdgateway.cassandra;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jboss.logging.Logger;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Helper class for SBBs to persist USSD session state.
 * Thread-safe for 100k concurrent SBBs.
 */
public class UssdSessionHelper {
    
    private static final Logger tracer = Logger.getLogger(UssdSessionHelper.class);
    
    private static volatile UssdSessionHelper instance;
    
    public static UssdSessionHelper getInstance() {
        if (instance == null) {
            synchronized (UssdSessionHelper.class) {
                if (instance == null) {
                    instance = new UssdSessionHelper();
                }
            }
        }
        return instance;
    }
    
    private final UssdSessionRepository repository;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    
    private UssdSessionHelper() {
        this.repository = UssdSessionRepository.getInstance();
        tracer.info("UssdSessionHelper initialized for 20k TPS");
    }
    
    /**
     * Save session state to Cassandra.
     * Ultra-fast: L1 cache update is synchronous, Cassandra write is async.
     */
    public void saveSession(String sessionId, String msisdn, 
                           org.mobicents.ussdgateway.UssdSessionState state) {
        if (sessionId == null || state == null) {
            return;
        }
        
        repository.saveSession(
            sessionId, msisdn,
            serializeState(state),
            state.getMenuLevel(),
            GSON.toJson(state.getSelections()),
            GSON.toJson(state.getData())
        );
    }
    
    /**
     * Load session state from Cassandra.
     * Uses L1/L2 cache for < 100μs latency.
     */
    public org.mobicents.ussdgateway.UssdSessionState loadSession(String sessionId) {
        UssdSessionRepository.SessionData data = repository.getSession(sessionId);
        
        if (data == null) {
            return null;
        }
        
        return deserializeState(data);
    }
    
    /**
     * Delete session.
     */
    public void deleteSession(String sessionId) {
        repository.deleteSession(sessionId);
    }
    
    // ===== Serialization =====
    
    private String serializeState(org.mobicents.ussdgateway.UssdSessionState state) {
        return GSON.toJson(new StateSnapshot(
            state.getMenuLevel(),
            state.getLastUpdateTime(),
            state.getVersion()
        ));
    }
    
    private org.mobicents.ussdgateway.UssdSessionState deserializeState(
            UssdSessionRepository.SessionData data) {
        
        org.mobicents.ussdgateway.UssdSessionState state = 
            new org.mobicents.ussdgateway.UssdSessionState(data.msisdn);
        
        state.setSessionId(data.sessionId);
        
        // Parse selections
        if (data.selectionsJson != null && !data.selectionsJson.isEmpty()) {
            try {
                List<String> selections = GSON.fromJson(data.selectionsJson, STRING_LIST_TYPE);
                if (selections != null) {
                    for (String sel : selections) {
                        state.addSelection(sel);
                    }
                }
            } catch (Exception e) {
                tracer.debug("Failed to parse selections: " + e.getMessage());
            }
        }
        
        // Parse data map
        if (data.dataJson != null && !data.dataJson.isEmpty()) {
            try {
                Map<String, String> dataMap = GSON.fromJson(data.dataJson, STRING_MAP_TYPE);
                if (dataMap != null) {
                    dataMap.forEach(state::setData);
                }
            } catch (Exception e) {
                tracer.debug("Failed to parse data: " + e.getMessage());
            }
        }
        
        state.setMenuLevel(data.menuLevel);
        return state;
    }
    
    // ===== State Snapshot =====
    
    static class StateSnapshot {
        int menuLevel;
        long lastUpdateTime;
        int version;
        
        StateSnapshot(int menuLevel, long lastUpdateTime, int version) {
            this.menuLevel = menuLevel;
            this.lastUpdateTime = lastUpdateTime;
            this.version = version;
        }
    }
    
    // ===== Statistics =====
    
    /**
     * Get performance statistics.
     */
    public UssdSessionRepository.TPS20kStats getStats() {
        return repository.getStats();
    }
    
    /**
     * Initialize Cassandra connection.
     */
    public void init() {
        CassandraSessionManager.getInstance().init();
        tracer.info("Cassandra connection initialized");
    }
    
    /**
     * Shutdown gracefully.
     */
    public void shutdown() {
        repository.shutdown();
        CassandraSessionManager.getInstance().shutdown();
        tracer.info("Shutdown complete");
    }
}
