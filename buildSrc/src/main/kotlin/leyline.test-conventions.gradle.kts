import leyline.build.configureTestDefaults

plugins {
    id("org.gradle.test-retry")
}

tasks.named<Test>("test") {
    configureTestDefaults()
}
