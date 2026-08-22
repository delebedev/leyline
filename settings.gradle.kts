plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

rootProject.name = "leyline"
include("domain")
include("engine")
include("gre-proto")
include("native")
include("web")
include("tools:detekt-rules")

// :gre-proto maps to the existing proto/ directory (rename-map.sed + upstream submodule).
project(":gre-proto").projectDir = file("proto")
