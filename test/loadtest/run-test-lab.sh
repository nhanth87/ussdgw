#!/bin/bash
# ============================================================
# USSD Gateway Test Lab Runner - 4G @ 10k TPS
# ============================================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Configuration
CONF_FILE="${1:-configuration.conf}"
TEST_MODE="${2:-http}"  # http | ss7 | loopback

echo -e "${GREEN}=== USSD Gateway Test Lab - 4G @ 10k TPS ===${NC}"
echo "Config: $CONF_FILE"
echo "Mode: $TEST_MODE"
echo ""

# Parse config (simple key=value parser)
parse_config() {
    local key="$1"
    grep -E "^${key}\s*=" "$CONF_FILE" | cut -d'=' -f2 | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

TARGET_TPS=$(parse_config "target_tps")
WORKER_THREADS=$(parse_config "worker_threads")
BASE_URL=$(parse_config "base_url")
DURATION=$(parse_config "duration_seconds")

# JVM Options for 10k TPS
JVM_OPTS="-XX:+UseG1GC \
    -Xms8g -Xmx8g \
    -XX:MaxGCPauseMillis=20 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UseStringDeduplication \
    -XX:+UseLargePages \
    -XX:+AlwaysPreTouch \
    -XX:+DisableExplicitGC \
    -XX:+ParallelRefProcEnabled \
    -XX:MaxTenuringThreshold=1 \
    -XX:SurvivorRatio=8"

# OS Tuning (Linux only)
tune_os() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo -e "${YELLOW}[TUNING] Applying Linux kernel optimizations...${NC}"
        
        # Network buffers
        sudo sysctl -w net.core.rmem_max=16777216
        sudo sysctl -w net.core.wmem_max=16777216
        sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
        sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"
        sudo sysctl -w net.core.netdev_max_backlog=65536
        sudo sysctl -w net.ipv4.tcp_max_syn_backlog=65536
        
        # File descriptors
        sudo ulimit -n 200000 2>/dev/null || echo "ulimit requires root"
        
        # Disable swap for performance test
        sudo swapoff -a 2>/dev/null || true
        
        echo -e "${GREEN}[TUNING] OS tuning applied${NC}"
    else
        echo -e "${YELLOW}[WARN] OS tuning only available on Linux${NC}"
    fi
}

# Build if needed
build_tools() {
    echo -e "${YELLOW}[BUILD] Checking build artifacts...${NC}"
    
    if [[ ! -f "target/loadtest-7.2.1-SNAPSHOT.jar" ]]; then
        echo -e "${YELLOW}[BUILD] Building loadtest module...${NC}"
        mvn clean package -DskipTests -q
    fi
    
    echo -e "${GREEN}[BUILD] Ready${NC}"
}

# Run HTTP Load Test
run_http_test() {
    echo -e "${GREEN}[TEST] Starting HTTP Load Test - Target: ${TARGET_TPS} TPS${NC}"
    echo "URL: $BASE_URL"
    echo "Threads: $WORKER_THREADS"
    echo "Duration: ${DURATION}s"
    echo ""
    
    java $JVM_OPTS \
        -cp "target/loadtest-7.2.1-SNAPSHOT.jar:target/dependency/*" \
        org.mobicents.ussd.loadtest.UssdHttpLoadGenerator \
        "$BASE_URL" \
        "$TARGET_TPS" \
        "$WORKER_THREADS" \
        "$(($TARGET_TPS * 5))"
}

# Run Loopback Test (no real network)
run_loopback_test() {
    echo -e "${GREEN}[TEST] Starting Loopback Load Test - Target: ${TARGET_TPS} TPS${NC}"
    echo "Mode: Local benchmark (no real SS7 transport)"
    echo ""
    
    # Start server in background
    java $JVM_OPTS \
        -cp "target/loadtest-7.2.1-SNAPSHOT.jar:target/dependency/*" \
        org.mobicents.ussd.loadtest.UssdLoadTestMain \
        --server --loopback --ssn 8 &
    SERVER_PID=$!
    
    sleep 2
    
    # Start client
    java $JVM_OPTS \
        -cp "target/loadtest-7.2.1-SNAPSHOT.jar:target/dependency/*" \
        org.mobicents.ussd.loadtest.UssdLoadTestMain \
        --client --loopback --tps "$TARGET_TPS" --threads "$WORKER_THREADS" --ssn 8
    
    # Cleanup
    kill $SERVER_PID 2>/dev/null || true
}

# Run SS7 Test (requires bootstrap stack)
run_ss7_test() {
    echo -e "${GREEN}[TEST] Starting Real SS7 Load Test${NC}"
    echo -e "${YELLOW}[WARN] Requires test/bootstrap with SS7 config${NC}"
    echo ""
    
    # Check if bootstrap is available
    if [[ ! -d "../bootstrap/target/ussd-server" ]]; then
        echo -e "${RED}[ERROR] Bootstrap not built. Building...${NC}"
        cd ../bootstrap
        mvn clean package -DskipTests -q
        cd ../loadtest
    fi
    
    echo -e "${YELLOW}[INFO] Start bootstrap first: cd ../bootstrap && ./run.sh${NC}"
    echo -e "${YELLOW}[INFO] Then run load generator manually${NC}"
}

# Main
case "$TEST_MODE" in
    http)
        tune_os
        build_tools
        run_http_test
        ;;
    loopback)
        build_tools
        run_loopback_test
        ;;
    ss7)
        run_ss7_test
        ;;
    *)
        echo "Usage: $0 [config_file] [http|loopback|ss7]"
        echo ""
        echo "Modes:"
        echo "  http     - HTTP interface load test (requires running USSD GW)"
        echo "  loopback - Local benchmark without network"
        echo "  ss7      - Real SS7/SIGTRAN test (requires bootstrap stack)"
        exit 1
        ;;
esac

echo -e "${GREEN}[DONE] Test completed${NC}"
