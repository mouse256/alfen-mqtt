#!/bin/bash

docker buildx build --push --platform linux/arm64,linux/amd64 --tag ghcr.io/mouse256/alfen-mqtt:0.0.3-SNAPSHOT -f src/main/docker/Dockerfile.jvm .

