plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
rootProject.name = "jetta"
include("compiler")
include("frontend-api")
include("backend")
include("frontend")
include("server")
include("runtime")
