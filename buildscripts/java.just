# Shared Java helpers — imported by root justfile
# JAVA_HOME is needed for serve/CLI targets that launch JVM directly.
# Gradle-based targets (build, test, fmt) auto-provision via toolchain.

export JAVA_HOME := env("JAVA_HOME", `
    sdkman="$HOME/.sdkman/candidates/java/current"; \
    if [ -x "$sdkman/bin/java" ]; then echo "$sdkman"; \
    else /usr/libexec/java_home -v 17 2>/dev/null || echo ""; fi
`)

[private]
check-java:
    @test -x "{{JAVA_HOME}}/bin/java" || { echo "JAVA_HOME not set. Install JDK 17 or let Gradle auto-provision: ./gradlew build" >&2; exit 1; }
