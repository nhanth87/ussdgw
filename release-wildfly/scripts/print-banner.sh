#!/bin/bash
# Print startup summary for operators.

print_startup_banner() {
    echo "============================================================"
    echo " USSD Gateway Docker Startup"
    echo " Version:     ${USSD_VERSION:-7.2.1-SNAPSHOT}"
    echo " Profile:     ${USSDGW_PROFILE:-lab}"
    echo " JBOSS_HOME:  ${JBOSS_HOME}"
    echo " Host base:   ${USSDGW_HOST_BASE:-/opt/ussdgw}"
    echo " Memory (GB): ${AUTO_JVM_MEM_GB:-n/a}"
    echo " Heap:        ${AUTO_JVM_HEAP:-manual}"
    echo " CPUs:        ${AUTO_JVM_CPUS:-n/a}"
    echo " AUTO JVM:    ${AUTO_JVM_ENABLED:-true}"
    echo "============================================================"
    echo " AUTO_JVM_OPTS: ${AUTO_JVM_OPTS:-}"
    echo " USER_CONFIG:   ${USER_CONFIG_JVM:-}"
    echo "============================================================"
}

check_sctp_module() {
    if [ ! -f /proc/net/sctp ]; then
        echo "[warn] SCTP kernel module not loaded on host — SS7/SIGTRAN will not work"
        echo "[warn] Run on host: sudo modprobe sctp"
    else
        echo "[ok] SCTP kernel interface available"
    fi
}

check_data_config() {
    local data="${USSDGW_HOST_BASE:-/opt/ussdgw}/data"
    if [ ! -f "${data}/SCTPManagement_sctp.xml" ]; then
        echo "[warn] Missing ${data}/SCTPManagement_sctp.xml — seed config or edit SS7 XML"
    fi
}

print_startup_banner
check_sctp_module
check_data_config
