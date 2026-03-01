# --- Stage 1: Build forge engine JARs (cached unless forge src changes) ---
FROM maven:3.9-eclipse-temurin-17 AS upstream
WORKDIR /build/forge

# Copy POMs first for layer caching (Maven validates reactor modules exist)
COPY forge/pom.xml .
COPY forge/.mvn/ .mvn/
COPY forge/forge-core/pom.xml forge-core/pom.xml
COPY forge/forge-game/pom.xml forge-game/pom.xml
COPY forge/forge-ai/pom.xml forge-ai/pom.xml
COPY forge/forge-gui/pom.xml forge-gui/pom.xml
# Reactor stubs — Maven needs these POMs to exist even if we don't build them
COPY forge/forge-gui-desktop/pom.xml forge-gui-desktop/pom.xml
COPY forge/forge-gui-mobile/pom.xml forge-gui-mobile/pom.xml
COPY forge/forge-gui-mobile-dev/pom.xml forge-gui-mobile-dev/pom.xml
COPY forge/forge-gui-ios/pom.xml forge-gui-ios/pom.xml
COPY forge/forge-gui-android/pom.xml forge-gui-android/pom.xml
COPY forge/forge-installer/pom.xml forge-installer/pom.xml
COPY forge/forge-nexus/pom.xml forge-nexus/pom.xml
COPY forge/forge-lda/pom.xml forge-lda/pom.xml
COPY forge/adventure-editor/pom.xml adventure-editor/pom.xml
COPY forge/forge-web/pom.xml forge-web/pom.xml

# Download dependencies (cached unless POMs change)
RUN mvn -pl forge-core,forge-game,forge-ai,forge-gui -am dependency:go-offline \
    -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true -q || true

# Copy upstream source (changes only when forge submodule is updated)
COPY forge/forge-core/src/ forge-core/src/
COPY forge/forge-game/src/ forge-game/src/
COPY forge/forge-ai/src/ forge-ai/src/
COPY forge/forge-gui/src/ forge-gui/src/

# Install: parent POM + upstream modules with flatten for ${revision} resolution
RUN mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install -N \
    -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true -q && \
    mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install \
    -pl forge-core,forge-game,forge-ai,forge-gui \
    -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true -q

# --- Stage 2: Gradle build of leyline ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

# Gradle wrapper + build config (layer cache — only invalidates when build files change)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY buildSrc/ buildSrc/

# Forge JARs from stage 1
COPY --from=upstream /build/forge/.m2-local/ forge/.m2-local/

# Pre-fetch dependencies (cached unless build files change)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Proto files
COPY proto/ proto/
COPY src/main/proto/ src/main/proto/

# Application source
COPY src/main/kotlin/ src/main/kotlin/
COPY src/main/resources/ src/main/resources/

# Build: sync proto + compile + package distribution
RUN ./gradlew syncProto installDist --no-daemon -q

# --- Stage 3: Runtime ---
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Application distribution (launch script + all JARs)
COPY --from=build /build/build/install/leyline/ .

# Game resources (card scripts — large media dirs excluded via .dockerignore)
COPY forge/forge-gui/res/ forge-gui/res/

# Playtest config defaults (decks are embedded in forge-gui/res)
COPY playtest.toml .
COPY decks/ decks/

EXPOSE 30010 30003 9443 8090

# Memory limit (Netty access flags baked into launch script via applicationDefaultJvmArgs)
ENV JAVA_OPTS="-Xmx384m"

CMD ["bin/leyline"]
