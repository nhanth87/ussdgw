#!/bin/bash
# ============================================================
# Setup 4G USSD Test Lab Environment
# ============================================================

set -e

echo "=== USSD Gateway 4G Test Lab Setup ==="
echo ""

# 1. System Requirements Check
echo "[CHECK] System requirements..."

CPU_CORES=$(nproc)
MEM_GB=$(free -g | awk '/^Mem:/{print $2}')

echo "  CPU Cores: $CPU_CORES"
echo "  Memory: ${MEM_GB}GB"

if [ "$CPU_CORES" -lt 8 ]; then
    echo "  [WARN] Recommend 8+ cores for 10k TPS"
fi

if [ "$MEM_GB" -lt 16 ]; then
    echo "  [WARN] Recommend 16+ GB RAM for 10k TPS"
fi

# 2. Install Dependencies
echo ""
echo "[INSTALL] Dependencies..."

# Java 8 (Zulu recommended)
if ! command -v java &> /dev/null; then
    echo "  Installing Zulu JDK 8..."
    # Ubuntu/Debian
    if command -v apt-get &> /dev/null; then
        apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0xB1998361219BD9C9
        apt-add-repository 'deb http://repos.azulsystems.com/ubuntu stable main'
        apt-get update
        apt-get install -y zulu-8
    fi
fi

# Maven
if ! command -v mvn &> /dev/null; then
    echo "  Installing Maven..."
    apt-get install -y maven || yum install -y maven
fi

# 3. Network Interface Tuning
echo ""
echo "[TUNING] Network interface..."

IFACE=$(ip route | grep default | awk '{print $5}' | head -1)
echo "  Interface: $IFACE"

# Increase txqueuelen
ip link set dev $IFACE txqueuelen 10000 2>/dev/null || true

# Enable Jumbo Frames (if supported)
ip link set dev $IFACE mtu 9000 2>/dev/null || echo "  Jumbo frames not supported"

# 4. Kernel Parameters
echo ""
echo "[TUNING] Kernel parameters..."

cat >> /etc/sysctl.d/99-ussd-performance.conf << 'EOF'
# Network buffers for high-throughput SS7/SCTP
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.rmem_default = 1048576
net.core.wmem_default = 1048576
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216
net.core.netdev_max_backlog = 65536
net.ipv4.tcp_max_syn_backlog = 65536
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_notsent_lowat = 16384

# File descriptors
fs.file-max = 2097152
fs.nr_open = 2097152

# VM settings
vm.swappiness = 1
vm.dirty_ratio = 40
vm.dirty_background_ratio = 10
EOF

sysctl -p /etc/sysctl.d/99-ussd-performance.conf

# 5. File Descriptors
echo ""
echo "[TUNING] File descriptors..."

cat >> /etc/security/limits.d/99-ussd.conf << 'EOF'
* soft nofile 200000
* hard nofile 200000
* soft nproc 65536
* hard nproc 65536
EOF

echo "  File descriptor limits set to 200000"

# 6. Disable Swap for Performance Test
echo ""
echo "[TUNING] Memory..."
swapoff -a 2>/dev/null || true
echo "  Swap disabled"

# 7. CPU Governor (if available)
if [ -f /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]; then
    echo ""
    echo "[TUNING] CPU governor..."
    for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        echo performance > $cpu 2>/dev/null || true
    done
    echo "  CPU governor set to performance"
fi

# 8. Build Project
echo ""
echo "[BUILD] Building USSD Gateway test modules..."

cd "$(dirname "$0")"

# Build parent first
cd ../../..
mvn clean install -DskipTests -pl :ussdgateway -am -q

# Build loadtest
cd test/loadtest
mvn clean package -DskipTests -q

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "  1. Edit configuration.conf for your environment"
echo "  2. Start USSD Gateway: cd release-wildfly && ./bin/standalone.sh"
echo "  3. Run test: ./run-test-lab.sh configuration.conf http"
echo ""
echo "For loopback test (no USSD GW needed):"
echo "  ./run-test-lab.sh configuration.conf loopback"
