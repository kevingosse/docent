import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

// --- One artifact, one Air generation (the "air.*" API) ----------------------------------------
// The Air (formerly Agent Workbench) API is `@Internal` and moves fast. History:
//   * 262.8377 and older: the pre-air `com.intellij.platform.ai.agent.*` / `agent.workbench.*` API.
//   * 262.8665.20260706 (2026.2 EAP9): everything moved under `com.intellij.air.*`, Session→Thread.
//   * 263.1445: `AgentThreadTerminalLaunchSpec`→`AgentThreadLaunchSpec`, `AgentThreadProvider`→`AgentId`.
//   * 262.8665.20260723 / 262.8665.28 (Rider 2026.2.0.1): flat namespaces split into `air.backend.*` /
//     `air.frontend.*` / `air.shared.*`. The launch-EP INTERFACE moved with them, so spanning generations
//     ended (0.7.0): one seam, one generation.
//   * 263.4739 (IDEA 2026.3, Air BUNDLED in the IDE): plugin id renamed `com.intellij.agent.workbench` →
//     `com.intellij.air` (no alias), and Claude/Codex became "folded" ACP agents — the default Chat surface
//     launches them through the ACP adapter with an EMPTY terminal command, so CLI-arg injection only reaches
//     the Terminal surface. The ACP surface is served through Air's ACP EPs (`acp.mcpServerProvider`,
//     `acpPromptSupplement`) instead.
//   * 263.4825 (2026.3 nightly): `AgentPromptLaunchRequest(projectPath)` → `(workspaceId: SessionWorkspaceId,
//     projectDirectory)`.
//   * 263.4953 (2026.3 nightly): `AgentPromptLaunchRequest` gained a trailing `preallocatedThreadId` param; the
//     0.8.2 binary died with NoSuchMethodError on its synthetic default ctor (source-compatible, binary-incompatible).
//     As of 0.8.3 the seam targets THIS generation. Full map: docs/AWB-2026.3-COMPAT.md.
// So: re-verify the seam (javap the installed air-plugin jars) whenever Air or the IDE updates, and let
// DocentSeamCheck report at runtime what a newer build broke.
//
// Source layout: Air-free code stays in src/main; the Air-touching code (the air.* seam) lives in src/awb.
val awbBuild = "262.8665" // since-build floor: first build carrying the air.* API (2026.2 EAP9)

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Platform base + Air resolution has two modes:
//
//   * Local (developer default): build against a locally-installed 2026.3 IDE that BUNDLES Air (IntelliJ
//     IDEA 263.4739+; Rider 263 does not bundle it yet) and compile the seam against that bundled Air.
//     Zero-download, matches the runtime exactly, and gives us `com.intellij.mcpServer`. Enabled by the
//     `ideLocalPath` Gradle property (e.g. in ~/.gradle/gradle.properties or -PideLocalPath=...) / IDE_HOME.
//     `riderLocalPath` / RIDER_HOME is accepted as a fallback base (and still drives `runRider`).
//     To compile against a non-bundled Air install instead, point `airPluginPath` / AIR_PLUGIN at its
//     directory (the `air-plugin` folder); otherwise the IDE's bundled `com.intellij.air` is used.
//
//   * Download (CI, no local IDE): when those paths are unset we download the IntelliJ IDEA Ultimate build
//     named by the `ideaVersion` property (Air is bundled in it). NB: Air's since/until-build pins it to a
//     single IDE build, so the seam is only guaranteed against the exact build in gradle.properties.
fun propOrEnv(prop: String, env: String): String? =
    providers.gradleProperty(prop).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

val riderLocalPath: String? = propOrEnv("riderLocalPath", "RIDER_HOME")

/** The compile base: a 2026.3 IDE bundling Air (preferred), else the local Rider. */
val ideLocalPath: String? = propOrEnv("ideLocalPath", "IDE_HOME") ?: riderLocalPath

val airPluginPath: String? = propOrEnv("airPluginPath", "AIR_PLUGIN")

dependencies {
    intellijPlatform {
        if (ideLocalPath != null) {
            local(ideLocalPath)
        } else {
            intellijIdeaUltimate(providers.gradleProperty("ideaVersion").get()) {
                useInstaller = false
            }
        }

        // The in-IDE MCP server: lets us publish McpToolset tools the workbench's agents can call. Bundled
        // in the platform, so the mcp/ package is version-agnostic.
        bundledPlugin("com.intellij.mcpServer")

        // Air: optional compile-time dep for its launch / ACP EPs (the air.* API). Plugin id `com.intellij.air`,
        // bundled in 2026.3 IDEs; `airPluginPath` overrides with a standalone install. Re-verify the EP shapes
        // (javap the jars) whenever the base moves.
        if (airPluginPath != null) {
            localPlugin(file(airPluginPath))
        } else {
            bundledPlugin("com.intellij.air")
        }

        pluginVerifier()
        zipSigner()
    }
}

// Add the AWB (air.*) seam to the main source set. Shared code (src/main) references its classes by package +
// name, and the seam is compiled against the AWB dependency resolved above. The Kotlin sources go on the Kotlin
// source set (so the Kotlin compiler picks them up); the resources go on the base source set (so
// processResources bundles META-INF/docent-awb.xml).
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir("src/awb/kotlin")
    }
}
sourceSets {
    named("main") {
        resources.srcDir("src/awb/resources")
    }
}

intellijPlatform {
    // Pure-Kotlin plugin: no Java @NotNull weaving or GUI .form files to instrument.
    // Disabling avoids the instrumentCode task (and its JDK-layout sensitivity).
    instrumentCode = false

    // No custom Settings UI yet, so don't spin up a headless IDE to index them.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // Floor = 262.8665 (2026.2 EAP9, first build with the air.* API); anything below has the old
            // platform.ai.agent.* API and would fail at load, so it's deliberately excluded. The CORE review
            // surface is platform-clean and works across the whole range. The Air seam is gated on the
            // `com.intellij.air` plugin id (263.x bundled Air): on a 262 IDE (Air still `com.intellij.agent.workbench`)
            // it simply doesn't load; on a 263 IDE with a different Air build it degrades loudly via DocentSeamCheck.
            sinceBuild = awbBuild
            untilBuild = "263.*"
        }
    }

    // Cross-IDE guarantee: the plugin is platform-clean (depends only on com.intellij.modules.platform,
    // with the MCP / Agent Workbench seams gated behind optional dependencies), so the core review
    // surface must load on any IntelliJ-based IDE. We compile against Rider, which has the whole API
    // surface on the classpath, so the compiler can't catch an accidental Rider-only call. The Plugin
    // Verifier does: it checks the built plugin against other products and flags any non-portable API.
    pluginVerification {
        // Agent Workbench is an OPTIONAL dependency: the awb/ seam is gated behind <depends optional>
        // and only loads in an IDE that has the matching AWB build. The IDEs we verify against either
        // lack AWB or bundle a DIFFERENT build, so its references look unresolved here even though they
        // resolve at runtime where AWB is present. externalPrefixes treats the package as provided (so
        // it isn't verified or cascaded); the ignore file additionally suppresses member-level
        // mismatches against a bundled-but-older AWB. The core (everything else) is still verified.
        // NB: the other optional dep, com.intellij.mcpServer, is bundled in IDEA/PyCharm so it
        // resolves fine and needs no exclusion.
        // The AWB seam now lives entirely under com.intellij.air.* (some support classes still under
        // com.intellij.agent.workbench). Both are AWB-provided, so treat them as external — they resolve at
        // runtime in an IDE that has the workbench, but not against the verifier's bare IDEs. (The old
        // com.intellij.platform.ai.agent root is kept as a harmless superset for older ignored-problem entries.)
        externalPrefixes = listOf(
            "com.intellij.agent.workbench",
            "com.intellij.platform.ai.agent",
            "com.intellij.air",
        )
        ignoredProblemsFile = layout.projectDirectory.file("verifier-ignored-problems.txt")
        // We also knowingly call internal/experimental platform + AWB EPs, so fail only on the real
        // portability breakers, not on internal/experimental/deprecated-API notes.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        // The verifier IDE list is intentionally EMPTY for now. Our since-build floor (262.8665) is ABOVE
        // every public non-Rider release (2026.2 is still EAP), so any downloadable IDEA/PyCharm build the
        // verifier could use is below the floor and would be (correctly) rejected as incompatible — a false
        // negative about portability, not a real one. With no `ides {}` entries `verifyPlugin` has nothing to
        // run against and effectively no-ops. Re-add a real >= 262.8665 non-Rider build once 2026.2 ships
        // publicly, to restore the cross-IDE core-portability check.
    }
}

kotlin {
    jvmToolchain(21)
}

// --- Rider navigation check ----------------------------------------------------------------------
// Launch the locally installed Rider (no Rider SDK download) with this plugin loaded, to verify
// that embedded editor cells receive the .NET backend's navigation.
//   Run:  ./gradlew runRider
// then open a solution in the launched Rider and control-click a symbol inside an embedded cell.
// Only registered when a local Rider is configured — there's nothing to launch in the CI download
// mode, and `buildPlugin` (the CI entry point) doesn't need this task.
if (riderLocalPath != null) {
val riderLocalDir = file(riderLocalPath)
val runRider by intellijPlatformTesting.runIde.registering {
    localPath = riderLocalDir
    // Rider 2026.2 (build 262) reshuffled its boot layout: `com.intellij.idea.Main` and the bootstrap
    // classes it loads (e.g. com.intellij.platform.ide.bootstrap.StartupUtil) live in jars that are NOT
    // in product-info.json's bootClassPathJarNames, so the plugin's computed launch classpath omits them
    // and the launch dies with ClassNotFoundException. Naming them individually is whack-a-mole, so put
    // the whole `lib/` on the classpath (harmless duplicates; PathClassLoader + the module repository
    // sort it out). If a future Rider update breaks this again, this is the first place to look.
    task {
        classpath += fileTree(riderLocalDir.resolve("lib")) { include("*.jar") }
        // Rider 262 also needs sun.swing.text *exported* (not just --add-opens, which product-info has)
        // for the link-time superclass check of IntelliJ's GlyphViewFix. WITHOUT it the IDE process
        // starts but the IllegalAccessError is thrown inside WelcomeFrame creation, so no window ever
        // appears ("builds successfully, nothing opens"). This export is required, not cosmetic.
        jvmArgs("--add-exports=java.desktop/sun.swing.text=ALL-UNNAMED")
    }
}
}

// --- IntelliJ navigation check -------------------------------------------------------------------
// Launch a locally-installed IntelliJ IDEA with this plugin, to verify Ctrl-click in the unified diff
// resolves for Java/Kotlin — where the platform has real PSI — which isolates the unified-diff nav
// bridge from Rider's backend-only resolution (C# goes through the ReSharper backend, not PSI).
//   Run:  ./gradlew runIdea -PideaLocalPath="C:/Users/<you>/AppData/Local/Programs/IntelliJ IDEA"
// (or set ideaLocalPath in ~/.gradle/gradle.properties, or the IDEA_HOME env var). Use an IDEA whose
// build matches the compile base (262) so the platform API we call resolves at runtime.
val ideaLocalPath: String? =
    providers.gradleProperty("ideaLocalPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("IDEA_HOME").orNull?.takeIf { it.isNotBlank() }
if (ideaLocalPath != null) {
val ideaLocalDir = file(ideaLocalPath)
val runIdea by intellijPlatformTesting.runIde.registering {
    localPath = ideaLocalDir
    // Same build-262 boot-layout workaround as runRider above (whole lib/ on the classpath + the
    // sun.swing.text export); harmless on IDEA and required if it shares Rider's 262 boot quirks.
    task {
        classpath += fileTree(ideaLocalDir.resolve("lib")) { include("*.jar") }
        jvmArgs("--add-exports=java.desktop/sun.swing.text=ALL-UNNAMED")
    }
}
}
