#!/bin/bash
# Prepare host-mounted directories, seed config, wire data/log into WildFly.

init_host_dirs() {
    local base="${USSDGW_HOST_BASE:-/opt/ussdgw}"
    local seed="${base}/config-seed"
    local data="${base}/data"
    local log="${base}/log"
    local conf_mount="${base}/standalone.conf"
    local patch_mirror="${base}/patched_jar/mirror"

    mkdir -p "$data" "$log" "$patch_mirror"

    # Seed SS7/USSD XML when data/ is empty (first install)
    if [ -d "$seed" ] && [ -n "$(ls -A "$seed" 2>/dev/null)" ]; then
        if [ -z "$(find "$data" -maxdepth 1 -type f 2>/dev/null | head -1)" ]; then
            echo "[init] Seeding config from ${seed} -> ${data}"
            cp -f "$seed"/* "$data/" 2>/dev/null || true
        else
            echo "[init] data/ already populated — skipping seed"
        fi
    else
        echo "[init] WARN: no config-seed at ${seed}"
    fi

    # Host standalone.conf overrides image default when mounted
    if [ -f "$conf_mount" ]; then
        echo "[init] Using host standalone.conf from ${conf_mount}"
        cp -f "$conf_mount" "${JBOSS_HOME}/bin/standalone.conf"
    elif [ -f "${JBOSS_HOME}/bin/standalone.conf" ]; then
        echo "[init] Using bundled standalone.conf"
    fi

    # Replace standalone/data and standalone/log with symlinks to persistent mounts
    if [ -L "${JBOSS_HOME}/standalone/data" ] || [ -d "${JBOSS_HOME}/standalone/data" ]; then
        rm -rf "${JBOSS_HOME}/standalone/data"
    fi
    if [ -L "${JBOSS_HOME}/standalone/log" ] || [ -d "${JBOSS_HOME}/standalone/log" ]; then
        rm -rf "${JBOSS_HOME}/standalone/log"
    fi
    ln -sf "$data" "${JBOSS_HOME}/standalone/data"
    ln -sf "$log"  "${JBOSS_HOME}/standalone/log"

    echo "[init] ${JBOSS_HOME}/standalone/data -> ${data}"
    echo "[init] ${JBOSS_HOME}/standalone/log  -> ${log}"

    if [ "$(id -u)" = "0" ]; then
        chown -R ussdgw:ussdgw "$data" "$log" "$patch_mirror" 2>/dev/null || true
        chown ussdgw:ussdgw "${JBOSS_HOME}/bin/standalone.conf" 2>/dev/null || true
    fi
}

init_host_dirs
