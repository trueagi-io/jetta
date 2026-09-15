plugins {
    kotlin("jvm") version "2.3.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "metta"
version = "0.9.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":frontend-api"))
    implementation(project(":frontend"))
    implementation(project(":backend"))
    implementation(project(":runtime"))
    implementation(project(":logger"))

    implementation("org.ow2.asm:asm:9.4")
    implementation("org.ow2.asm:asm-commons:9.4")
    implementation("org.ow2.asm:asm-util:9.4")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

val generatedSrcDir = layout.buildDirectory.dir("generated/version")

tasks.register("generateVersionFile") {
    val outputDir = generatedSrcDir.get().asFile
    outputs.dir(outputDir)

    doLast {
        val versionCode = project.version.toString()
        val packageName = "net.singularity.jetta.compiler" // Change to your package name

        val file = outputDir.resolve("VersionInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package $packageName

            object VersionInfo {
                const val VERSION = "$versionCode"
            }
            """.trimIndent()
        )
    }
}

// Ensure the task runs before compilation
tasks.named("compileKotlin") {
    dependsOn("generateVersionFile")
}

// Add the generated directory to Kotlin source sets
sourceSets {
    main {
        kotlin.srcDir(generatedSrcDir)
    }
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

// ---- The standard library, compiled once and shipped inside the jar ----------------------
//
// `stdlib/stdlib.metta` is the reference interpreter's own standard library, vendored verbatim
// (see stdlib/README.md). It is compiled HERE, once per build, into the artifact set a program
// links against; `ImportResolutionPass` then links `(import! &self stdlib)` instead of compiling
// those 1421 lines again for every program.
val stdlibSource = layout.projectDirectory.file("../stdlib/stdlib.metta")
val stdlibArtifacts = layout.buildDirectory.dir("stdlib-artifacts")

val compileStdlib = tasks.register<JavaExec>("compileStdlib") {
    description = "Compiles the vendored MeTTa standard library into the artifacts shipped in the jar"
    group = "build"
    // Runs the compiler from its CLASSPATH rather than from the shadow jar: the jar has to
    // INCLUDE this task's output, so depending on the jar would close a cycle.
    //
    // The classpath is spelled out as classes + dependencies rather than `runtimeClasspath`,
    // which also carries this project's RESOURCES — and those are what this task produces, so
    // naming them would close a second cycle (classes -> processResources -> compileStdlib).
    // Compiling the library needs no resources of ours: it runs with --no-stdlib.
    dependsOn(tasks.named("compileKotlin"))
    classpath = files(
        sourceSets["main"].output.classesDirs,
        configurations.named("runtimeClasspath"),
    )
    mainClass.set("net.singularity.jetta.MainKt")
    inputs.file(stdlibSource).withPropertyName("stdlibSource")
    outputs.dir(stdlibArtifacts).withPropertyName("stdlibArtifacts")
    argumentProviders.add {
        listOf(
            stdlibSource.asFile.absolutePath,
            "-d", stdlibArtifacts.get().asFile.absolutePath,
            "--no-greetings",
        )
    }
    doFirst { stdlibArtifacts.get().asFile.mkdirs() }
}

// The artifacts travel as ordinary RESOURCES, not as something added to the shadow jar alone.
// That way every consumer sees the same library: the test runtime classpath, the plain jar, the
// shadow jar, and the test-runner's own shadow jar. Wiring them into `shadowJar` only would mean
// `./gradlew test` compiled programs without a standard library while `bin/jettac` compiled them
// with one — the two would measure different products.
tasks.processResources {
    dependsOn(compileStdlib)
    // The module's CLASS goes to the root: a compiled MeTTa program lands in the default package,
    // and the runtime loads it by the bare name its interface records.
    from(stdlibArtifacts) {
        include("*.class")
        into("")
    }
    // Its space, manifest and any prebuilt indices go where ArtifactSource.shipped() looks.
    from(stdlibArtifacts) {
        exclude("*.class")
        into("jetta/lib")
    }
}

tasks.shadowJar {
    manifest {
        attributes(
            "Main-Class" to "net.singularity.jetta.MainKt",
            "Implementation-Title" to "Jetta Compiler",
            "Implementation-Version" to archiveVersion.get()
        )
    }
}

tasks.register<Copy>("copyShadowJar") {
    dependsOn(tasks.shadowJar) // Ensure shadowJar runs first

    from(tasks.shadowJar.get().archiveFile) // Get the generated shadow jar
    into(layout.projectDirectory.dir("../bin")) // Destination directory

    rename { "jettac.jar" } // Rename the jar file
}

tasks.build {
    finalizedBy("copyShadowJar")
}