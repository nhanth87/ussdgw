#!/bin/bash
set -e

JBOSS_HOME=/opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final

if [ "$(id -u)" = "0" ]; then
    echo "[entrypoint] Fixing mount permissions..."
    chown -R ussdgw:ussdgw /opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final/standalone/data 2>/dev/null || true
    chown -R ussdgw:ussdgw /opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final/standalone/log 2>/dev/null || true
    chown ussdgw:ussdgw /opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final/bin/standalone.conf 2>/dev/null || true
    echo "[entrypoint] Starting as ussdgw user..."
    exec gosu ussdgw "${JBOSS_HOME}/bin/standalone.sh" "$@"
else
    exec "${JBOSS_HOME}/bin/standalone.sh" "$@"
fi
