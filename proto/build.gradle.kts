import leyline.build.SyncProtoTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.protobuf)
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

val syncProto =
    tasks.register<SyncProtoTask>("syncProto") {
        description = "Generate messages.proto from upstream submodule + rename map"
        sedFile.set(rootProject.layout.projectDirectory.file("proto/rename-map.sed"))
        upstream.set(rootProject.layout.projectDirectory.file("proto/upstream/messages.proto"))
        outputFile.set(layout.projectDirectory.file("src/main/proto/messages.proto"))
    }

tasks.named("extractProto") {
    dependsOn(syncProto)
}

protobuf {
    protoc {
        artifact =
            if (System.getProperty("os.name").lowercase().contains("win") &&
                (System.getProperty("os.arch") == "aarch64" || System.getProperty("os.arch") == "arm64")
            ) {
                "com.google.protobuf:protoc:3.25.5:windows-x86_64@exe"
            } else {
                "com.google.protobuf:protoc:3.25.5"
            }
    }
}

dependencies {
    // Generated public signatures expose protobuf runtime types, so protobuf-java
    // is part of this module's API surface.
    api(libs.protobuf.java)
    implementation(libs.kotlin.stdlib)
}
