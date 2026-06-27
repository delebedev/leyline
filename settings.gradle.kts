plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "leyline"
include("domain")
include("engine")
include("native")
include("web")
include("tools:detekt-rules")
