# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Quarkus (Java 25) application that polls Alfen EV charging points and republishes their data over MQTT, including Home Assistant / OpenHAB MQTT auto-discovery. A charge point can be read in two independent modes — **modbus** (recommended, TCP, supports writing/control) and **http** (Alfen's mobile-app API, read-only here, single client only).

## Build & Run

The Gradle build is a **composite build** that depends on the sibling project `homeassistant-discovery`. By default it expects it at `../homeassistant-discovery` (see `includeBuild` in `settings.gradle.kts`). Override the location with `-PhaDiscoveryDir=/path` if it lives elsewhere. The build fails without it.

```bash
./gradlew quarkusDev          # dev mode, live reload (run.sh sources private.env first)
./gradlew test                # run all tests
./gradlew assemble            # build the quarkus-app under build/quarkus-app/
./gradlew clean assemble      # full rebuild (what build.sh does before the docker build)

# Run a single test class / method
./gradlew test --tests org.muizenhol.alfen.AlfenModbusTest
./gradlew test --tests "org.muizenhol.alfen.AlfenModbusTest.test1"
```

`build.sh` builds and pushes multi-arch docker images (`Dockerfile` = full JVM, `Dockerfile-scratch` = jars only, relies on the host JVM). `run.sh` runs dev mode after sourcing `private.env` (local secrets, gitignored).

## Configuration

Standard Quarkus config (`src/main/resources/application.properties`); every key is overridable via environment variables. Devices are a list under the `alfen.devices` prefix mapped by `AlfenConfig`:

```
ALFEN_DEVICES_1__NAME=alfen1
ALFEN_DEVICES_1__TYPE=modbus     # or http
ALFEN_DEVICES_1__ENDPOINT=192.168.1.50   # http mode uses an https:// URL + username/password
MQTT_HOST=...  MQTT_PORT=1883
```

Notable flags: `modbus.write_enabled` (gates all modbus writes/control, default false; on in `%dev`), `mqtt.enabled`, `auto.startup` (when false, `Main` skips wiring everything — used by tests), `discovery.uuid` (HA discovery device id).

## Architecture

`Main` is the CDI entrypoint: on `StartupEvent` it starts `MqttHandler`, then `AlfenModbus`, then `AlfenController` (and stops them in reverse). The two device modes are deliberately separate subsystems that share only the MQTT layer.

- **`MqttHandler`** — single shared Vert.x `MqttClient`. Owns connect/reconnect logic (retries connect every 60s, auto-restarts 30s after a close/exception). Other components `register(pattern, mqttPattern, listener)` to subscribe to inbound topics, and publish via its `publish*`/`publishJson` methods. This is the only thing that touches the broker.
- **`MqttPublisher`** — outbound side: formats device readings into topics/payloads and pushes them through `MqttHandler`.
- **HTTP mode** — `AlfenController` (`@ApplicationScoped`) sets a Vert.x periodic timer (every 5s) that polls each HTTP device for a fixed set of categories (`meter1`, `temp`, `generic2`). Each device has an `AlfenConnection` that logs in over the Alfen HTTP API (TLS, **trusts all certs**), fetches paged `prop?cat=` responses, and `AlfenController` resolves raw property ids to names via `/ids.properties` and type-converts values into `PropertyParsed`. `AlfenResource` exposes the same data over REST at `/alfen/{device}/categories` and `/alfen/{device}/properties/{category}`.
- **Modbus mode** — `AlfenModbus` creates one `AlfenModbusClient` per modbus device. `AlfenModbusClient` (the largest/most complex class) holds the digitalpetri Modbus TCP connection, polls holding registers, maintains per-socket `socketStatus`/`socketMeasurement`/`setStates`, publishes readings + HA discovery, and — when `writeEnabled` — drives `AlfenModbusWriter` to write desired charge state (enable, max current, phase count) back to registers in response to MQTT control messages. `ModbusConst` holds register addresses/layouts.

Register maps, property id mappings, and HA discovery component definitions are the domain-specific heart of the app; the rest is plumbing.

## Testing

Tests are `@QuarkusTest`-based and use `@QuarkusTestResource` to stand up mock devices and an embedded broker, so they exercise real wiring without hardware:
- `MockAlfenModbusDevice` / `ModbusTestResource` — fake modbus charge point.
- `MockAlfenHttpDevice(Resource)` — fake Alfen HTTP API.
- `TestMqttServer` / `MqttTestResource` — embedded MQTT broker.

Tests typically disable `auto.startup` (via a `@TestProfile`) and start the subsystem under test manually in `@BeforeEach`.
