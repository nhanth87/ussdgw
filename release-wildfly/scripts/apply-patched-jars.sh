#!/bin/bash
# Overlay JAR/files from /opt/ussdgw/patched_jar/mirror/ onto the release tree.
# Mirror path is relative to wildfly-10.0.0.Final/ or restcomm-ussd-*/ inside the tree.

apply_patched_jars() {
    local mirror="${USSDGW_HOST_BASE:-/opt/ussdgw}/patched_jar/mirror"
    local ussd_root="/opt/restcomm/restcomm-ussd-${USSD_VERSION:-7.2.1-SNAPSHOT}"
    local patched=0

    if [ ! -d "$mirror" ]; then
        echo "[patch] No mirror directory at ${mirror} — skipping"
        return 0
    fi

    if [ -z "$(find "$mirror" -type f 2>/dev/null | head -1)" ]; then
        echo "[patch] Mirror directory empty — skipping"
        return 0
    fi

    echo "[patch] Scanning ${mirror} ..."

    while IFS= read -r -d '' src; do
        local rel="${src#${mirror}/}"
        local target=""

        case "$rel" in
            wildfly-*/*)
                target="${ussd_root}/${rel}"
                ;;
            restcomm-ussd-*/*)
                target="/opt/restcomm/${rel}"
                ;;
            *)
                target="${JBOSS_HOME}/${rel}"
                ;;
        esac

        if [ ! -f "$target" ] && [ "${PATCH_FORCE:-true}" != "true" ]; then
            echo "[patch] SKIP (target missing, PATCH_FORCE=false): ${rel}"
            continue
        fi

        mkdir -p "$(dirname "$target")"

        if [ -f "$target" ] && [ "${PATCH_BACKUP:-true}" = "true" ]; then
            cp -f "$target" "${target}.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
        fi

        cp -f "$src" "$target"
        chown ussdgw:ussdgw "$target" 2>/dev/null || true
        echo "[patch] ${rel} -> ${target}"
        patched=$((patched + 1))
    done < <(find "$mirror" -type f -print0 2>/dev/null)

    echo "[patch] Applied ${patched} file(s)"
}

apply_patched_jars
