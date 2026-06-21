#!/bin/bash
# Compute AUTO_JVM_OPTS from container cgroup memory/CPU limits.
# Sourced by docker-entrypoint.sh — do not execute directly.

compute_cgroup_memory_bytes() {
    local mem
    if [ -f /sys/fs/cgroup/memory.max ]; then
        mem=$(cat /sys/fs/cgroup/memory.max 2>/dev/null)
        if [ "$mem" = "max" ] || [ -z "$mem" ]; then
            mem=$(awk '/MemTotal/ {printf "%.0f", $2 * 1024}' /proc/meminfo 2>/dev/null)
        fi
    elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
        mem=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)
        if [ "$mem" -gt 9223372036854770000 ] 2>/dev/null; then
            mem=$(awk '/MemTotal/ {printf "%.0f", $2 * 1024}' /proc/meminfo 2>/dev/null)
        fi
    else
        mem=$(awk '/MemTotal/ {printf "%.0f", $2 * 1024}' /proc/meminfo 2>/dev/null)
    fi
    # Bash $(( )) cannot parse scientific notation (e.g. 3.97e+09 from some awk builds)
    awk 'BEGIN { v = '"${mem:-4294967296}"'; if (v < 1) v = 4294967296; printf "%.0f", v }'
}

compute_cgroup_cpus() {
    local quota period cpus
    if [ -f /sys/fs/cgroup/cpu.max ]; then
        read -r quota period < /sys/fs/cgroup/cpu.max 2>/dev/null || true
        if [ "$quota" = "max" ] || [ -z "$quota" ] || [ "$period" = "0" ]; then
            cpus=$(nproc 2>/dev/null || echo 2)
        else
            cpus=$(( (quota + period - 1) / period ))
        fi
    elif [ -f /sys/fs/cgroup/cpu/cpu.cfs_quota_us ]; then
        quota=$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null)
        period=$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us 2>/dev/null)
        if [ "$quota" -le 0 ] 2>/dev/null; then
            cpus=$(nproc 2>/dev/null || echo 2)
        else
            cpus=$(( (quota + period - 1) / period ))
        fi
    else
        cpus=$(nproc 2>/dev/null || echo 2)
    fi
    [ "$cpus" -lt 1 ] && cpus=1
    echo "$cpus"
}

compute_auto_jvm_opts() {
    AUTO_JVM_OPTS=""
    if [ "${AUTO_JVM_ENABLED:-true}" != "true" ]; then
        export AUTO_JVM_OPTS
        export AUTO_JVM_HEAP=""
        export AUTO_JVM_CPUS=""
        return 0
    fi

    local mem_bytes cpus mem_gb heap direct pause gc_threads conc_threads
    mem_bytes=$(compute_cgroup_memory_bytes)
    cpus=$(compute_cgroup_cpus)
    mem_gb=$(awk 'BEGIN { printf "%d", '"${mem_bytes}"' / 1024 / 1024 / 1024 }')

    if [ "$mem_gb" -le 4 ]; then
        heap="2g"; direct="512m"; pause="200"; gc_threads=2; conc_threads=1
    elif [ "$mem_gb" -le 8 ]; then
        heap="4g"; direct="1024m"; pause="100"; gc_threads=4; conc_threads=2
    elif [ "$mem_gb" -le 16 ]; then
        heap="6g"; direct="1536m"; pause="50"; gc_threads=4; conc_threads=2
    elif [ "$mem_gb" -le 32 ]; then
        heap="8g"; direct="2048m"; pause="20"; gc_threads=8; conc_threads=4
    else
        heap="12g"; direct="3072m"; pause="20"; gc_threads=16; conc_threads=8
    fi

    if [ "$cpus" -lt "$gc_threads" ]; then
        gc_threads=$cpus
    fi
    if [ "$conc_threads" -gt $(( cpus / 2 )) ] && [ "$cpus" -gt 1 ]; then
        conc_threads=$(( cpus / 2 ))
    fi
    [ "$conc_threads" -lt 1 ] && conc_threads=1

    AUTO_JVM_OPTS="-Xms${heap} -Xmx${heap}"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:MaxDirectMemorySize=${direct}"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=${pause}"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:ParallelGCThreads=${gc_threads} -XX:ConcGCThreads=${conc_threads}"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:+UseContainerSupport -XX:+DisableExplicitGC"
    AUTO_JVM_OPTS="$AUTO_JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/ussdgw/log/heapdump.hprof"

    export AUTO_JVM_OPTS
    export AUTO_JVM_HEAP="$heap"
    export AUTO_JVM_CPUS="$cpus"
    export AUTO_JVM_MEM_GB="$mem_gb"
}

compute_auto_jvm_opts
