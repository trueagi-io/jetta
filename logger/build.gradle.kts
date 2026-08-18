plugins {
    kotlin("jvm") version "2.3.0"
}

val antlrKotlinVersion: String by project

group = "net.singularity.jetta"
version = "0.8.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}