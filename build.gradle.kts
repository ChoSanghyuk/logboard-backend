plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.logboard"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0")
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.2"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.apache.sshd:sshd-core:2.20.1")
    implementation("org.apache.sshd:sshd-sftp:2.20.1")
    runtimeOnly("net.i2p.crypto:eddsa:0.3.0")   // ed25519 private keys for MINA SSHD
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew bootRun` is the local dev entry point: enable the dev profile (CORS for the Vite dev server).
tasks.bootRun {
    systemProperty("spring.profiles.active", "dev")
}
