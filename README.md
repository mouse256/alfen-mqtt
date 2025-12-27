# Alfen-mqtt

This project connects to [Alfen](https://alfen.com/en) charging points and exposes the data over MQTT.
It's currently tested with an Alfen eve pro, other models may or may not work.

## Running

The easiest way to run this project is to use the pre-built docker image:
```aiignore
# check the latest version from the releases page
docker run ghcr.io/mouse256/alfen-mqtt:1.2.4
```

There are 2 modes in which this tool can communicate with the alfen chargepoint: `http` or `modbus`

### modbus mode
This is the recommended mode.

In this mode, it uses the modbus protocol to connect to the charging point. It's a more advanced/complex protocol.

The disadvantage of this mode is that you need this option to be activated on your charging point. It can be validated in the eve-pro app if this is activated.

### http mode
In this mode, it uses the http endpoints exposed by the charging point. This uses the same protocol the alfen mobile app uses.

The advantage of this mode is that every charging point supports this.
The disadvantage is that you can only have 1 client connected at the same time. So you can't use the mobile app and this tool at the same time.

## Configuration options
This project is built with [Quarkus](https://quarkus.io), which means it follow the conventions there to configure it.

The built-in configuration can be found in the [application.properties](src/main/resources/application.properties) file.
Every item in there can be overruled by passing it as an [environment variable](https://quarkus.io/guides/config-reference#environment-variables)

Sample running with configuration options
```aiignore
docker run \
  --name alfen-mqtt \
  -e ALFEN_DEVICES_1__ENDPOINT="192.168.1.58" \
  -e ALFEN_DEVICES_1__TYPE="modbus" \
  -e MQTT_HOST=192.168.1.152 \
  -e MQTT_PORT=1883 \
  ghcr.io/mouse256/alfen-mqtt:1.2.4
```

## Advanced docker mode

Besides the regular docker image, there is also a docker image provided which contains only the jar files, no JVM.
This can be used if you want to use the JVM from the host for a more light-weight image.

```aiignore
docker run \
  --name alfen-mqtt \
  -e ALFEN_DEVICES_1__ENDPOINT="192.168.1.58" \
  -e ALFEN_DEVICES_1__TYPE="modbus" \
  -e MQTT_HOST=192.168.1.152 \
  -e MQTT_PORT=1883 \
  --mount type=bind,src=/usr,dst=/usr \
  --mount type=bind,src=/lib,dst=/lib \
  --mount type=bind,src=/lib64,dst=/lib64 \
  --mount type=bind,src=/etc/alternatives,dst=/etc/alternatives \
  --mount type=tmpfs,dst=/tmp \
  ghcr.io/mouse256/alfen-mqtt:1.2.4-scratch
```