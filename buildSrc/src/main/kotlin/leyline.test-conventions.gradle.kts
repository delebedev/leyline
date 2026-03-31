import leyline.build.configureTestDefaults

plugins {
    jacoco
}

tasks.named<Test>("test") {
    configureTestDefaults()
}

jacoco {
    toolVersion = "0.8.12"
}

// Don't depend on test — CI/recipes control test execution order.
// Report generates from whatever .exec files exist in build/jacoco/.
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
    // Pick up exec data from all test tasks (test, testGate, testIntegration, etc.)
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
}
