FROM eclipse-temurin:8-jdk

# Install unzip
RUN apt-get update && apt-get install -y unzip && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /opt/restcomm

# Copy and extract release
COPY restcomm-ussd-7.2.1-SNAPSHOT-linux.zip /tmp/
RUN unzip -q /tmp/restcomm-ussd-7.2.1-SNAPSHOT-linux.zip -d /opt/restcomm && \
    rm /tmp/restcomm-ussd-7.2.1-SNAPSHOT-linux.zip && \
    chmod +x /opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final/bin/*.sh

# Copy entrypoint script
COPY docker-entrypoint.sh /opt/restcomm/
RUN chmod +x /opt/restcomm/docker-entrypoint.sh

# Set environment
ENV JBOSS_HOME=/opt/restcomm/restcomm-ussd-7.2.1-SNAPSHOT/wildfly-10.0.0.Final
ENV JAVA_OPTS="-Xms1024m -Xmx2048m -Djava.net.preferIPv4Stack=true"

# Expose ports
EXPOSE 8080 8443 9990 9993 8009

# Volume for external data, logs and custom standalone.conf
VOLUME ["/opt/ussdgw"]

# Entrypoint handles standalone.conf copy + data/log symlinks before starting WildFly
ENTRYPOINT ["/opt/restcomm/docker-entrypoint.sh"]
CMD ["-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]
