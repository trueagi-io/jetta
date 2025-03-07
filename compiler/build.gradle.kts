plugins {
    kotlin("jvm") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "metta"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":frontend-api"))
    implementation(project(":frontend"))
    implementation(project(":backend"))
    implementation(project(":runtime"))

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

tasks.shadowJar {
    manifest {
        attributes(
            "Main-Class" to "net.singularity.jetta.compiler.MainKt",
            "Implementation-Title" to "Jetta Compiler",
            "Implementation-Version" to archiveVersion.get()
        )
    }
}

tasks.register<Copy>("copyShadowJar") {
    dependsOn(tasks.shadowJar) // Ensure shadowJar runs first

    from(tasks.shadowJar.get().archiveFile) // Get the generated shadow jar
    println(">>>" + layout.projectDirectory.dir("bin"))
    into(layout.projectDirectory.dir("../bin")) // Destination directory

    rename { "jettac.jar" } // Rename the jar file
}