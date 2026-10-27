# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important information

No guessing. If something is unclear, ask for user input to clarify the question.
Make sure the specification is clear before doing an implementation.
If a new feature is added, update CLAUDE.md

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

MQTT topic ownership: only this app publishes state under `alfen/...`. Other tools may only publish *commands* there (`alfen/set/...`); state from other tools (grid power, car battery level) is read from their own topic trees, configured via `writer.grid-*-topic` / `soc.car-soc-topic`.

Notable flags: `writer.enabled` (starts the charging controller, the only thing that writes to the charger; default false), `writer.dry-run` (controller runs and publishes its status but no modbus writes are done; default false), `mqtt.enabled`, `auto.startup` (when false, `Main` skips wiring everything — used by tests), `discovery.uuid` (HA discovery device id).

## Architecture

`Main` is the CDI entrypoint: on `StartupEvent` it starts `MqttHandler`, then `AlfenModbus`, then `AlfenController` (and stops them in reverse). The two device modes are deliberately separate subsystems that share only the MQTT layer.

- **`MqttHandler`** — single shared Vert.x `MqttClient`. Owns connect/reconnect logic (retries connect every 60s, auto-restarts 30s after a close/exception). Other components `register(pattern, mqttPattern, listener)` to subscribe to inbound topics, and publish via its `publish*`/`publishJson` methods. This is the only thing that touches the broker. The vert.x `MqttClient` is not thread safe, so every call into it (publish, subscribe, disconnect) is run on the context it connected on, whatever thread the caller is on (e.g. the modbus thread).
- **`MqttPublisher`** — outbound side: formats device readings into topics/payloads and pushes them through `MqttHandler`.
- **HTTP mode** — `AlfenController` (`@ApplicationScoped`) sets a Vert.x periodic timer (every 5s) that polls each HTTP device for a fixed set of categories (`meter1`, `temp`, `generic2`). Each device has an `AlfenConnection` that logs in over the Alfen HTTP API (TLS, **trusts all certs**), fetches paged `prop?cat=` responses, and `AlfenController` resolves raw property ids to names via `/ids.properties` and type-converts values into `PropertyParsed`. `AlfenResource` exposes the same data over REST at `/alfen/{device}/categories` and `/alfen/{device}/properties/{category}`.
- **Modbus mode** — `AlfenModbus` creates one `AlfenModbusClient` per modbus device. `AlfenModbusClient` (the largest/most complex class) holds the digitalpetri Modbus TCP connection, polls holding registers, maintains per-socket `socketStatus`/`socketMeasurement`/`setStates`, publishes readings + HA discovery, and drives `AlfenModbusWriter` to write desired charge state (enable, max current, phase count) back to registers in response to MQTT control messages. `ModbusConst` holds register addresses/layouts. All modbus I/O of a client (the 1s poll and the writes) runs on its own single `modbus-<name>` thread, never on the vert.x event loop: `setState`/`disable` only record the desired state and queue a write, which uses the socket status of the last poll, and the getters used by the controller read concurrent maps without locking.

Vehicle connected: after each poll of the socket status group, `AlfenModbusClient` derives from the IEC 61851 Mode 3 state (register 1201) whether a car is connected (B*/C*/D* = yes, A/E/F = no, anything else unknown and not published) and publishes `ON`/`OFF` on `alfen/modbus/state/<charger>/<socket>/connected` every poll; discovered in HA as binary sensor "Car connected" (device class `plug`). `isVehicleConnected(socket)` exposes it in code.

Register maps, property id mappings, and HA discovery component definitions are the domain-specific heart of the app; the rest is plumbing.

### Charging controller (load management)

`AlfenModbusWriter` (one instance per socket, created by `AlfenModbusClient`) is an evcc-style load manager that decides whether to charge and at what current/phase count, then drives the charger via `AlfenModbusClient.setState`/`disable`. It only runs when `writer.enabled` (`WriterConfig`, prefix `writer`) is true; with `writer.dry-run` it makes its decisions and publishes its status but `AlfenModbusClient` skips the actual register writes.

Inputs over MQTT:
- **mode** on `alfen/set/<charger>/<socket>/mode` — one of `OFF`, `PV_ONLY` (only charge on solar surplus), `PV_AND_MIN` (always at least min current, faster on surplus), `FAST` (as fast as the grid budget allows), `SMART` (battery-level dependent, see below).
- **grid power** on the configurable `writer.grid-consumed-topic` / `writer.grid-produced-topic` (payloads in kW). Net grid power is the only signal used; there is no separate solar input.

The control loop (`update`, every `writer.interval`) computes `powerAvailable = (produced - consumed) + chargerDraw` (the solar surplus excluding the charger itself) and applies, in order: a stale-input guard (`writer.input-timeout`), an over-limit guard while charging (grid import above `writer.max-grid-power` for `writer.over-limit-delay` steps down: 3 phase → 1 phase immediately, bypassing the phase debounce, or stop + cooldown when already on 1 phase at minimum current), start/stop debounce (`writer.start-delay` only in `PV_ONLY`, `PV_AND_MIN`/`FAST` start right away; `writer.stop-delay`), a post-stop `writer.cooldown`, and target current/phase selection clamped to `[writer.min-current, writer.max-current]`. Phase count auto-switches 1↔3 (debounced by `writer.phase-switch-delay`) with hysteresis: up to 3 phase only when the budget covers the 3-phase minimum plus `writer.three-phase-margin`, back to 1 phase when it no longer covers the 3-phase minimum. `PV_ONLY` thresholds are evaluated against the grid power as it would be with the car charging at minimum current on 1 phase (`powerAvailable - min-current × 230`): it starts when that still exports at least `writer.solar-start-power` and stops when it imports more than `writer.solar-stop-power`. Controller status (`ControllerStatus`: mode, state, charging, targetCurrent, phases, timerEnd) is published to `alfen/controller/<charger>/<socket>` every interval. `state` (`ControllerState`) says what the controller is doing or waiting for: `OFF`, `STALE_INPUT`, `NO_MEASUREMENT`, `WAITING_FOR_SURPLUS`, `START_DELAY`, `COOLDOWN`, `CHARGING`, `STOP_DELAY`, `OVER_LIMIT`, `PHASE_SWITCH_DELAY`; `timerEnd` is the ISO-8601 instant the running timer of that state expires (null when none). State changes are logged once (`Controller <charger> (<socket>) mode <mode>: state <state> until <time> (<n>s)`), at WARN for `STALE_INPUT`/`NO_MEASUREMENT`, not every interval.

The charge mode is kept in memory but published **retained** on `alfen/controller/<charger>/<socket>/mode` whenever a mode command arrives. The writer subscribes to that topic and restores the mode from it at startup (default `OFF` when nothing is retained); once a mode command was received the retained value is ignored, so our own echoed publishes never override a newer command.

Home Assistant discovery (`AlfenModbusClient.sendDiscovery`) adds per socket, next to the measurement sensors: a `select` "Charge mode" (command `alfen/set/...`, state from the retained mode topic), a `binary_sensor` "Controller charging" and sensors "Controller target current"/"Controller phases"/"Controller state"/"Controller timer end" (timestamp) from the status JSON. These use their own `state_topic`, overriding the device-level one. `Select` and `BinarySensor` live in the sibling `homeassistant-discovery` library. Topic names come from the static helpers `AlfenModbusWriter.modeCommandTopic`/`modeStateTopic`/`statusTopic`.

**SMART mode** uses the battery level from `SocEstimator` (passed to the writer as a `Supplier<Optional<Double>>`, empty when the estimator isn't configured or has no car value yet). `allowedImport(soc)` is `writer.smart-grid-import-low` (default 1000 W) up to `writer.smart-min-soc` (default 40%), `writer.smart-grid-import-high` (default 0 W) at 100%, linear in between. Below min-soc it always charges without start delay (like `PV_AND_MIN`) with budget `powerAvailable + smart-grid-import-low`. Above it, it is solar driven with start delay: start when `gridAtMin = powerAvailable - min-current × 230 ≥ -allowedImport`, stop (after stop-delay) when `gridAtMin < -allowedImport - writer.smart-stop-hysteresis` (default 500 W), budget `powerAvailable + allowedImport`. With an unknown battery level it behaves exactly like `PV_ONLY`. Sub-phase changes (unknown / below minimum / solar) are logged once. The status JSON also carries `soc` and `allowedGridImport` (only in SMART), discovered as "Controller allowed grid import".

All time-based decisions use an injectable `Supplier<Instant>` clock so `AlfenModbusWriterTest` can drive them with simulated time.

### Car battery estimation

The charger doesn't know the car's battery level (AC / IEC 61851 has no SoC; there is no such register in `configuration_guide_modbus_ace_v1.pdf`), so `SocEstimator` (one per socket, created by `AlfenModbusClient` next to the writer) estimates it evcc-style. It is enabled only when `soc.battery-capacity` (kWh, `SocConfig`, prefix `soc`) is set. The car's reported level arrives over MQTT on `soc.car-soc-topic` (default `skoda/enyaq/battery_percentage`, plain percentage, published by e.g. a Home Assistant automation from the car integration; no car APIs in this app). All sockets' estimators listen to that same topic (one car). The estimate is `carSoc + deliveredSinceKWh × soc.efficiency (default 0.9) / capacity × 100`, capped at 100, where the delivered energy comes from register 374 "Real Energy Delivered Sum"; each new reported level resets the anchor. Every `soc.interval` it publishes `SocStatus` (estimate, carSoc, carSocReceived, energySinceWh) on `alfen/car/<charger>/<socket>/soc_estimate`, discovered in HA as the battery sensor "Estimated battery". The anchor is not persisted: after a restart it restarts from the (retained) car level and the current meter value.

## Testing

Tests are `@QuarkusTest`-based and use `@QuarkusTestResource` to stand up mock devices and an embedded broker, so they exercise real wiring without hardware:
- `MockAlfenModbusDevice` / `ModbusTestResource` — fake modbus charge point.
- `MockAlfenHttpDevice(Resource)` — fake Alfen HTTP API.
- `TestMqttServer` / `MqttTestResource` — embedded MQTT broker.

Tests typically disable `auto.startup` (via a `@TestProfile`) and start the subsystem under test manually in `@BeforeEach`.
