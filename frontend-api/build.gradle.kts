plugins {
    kotlin("jvm") version "2.3.0"
}

group = "net.singularity.jetta"
version = "0.6.1"

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