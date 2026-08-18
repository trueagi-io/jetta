plugins {
    kotlin("jvm") version "2.3.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "metta"
version = "0.8.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":frontend-api"))
    implementation(project(":frontend"))
    implementation(project(":backend"))
    implementation(project(":runtime"))
    implementation(project(":logger"))
    implementation(project(":compiler"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("net.singularity.jetta.test.MainKt")
}

tasks.named<JavaExec>("run") {
    // Default args: tests/metta tests/reports
    // Override with --args="<test-dir> <report-dir> [--xfail <file>]"
    if (project.hasProperty("runArgs")) {
        args((project.property("runArgs") as String).split("\\s+".toRegex()))
    } else {
        args("tests/metta", "tests/reports")
    }
    // Run from project root so tests/metta paths resolve as expected
    workingDir = rootProject.projectDir
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

tasks.shadowJar {
    manifest {
        attributes(
            "Main-Class" to "net.singularity.jetta.test.MainKt",
            "Implementation-Title" to "Jetta Test Runner",
            "Implementation-Version" to archiveVersion.get()
        )
    }
}
