#!/bin/bash
set -e

echo "=== Building RestComm USSD Gateway Docker Image ==="
echo "Step 1: Build Linux release package..."
ant -f build-linux.xml clean release

echo ""
echo "Step 2: Build Docker image..."
docker build -t restcomm-ussd:7.2.1-SNAPSHOT .

echo ""
echo "=== DONE ==="
echo "Image: restcomm-ussd:7.2.1-SNAPSHOT"
echo "Run: docker run -p 8080:8080 -p 9990:9990 restcomm-ussd:7.2.1-SNAPSHOT"
