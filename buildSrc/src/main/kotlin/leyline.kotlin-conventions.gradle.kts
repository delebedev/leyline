import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("org.jetbrains.kotlin.plugin.power-assert")
}

// The forge submodule publishes its jars to a local repo instead of a remote one.
// Content-filtered to the "forge" group so it never gets consulted for anything else.
repositories {
    maven {
        url = uri("${rootProject.projectDir}/forge/.m2-local")
        content {
            includeGroup("forge")
        }
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertTrue",
            "kotlin.test.assertFalse",
            "kotlin.test.assertNull",
            "kotlin.test.assertEquals",
        )
}
