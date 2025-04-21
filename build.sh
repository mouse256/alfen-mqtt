#!/bin/bash

./gradlew clean assemble
docker buildx build --push --platform linux/arm64,linux/amd64 --tag ghcr.io/mouse256/alfen-mqtt:dev -f src/main/docker/Dockerfile.jvm .

