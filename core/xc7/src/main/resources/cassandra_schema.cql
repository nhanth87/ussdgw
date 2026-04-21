-- =====================================================
-- USSD Gateway Cassandra Schema
-- Optimized for 100k concurrent USSD sessions
-- 
-- NOTE: CDR is file-based, NOT stored in Cassandra
-- =====================================================

-- Create keyspace with Network Topology Strategy
CREATE KEYSPACE IF NOT EXISTS ussd_gateway
WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'datacenter1': 3
}
AND durable_writes = true;

-- =====================================================
-- USSD Sessions Table
-- TTL: 120 seconds (auto-expire inactive sessions)
-- =====================================================
CREATE TABLE IF NOT EXISTS ussd_gateway.ussd_sessions (
    session_id text PRIMARY KEY,
    msisdn text,
    state_json text,
    menu_level int,
    selections_json text,
    data_json text,
    created_at timestamp,
    updated_at timestamp
) WITH default_time_to_live = 120
  AND gc_grace_seconds = 30
  AND compaction = {
      'class': 'TimeWindowCompactionStrategy',
      'compaction_window_unit': 'MINUTES',
      'compaction_window_size': 5
  }
  AND caching = {
      'keys': 'ALL',
      'rows_per_partition': '100'
  };

-- Index for MSISDN lookups
CREATE INDEX IF NOT EXISTS idx_ussd_sessions_msisdn 
ON ussd_gateway.ussd_sessions(msisdn);

-- =====================================================
-- Performance Tuning Tips
-- =====================================================

-- 1. For write-heavy workload (USSD):
--    - Use LOCAL_ONE for reads (fast, tolerant)
--    - Use ANY for writes (fastest, eventually consistent)

-- 2. Connection pooling:
--    - Pool size: 100 connections max
--    - Core connections per host: 20

-- 3. Query timeout: 1000ms for USSD latency requirements

-- =====================================================
-- Sample Queries
-- =====================================================

-- Get active session by ID
-- SELECT * FROM ussd_sessions WHERE session_id = 'session123';

-- Get sessions by MSISDN
-- SELECT * FROM ussd_sessions WHERE msisdn = '251911123456';
