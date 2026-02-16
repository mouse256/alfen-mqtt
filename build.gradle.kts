plugins {
    java
    alias(libs.plugins.quarkus)
}

repositories {
    mavenCentral()
    mavenLocal()
}


dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    //implementation("io.quarkus:quarkus-messaging-mqtt")
    implementation("io.vertx:vertx-mqtt")
    implementation("io.quarkus:quarkus-resteasy-jackson")
    implementation("io.quarkus:quarkus-arc")
    //implementation("io.quarkus:quarkus-container-image-jib")
    implementation("org.muizenhol:homeassistant-discovery:1.0.0")
    //implementation(libs.zeroconf)
    implementation("com.digitalpetri.modbus:modbus-tcp:2.1.4")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.vertx:vertx-web")
    testImplementation("io.quarkus:quarkus-vertx")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    runtimeOnly("org.jboss.slf4j:slf4j-jboss-logmanager")
}

group = "org.acme"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
