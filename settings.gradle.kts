plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "leyline"
include("account")
include("domain")
include("engine")
include("frontdoor")
include("matchdoor")
include("webdoor")
include("tools:detekt-rules")
