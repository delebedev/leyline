# Shared Java/Maven helpers — imported by root and module justfiles

export JAVA_HOME := env("JAVA_HOME", `
    sdkman="$HOME/.sdkman/candidates/java/current"; \
    if [ -x "$sdkman/bin/java" ]; then echo "$sdkman"; \
    else /usr/libexec/java_home -v 17 2>/dev/null || echo ""; fi
`)

# Common mvn flags
mvn_skip  := "-DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true"  # compile-only
mvn_quiet := "-Dcheckstyle.skip=true -Denforcer.skip=true"              # test runs

[private]
check-java:
    @test -x "{{JAVA_HOME}}/bin/java" || { echo "JAVA_HOME not set. Install JDK 17." >&2; exit 1; }
