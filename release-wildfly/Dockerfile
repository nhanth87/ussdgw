FROM eclipse-temurin:8-jdk

# Install unzip + gosu (for user switching)
RUN apt-get update && apt-get install -y unzip gosu lksctp-tools && rm -rf /var/lib/apt/lists/*

# Create ussdgw user with FIXED UID/GID = 2000 (must match host /home/meodien/ussdgateway owner)
RUN groupadd -r -g 2000 ussdgw && useradd -r -u 2000 -g ussdgw -d /opt/restcomm ussdgw

# Set working directory
WORKDIR /opt/restcomm

# Copy and extract release
COPY restcomm-ussd-7.2.1-SNAPSHOT-linux.zip /tmp/
RUN unzip -q /tmp/restcomm-ussd-7.2.1-SNAPSHOT-linux.zip -d /opt/restcomm && \
    rm /tmp/restcomm-ussd-7.2.1-SNAPSHOT-linux.zip && \
    chmod +x /opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final/bin/*.sh && \
    chown -R ussdgw:ussdgw /opt/restcomm

# Copy entrypoint script
COPY docker-entrypoint.sh /opt/restcomm/
RUN chmod +x /opt/restcomm/docker-entrypoint.sh

# Set environment
ENV JBOSS_HOME=/opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final
ENV JAVA_OPTS=""

# Expose ports
EXPOSE 8080 8443 9990 9993 8009 2905 2906 8011 8012

# Healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -fs http://localhost:9990/health || exit 1

# Entrypoint
ENTRYPOINT ["/opt/restcomm/docker-entrypoint.sh"]
CMD ["-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]
