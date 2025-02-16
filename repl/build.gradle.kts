val kotlinVersion: String by project

plugins {
    kotlin("jvm") version "2.1.0"
}

group = "net.singularity.jetta"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":frontend-api"))
    implementation(project(":frontend"))
    implementation(project(":backend"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}