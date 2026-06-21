#!/bin/bash
# USSD Gateway host setup — run once before first docker compose up
set -euo pipefail

BASE_DIR="/opt/ussdgw"
DATA_DIR="${BASE_DIR}/data"
LOG_DIR="${BASE_DIR}/log"
CONF_FILE="${BASE_DIR}/standalone.conf"
PATCH_DIR="${BASE_DIR}/patched_jar/mirror"
SEED_DIR="${BASE_DIR}/config-seed"

CONTAINER_UID=2000
CONTAINER_GID=2000

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLED_SEED="${SCRIPT_DIR}/config-seed"
BUNDLED_CONF="${SCRIPT_DIR}/standalone.conf"

echo "Starting USSD Gateway host setup under ${BASE_DIR}..."
echo ""

echo "Creating directories..."
sudo mkdir -p "${DATA_DIR}" "${LOG_DIR}" "${PATCH_DIR}" "${SEED_DIR}"

echo "Setting ownership to ${CONTAINER_UID}:${CONTAINER_GID}..."
sudo chown -R ${CONTAINER_UID}:${CONTAINER_GID} "${BASE_DIR}"
sudo chmod -R 755 "${BASE_DIR}"

echo "Checking standalone.conf..."
if [ ! -f "${CONF_FILE}" ]; then
    if [ -f "${BUNDLED_CONF}" ]; then
        sudo cp "${BUNDLED_CONF}" "${CONF_FILE}"
        echo "  Copied bundled standalone.conf"
    else
        sudo touch "${CONF_FILE}"
        echo "  WARN: created empty standalone.conf"
    fi
fi
sudo chown ${CONTAINER_UID}:${CONTAINER_GID} "${CONF_FILE}"
sudo chmod 644 "${CONF_FILE}"

echo "Checking config-seed templates..."
if [ -d "${BUNDLED_SEED}" ]; then
    copied=0
    skipped=0
    for xml_file in "${BUNDLED_SEED}"/*.xml; do
        [ -f "$xml_file" ] || continue
        filename="$(basename "$xml_file")"
        if [ ! -f "${SEED_DIR}/${filename}" ]; then
            sudo cp "$xml_file" "${SEED_DIR}/"
            echo "  + ${filename} -> config-seed/"
            copied=$((copied + 1))
        else
            echo "  = ${filename} (exists, skipped)"
            skipped=$((skipped + 1))
        fi
    done
    echo "  seed: ${copied} copied, ${skipped} skipped"
else
    echo "  WARN: no bundled config-seed at ${BUNDLED_SEED}"
fi

echo "Checking SCTP kernel module..."
if lsmod | grep -q sctp; then
    echo "  SCTP module loaded"
else
    echo "  WARN: SCTP not loaded — run: sudo modprobe sctp"
fi

echo ""
echo "Setup complete."
echo "============================================================"
echo "  Data:       ${DATA_DIR}"
echo "  Logs:       ${LOG_DIR}"
echo "  JVM conf:   ${CONF_FILE}"
echo "  Patches:    ${PATCH_DIR}"
echo "  Seed:       ${SEED_DIR}"
echo "  Owner:      ${CONTAINER_UID}:${CONTAINER_GID}"
echo "============================================================"
echo ""
echo "Next steps:"
echo "  1. Edit SS7 XML in ${DATA_DIR}/ (or rely on first-start seed from config-seed/)"
echo "  2. Production (SCTP):  cd ${SCRIPT_DIR} && docker compose up -d"
echo "  3. Dev (HTTP only):    docker compose -f docker-compose.dev.yml up -d"
echo "  4. Logs:               docker logs -f ussdgw"
echo "  5. After config/JAR change: docker restart ussdgw"
echo "============================================================"
