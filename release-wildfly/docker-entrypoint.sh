#!/bin/bash
set -e

JBOSS_HOME=/opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final

# Copy custom standalone.conf if provided via volume mount
custom_conf="/opt/ussdgw/standalone.conf"
if [ -f "$custom_conf" ]; then
    echo "[entrypoint] Copying custom standalone.conf from $custom_conf"
    cp "$custom_conf" "${JBOSS_HOME}/bin/standalone.conf"
else
    echo "[entrypoint] No custom standalone.conf found at $custom_conf, using default"
fi

# Ensure external data/log directories exist
mkdir -p /opt/ussdgw/data
mkdir -p /opt/ussdgw/log

# Replace standalone/data and standalone/log with symlinks to external volumes
rm -rf "${JBOSS_HOME}/standalone/data"
rm -rf "${JBOSS_HOME}/standalone/log"
ln -sf /opt/ussdgw/data "${JBOSS_HOME}/standalone/data"
ln -sf /opt/ussdgw/log  "${JBOSS_HOME}/standalone/log"

echo "[entrypoint] standalone/data -> /opt/ussdgw/data"
echo "[entrypoint] standalone/log  -> /opt/ussdgw/log"

# Start WildFly with any extra arguments passed to the container
exec "${JBOSS_HOME}/bin/standalone.sh" "$@"
