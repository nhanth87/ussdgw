FROM eclipse-temurin:8-jdk-jammy

ARG USSD_VERSION=7.2.1-SNAPSHOT
ENV USSD_VERSION=${USSD_VERSION}
ENV JBOSS_HOME=/opt/restcomm/restcomm-ussd-${USSD_VERSION}/wildfly-10.0.0.Final
ENV USSDGW_HOST_BASE=/opt/ussdgw
ENV AUTO_JVM_ENABLED=true
ENV USSDGW_PROFILE=lab
ENV USER_CONFIG_JVM=""
ENV PATCH_FORCE=true
ENV PATCH_BACKUP=true

RUN apt-get update && apt-get install -y \
    unzip \
    gosu \
    lksctp-tools \
    curl \
    procps \
    iproute2 \
    inetutils-ping \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r -g 2000 ussdgw \
 && useradd -r -u 2000 -g 2000 -d /opt/restcomm -s /sbin/nologin ussdgw

WORKDIR /opt/restcomm

COPY restcomm-ussd-${USSD_VERSION}-linux.zip /tmp/
RUN unzip -q /tmp/restcomm-ussd-${USSD_VERSION}-linux.zip -d /opt/restcomm \
 && rm /tmp/restcomm-ussd-${USSD_VERSION}-linux.zip \
 && chmod +x ${JBOSS_HOME}/bin/*.sh \
 && cp ${JBOSS_HOME}/bin/standalone.conf ${JBOSS_HOME}/bin/standalone.conf.bundled \
 && chown -R ussdgw:ussdgw /opt/restcomm

COPY standalone.conf ${JBOSS_HOME}/bin/standalone.conf
COPY scripts/ /opt/restcomm/scripts/
COPY docker-entrypoint.sh /opt/restcomm/docker-entrypoint.sh
RUN chmod +x /opt/restcomm/docker-entrypoint.sh /opt/restcomm/scripts/*.sh \
 && chown -R ussdgw:ussdgw /opt/restcomm/scripts /opt/restcomm/docker-entrypoint.sh ${JBOSS_HOME}/bin/standalone.conf

EXPOSE 8080 8443 9990 9993 8009 2905 2906 8011 8012

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=10 \
    CMD curl -fs http://localhost:9990/health || exit 1

ENTRYPOINT ["/opt/restcomm/docker-entrypoint.sh"]
CMD ["-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]
