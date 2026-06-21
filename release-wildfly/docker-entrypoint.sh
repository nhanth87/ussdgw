#!/bin/bash
set -euo pipefail

JBOSS_HOME="${JBOSS_HOME:-/opt/restcomm/restcomm-ussd-${USSD_VERSION:-7.2.1-SNAPSHOT}/wildfly-10.0.0.Final}"
SCRIPT_DIR="/opt/restcomm/scripts"
USSDGW_HOST_BASE="${USSDGW_HOST_BASE:-/opt/ussdgw}"

export JBOSS_HOME USSDGW_HOST_BASE

# ── Phase 0: ensure container hostname resolves (required for network_mode:host) ──
# Docker sets the kernel hostname (e.g. ussd-ng) but does not add it to /etc/hosts.
# WildFly/SLEE call InetAddress.getLocalHost() during startup → UnknownHostException without this.
ensure_local_hostname() {
    local name="${USSDGW_HOSTNAME:-$(hostname -s 2>/dev/null || hostname)}"
    [ -n "$name" ] || return 0
    [ "$name" = "localhost" ] && return 0
    if grep -qE "[[:space:]]${name}([[:space:]]|$)" /etc/hosts 2>/dev/null; then
        echo "[init] hostname ${name} already in /etc/hosts"
        return 0
    fi
    echo "[init] Adding 127.0.1.1 ${name} to /etc/hosts (host-network hostname resolution)"
    # Ensure newline before append (some base images ship /etc/hosts without trailing LF)
    if [ -s /etc/hosts ] && [ "$(tail -c1 /etc/hosts | wc -l)" -eq 0 ]; then
        echo "" >> /etc/hosts
    fi
    echo "127.0.1.1 ${name}" >> /etc/hosts
}
ensure_local_hostname

# ── Phase 1: init directories, seed config, wire symlinks ──────────
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/init-host-dirs.sh"

# ── Phase 2: overlay hot-patch JARs from host mirror ─────────────────
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/apply-patched-jars.sh"

# ── Phase 3: auto-detect JVM heap/GC from cgroup ─────────────────────
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/compute-jvm.sh"

# ── Phase 4: merge JVM layers + profile extras ───────────────────────
BASE_JAVA_OPTS=""
if [ -f "${JBOSS_HOME}/bin/standalone.conf" ]; then
    set +u
    # shellcheck source=/dev/null
    source "${JBOSS_HOME}/bin/standalone.conf"
    set -u
    BASE_JAVA_OPTS="${JAVA_OPTS:-}"
fi

PRODUCTION_JVM_EXTRAS=""
if [ "${USSDGW_PROFILE:-lab}" = "production" ]; then
    PRODUCTION_JVM_EXTRAS="-Djainslee.eventrouter.useDisruptor=true"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.eventrouter.threads=32"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.eventrouter.ringsize=65536"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.eventrouter.waitstrategy=blocking"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.eventrouter.multi.producer=false"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.eventrouter.collectStats=false"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.sbb.pool.min=500"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.sbb.pool.max=50000"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Djainslee.sbb.pool.maxIdle=10000"
    PRODUCTION_JVM_EXTRAS="$PRODUCTION_JVM_EXTRAS -Dio.netty.leakDetectionLevel=disabled"
fi

export JAVA_OPTS="${AUTO_JVM_OPTS:-} ${BASE_JAVA_OPTS} ${PRODUCTION_JVM_EXTRAS} ${USER_CONFIG_JVM:-}"

# shellcheck source=/dev/null
source "${SCRIPT_DIR}/print-banner.sh"

# ── Phase 5: start WildFly as ussdgw ─────────────────────────────────
if [ "$(id -u)" = "0" ]; then
    exec gosu ussdgw "${JBOSS_HOME}/bin/standalone.sh" "$@"
else
    exec "${JBOSS_HOME}/bin/standalone.sh" "$@"
fi
