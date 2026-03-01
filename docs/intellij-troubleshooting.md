# IntelliJ IDEA Troubleshooting (Maven + Kotlin)

## kotlinx-serialization "Unresolved reference: serializer"

IDEA doesn't auto-detect the `kotlinx-serialization` compiler plugin from `kotlin-maven-plugin` config. Gradle gets this for free; Maven needs manual setup.

**Fix:** Project Structure → Modules → select Kotlin facet (e.g. forge-web, forge-nexus) → "Additional command line parameters":

```
-Xplugin=/Users/denis/.kotlin-plugins/ks.jar
```

The jar comes from Maven cache. To set up:

```bash
mkdir -p ~/.kotlin-plugins
cp ~/.m2/repository/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/<VERSION>/kotlin-serialization-compiler-plugin-<VERSION>.jar ~/.kotlin-plugins/ks.jar
```

Replace `<VERSION>` with the Kotlin version in `pom.xml` (e.g. `2.2.20`). Must match — a bundled IDEA jar from a different Kotlin version causes `AbstractMethodError`.

Apply to every module that uses `@Serializable`.

**Why a short path?** IDEA truncates long paths in the compiler params field.

## `${revision}` / "Could not find artifact forge:forge:pom:${revision}"

The root pom uses Maven CI-friendly versioning (`${revision}` resolved by `flatten-maven-plugin`). When IDEA runs Maven on a submodule, flatten doesn't run and `${revision}` stays literal, then gets cached as a failed lookup.

**Fix 1:** `.mvn/maven.config` includes `-Drevision=2.0.10-SNAPSHOT` so all Maven invocations resolve it.

**Fix 2:** If the error persists, purge the cached failure from the per-worktree local repo:

```bash
trash .m2-local/forge/forge/'${revision}'
just install-upstream
```

## "Source root doesn't exist: src/main/java"

Boilerplate `<sourceDir>` entries in `kotlin-maven-plugin` config referencing `src/main/java` or `src/test/java` when the module is pure Kotlin. Safe to remove from the pom — only keep `src/main/kotlin` and `src/test/kotlin`.

## forge-nexus can't resolve forge-web

forge-nexus depends on forge-web. If IDEA resolves it from the local Maven repo instead of as a module, the installed pom must have `${revision}` resolved (see above). To install forge-web with flattened pom:

```bash
mvn flatten:flatten install -pl forge-web -DskipTests -Drevision=2.0.10-SNAPSHOT
```

Better: ensure both modules are loaded from the root pom so IDEA uses module deps directly. Check Project Structure → Modules → forge-nexus → Dependencies — forge-web should show as a module (folder icon), not a Maven jar.

## General tips

- **Open the root `pom.xml`** as the project, not individual submodule poms.
- **Don't delegate build to Maven** — use IDEA's own build. Settings → Build Tools → Maven → Runner → uncheck "Delegate IDE build/run actions to Maven". IDEA's compiler resolves module deps directly.
- **`just install-upstream`** installs upstream (Java) modules to the per-worktree `.m2-local/` repo. Run after cloning or when upstream deps change.
- **Invalidate Caches** (File → Invalidate Caches → Invalidate and Restart) when IDEA gets confused after config changes.
