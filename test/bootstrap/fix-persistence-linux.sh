#!/bin/bash
# Quick fix for Jackson XML persistence UnrecognizedPropertyException on Linux server
# Run this on the Linux server in the ussdgateway directory

set -e

SIM_DIR="${1:-/home/app/ussdgateway/simulator}"
echo "=== USSD Gateway Simulator Persistence Fix ==="
echo "Target dir: $SIM_DIR"

# 1. Stop any running simulator
echo "[1/4] Stopping simulator if running..."
pkill -f "ussdgateway-simulator" || true
sleep 2

# 2. Backup old data
echo "[2/4] Backing up old data to data-backup-$(date +%Y%m%d-%H%M%S)..."
BACKUP="data-backup-$(date +%Y%m%d-%H%M%S)"
if [ -d "$SIM_DIR/data" ]; then
    cp -r "$SIM_DIR/data" "$SIM_DIR/$BACKUP"
    echo "Backup created: $SIM_DIR/$BACKUP"
fi

# 3. Remove old persistence files that cause Jackson XML deserialization errors
echo "[3/4] Removing old persistence files..."
rm -f "$SIM_DIR/data/SCTPManagement.xml" 2>/dev/null || true
rm -f "$SIM_DIR/data/M3UAManagement.xml" 2>/dev/null || true
rm -f "$SIM_DIR/data/SccpManagement.xml" 2>/dev/null || true
rm -f "$SIM_DIR/data/TcapManagement.xml" 2>/dev/null || true
rm -f "$SIM_DIR/data/MapManagement.xml" 2>/dev/null || true
echo "Old persistence files removed"

# 4. Copy new SCTPManagement config (if exists in current dir)
echo "[4/4] Copying new SCTPManagement_simulator.xml..."
if [ -f "SCTPManagement_simulator.xml" ]; then
    mkdir -p "$SIM_DIR/data"
    cp "SCTPManagement_simulator.xml" "$SIM_DIR/data/SCTPManagement.xml"
    echo "New SCTPManagement.xml copied"
else
    echo "WARNING: SCTPManagement_simulator.xml not found in current directory"
    echo "Skipping copy - simulator will create default config on first start"
fi

echo ""
echo "=== Done! Now start the simulator with: ==="
echo "  cd $SIM_DIR"
echo "  ./run.sh simulator"
