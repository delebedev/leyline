plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

rootProject.name = "leyline"
include("domain")
include("engine")
include("native")
include("web")
include("tools:detekt-rules")
