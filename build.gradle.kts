import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

// --- One artifact, one Agent Workbench generation (the "air.*" API) -----------------------------
// The Agent Workbench API is `@Internal` and moves fast; the plugin id (`com.intellij.agent.workbench`) stays
// the same across reworks, so a Marketplace update can silently swap the API under a running IDE. History:
//   * 262.8377 and older: the pre-air `com.intellij.platform.ai.agent.*` / `agent.workbench.*` API.
//   * 262.8665.20260706 (2026.2 EAP9): everything moved under `com.intellij.air.*`, Session→Thread. Verified
//     byte-identical to the 2026.3 build (263.1174) for every type used here, so ONE binary covered both.
//   * 263.1445: `AgentThreadTerminalLaunchSpec`→`AgentThreadLaunchSpec`, `AgentThreadProvider`→`AgentId`.
//     Signatures only, same FQNs — 0.6.x spanned it with a hand-mangled second `contribute` + a compile-only
//     stub source set.
//   * 262.8665.20260723 (shipped to the 2026.2.0.1 release): the flat namespaces were split into
//     `air.backend.*` / `air.frontend.*` / `air.shared.*` layers. The launch-EP INTERFACE moved with them, and
//     a JVM class cannot implement a superinterface that doesn't exist, so spanning generations is over: as of
//     0.7.0 the seam targets this layout only, and the dual-mangle machinery + awbStub are gone. Full map:
//     docs/AWB-2026.3-COMPAT.md.
// So: re-verify the seam (javap the installed AWB jars) whenever the workbench updates, and let
// DocentSeamCheck report at runtime what a newer build broke.
//
// Source layout: AWB-free code stays in src/main; the AWB-touching code (the air.* seam) lives in src/awb.
val awbBuild = "262.8665" // floor: first build carrying the air.* API (2026.2 EAP9)

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Platform base + Agent Workbench resolution has two modes:
//
//   * Local (developer default): build against the locally-installed Rider and the installed
//     Agent Workbench plugin. Zero-download, matches the runtime exactly, and gives us
//     `com.intellij.mcpServer` (bundled only since 2025+). Enabled by setting the `riderLocalPath`
//     Gradle property (e.g. in ~/.gradle/gradle.properties or -PriderLocalPath=...) / RIDER_HOME and
//     the `agentWorkbenchPluginPath` property / AGENT_WORKBENCH_PLUGIN env var. NB: both must now point
//     at an air.* build (Rider EAP9+ / the `air-plugin` install), not the old 262.8377 `agent-workbench-plugin`.
//
//   * Download (CI, no local IDE): when those paths are unset we download the Rider build named by
//     the `riderVersion` property and resolve the Agent Workbench plugin from the JetBrains
//     Marketplace at `agentWorkbenchVersion`. NB: the workbench's since/until-build pins it to a
//     single Rider build, so `riderVersion` and `agentWorkbenchVersion` are tightly coupled — bump
//     them together (see gradle.properties). Both must be air.*-era builds (>= 262.8665).
fun propOrEnv(prop: String, env: String): String? =
    providers.gradleProperty(prop).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

val riderLocalPath: String? = propOrEnv("riderLocalPath", "RIDER_HOME")

val agentWorkbenchPluginPath: String? = propOrEnv("agentWorkbenchPluginPath", "AGENT_WORKBENCH_PLUGIN")

dependencies {
    intellijPlatform {
        if (riderLocalPath != null) {
            local(riderLocalPath)
        } else {
            // useInstaller = false is REQUIRED for Rider: the IJP plugin can't consume Rider's
            // installer distribution, so we pull the maven artifact (com.jetbrains.intellij.rider:
            // riderRD) instead. 2026.2 is still EAP, so `riderVersion` is a `-SNAPSHOT` from the
            // snapshots repo (already part of defaultRepositories()).
            rider(providers.gradleProperty("riderVersion").get()) {
                useInstaller = false
            }
        }

        // The in-IDE MCP server: lets us publish McpToolset tools the workbench's agents can call. Bundled
        // in the platform, so the mcp/ package is version-agnostic.
        bundledPlugin("com.intellij.mcpServer")

        // Agent Workbench: optional compile-time dep for its launch / MCP-wiring EPs (the air.* API). Plugin
        // id is `com.intellij.agent.workbench`. When building locally it's the installed `air-plugin`; in CI it
        // comes from the Marketplace at `agentWorkbenchVersion`. Re-verify the EP shapes (javap the jars)
        // whenever the base moves.
        if (agentWorkbenchPluginPath != null) {
            localPlugin(file(agentWorkbenchPluginPath))
        } else {
            plugin("com.intellij.agent.workbench", providers.gradleProperty("agentWorkbenchVersion").get())
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
            // platform.ai.agent.* API and would fail at load, so it's deliberately excluded. Cap at 263.* is
            // kept because the CORE review surface is platform-clean and works there — but note the AWB seam
            // is compiled against the layered 20260723 API, so on an IDE carrying an older/newer workbench the
            // seam degrades (loudly, via DocentSeamCheck) while the rest of the plugin keeps working.
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
