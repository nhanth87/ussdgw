#!/bin/sh
# Host directory bootstrap — runs as root (compose init service or setup-server.sh via container).
set -eu

BASE_DIR="${USSDGW_BASE:-/opt/ussdgw}"
DATA_DIR="${BASE_DIR}/data"
LOG_DIR="${BASE_DIR}/log"
CONFIG_DIR="${BASE_DIR}/configuration"
CONF_FILE="${BASE_DIR}/standalone.conf"
PATCH_DIR="${BASE_DIR}/patched_jar/mirror"
SEED_DIR="${BASE_DIR}/config-seed"
BUNDLED_SEED="${BUNDLED_SEED:-/bundled-seed}"
BUNDLED_CONF="${BUNDLED_CONF:-/bundled/standalone.conf}"
CONTAINER_UID="${CONTAINER_UID:-2000}"
CONTAINER_GID="${CONTAINER_GID:-2000}"

echo "[host-init] Preparing ${BASE_DIR} (uid:gid ${CONTAINER_UID}:${CONTAINER_GID})..."

mkdir -p "${DATA_DIR}" "${LOG_DIR}" "${CONFIG_DIR}" "${PATCH_DIR}" "${SEED_DIR}"

if [ ! -f "${CONF_FILE}" ]; then
    if [ -f "${BUNDLED_CONF}" ]; then
        cp "${BUNDLED_CONF}" "${CONF_FILE}"
        echo "[host-init] Copied bundled standalone.conf"
    else
        touch "${CONF_FILE}"
        echo "[host-init] WARN: created empty standalone.conf"
    fi
fi

if [ -d "${BUNDLED_SEED}" ]; then
    copied=0
    skipped=0
    for xml_file in "${BUNDLED_SEED}"/*.xml; do
        [ -f "$xml_file" ] || continue
        filename=$(basename "$xml_file")
        if [ ! -f "${SEED_DIR}/${filename}" ]; then
            cp "$xml_file" "${SEED_DIR}/"
            echo "[host-init] + ${filename} -> config-seed/"
            copied=$((copied + 1))
        else
            skipped=$((skipped + 1))
        fi
    done
    echo "[host-init] config-seed: ${copied} copied, ${skipped} skipped"
else
    echo "[host-init] WARN: bundled seed not found at ${BUNDLED_SEED}"
fi

if [ -d "${BUNDLED_SEED}/configuration" ]; then
    cfg_copied=0
    cfg_skipped=0
    for prop_file in "${BUNDLED_SEED}/configuration"/*; do
        [ -f "$prop_file" ] || continue
        filename=$(basename "$prop_file")
        if [ ! -f "${CONFIG_DIR}/${filename}" ]; then
            cp "$prop_file" "${CONFIG_DIR}/"
            echo "[host-init] + ${filename} -> configuration/"
            cfg_copied=$((cfg_copied + 1))
        else
            cfg_skipped=$((cfg_skipped + 1))
        fi
    done
    echo "[host-init] configuration: ${cfg_copied} copied, ${cfg_skipped} skipped"
else
    echo "[host-init] WARN: bundled configuration/ not found under ${BUNDLED_SEED}"
fi

chown -R "${CONTAINER_UID}:${CONTAINER_GID}" "${BASE_DIR}"
chmod -R 755 "${BASE_DIR}"
chmod 644 "${CONF_FILE}"

echo "[host-init] Done."
