import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

// --- Dual build variants (Agent Workbench 262 vs 2026.3 build 263) -------------------------------
// The SAME plugin id ships as two build variants, selected by the `awbTarget` Gradle property
// (`-PawbTarget=262|263`, default 262 — see gradle.properties). The 2026.3 release reworked the whole
// Agent Workbench API surface (every type moved to the `com.intellij.air.*` namespace AND renamed
// Session→Thread, and both launch EPs were renamed) while KEEPING the plugin id `com.intellij.agent.workbench`.
// A single compiled binary therefore can't satisfy both (it would have to implement two EP interfaces that
// never coexist, and the plugin id is identical so we can't gate a config-file by <depends>). So we compile
// the AWB seam against its target's coordinates and publish two artifacts; the Marketplace serves the right
// one per IDE build via since/until-build (262 capped at 262.*, 263 open from build 263).
//
// Source layout: everything AWB-free stays in src/main (shared, compiled into BOTH variants); the AWB-touching
// code lives per-variant in src/awb262 and src/awb263 with the SAME package + class names, so the shared code
// referencing them compiles unchanged against either. Exactly one of those trees is added to the main source
// set below, per awbTarget.
val awbTarget: String =
    providers.gradleProperty("awbTarget").orNull?.takeIf { it.isNotBlank() } ?: "262"
require(awbTarget == "262" || awbTarget == "263") {
    "awbTarget must be '262' or '263' (was '$awbTarget')"
}

group = providers.gradleProperty("pluginGroup").get()
// Marketplace requires a DISTINCT version string per artifact of the same plugin id, so we suffix the target
// (JetBrains' documented convention for build-specific variants): e.g. 0.5.0-262 / 0.5.0-263.
version = "${providers.gradleProperty("pluginVersion").get()}-$awbTarget"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Platform base + Agent Workbench resolution has two modes, and is now ALSO per-target (262 vs 263):
//
//   * Local (developer default): build against the locally-installed Rider and the installed
//     Agent Workbench plugin. Zero-download, matches the runtime exactly, and gives us
//     `com.intellij.mcpServer` (bundled only since 2025+). Enabled by setting the `riderLocalPath`
//     Gradle property (e.g. in ~/.gradle/gradle.properties or -PriderLocalPath=...) / RIDER_HOME and
//     the `agentWorkbenchPluginPath` property / AGENT_WORKBENCH_PLUGIN env var.
//
//   * Download (CI, no local IDE): when those paths are unset we download the Rider build named by
//     the `riderVersion` property and resolve the Agent Workbench plugin from the JetBrains
//     Marketplace at `agentWorkbenchVersion`. NB: the workbench's since/until-build pins it to a
//     single Rider build, so `riderVersion` and `agentWorkbenchVersion` are tightly coupled — bump
//     them together (see gradle.properties).
//
// Per-target: the 262 target uses the existing property/env names; the 263 target uses their *263 twins.
// `riderLocalPath`/RIDER_HOME is a 262 Rider install, so it applies to the 262 target ONLY — the 263 target
// reads `riderLocalPath263`/RIDER_HOME_263 (for a future local 263 IDE) or downloads `riderVersion263`.
// The platformType/platformVersion properties are vestigial (kept for reference/fallback).
fun propOrEnv(prop: String, env: String): String? =
    providers.gradleProperty(prop).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

val riderLocalPath: String? =
    if (awbTarget == "263") propOrEnv("riderLocalPath263", "RIDER_HOME_263")
    else propOrEnv("riderLocalPath", "RIDER_HOME")

val agentWorkbenchPluginPath: String? =
    if (awbTarget == "263") propOrEnv("agentWorkbenchPluginPath263", "AGENT_WORKBENCH_PLUGIN_263")
    else propOrEnv("agentWorkbenchPluginPath", "AGENT_WORKBENCH_PLUGIN")

val riderVersionProp = if (awbTarget == "263") "riderVersion263" else "riderVersion"

dependencies {
    intellijPlatform {
        if (riderLocalPath != null) {
            local(riderLocalPath)
        } else {
            // useInstaller = false is REQUIRED for Rider: the IJP plugin can't consume Rider's
            // installer distribution, so we pull the maven artifact (com.jetbrains.intellij.rider:
            // riderRD) instead. Both 2026.2 (262) and 2026.3 (263) platforms are still EAP/snapshot, so
            // `riderVersion*` is a `-SNAPSHOT` from the snapshots repo (already part of defaultRepositories()).
            rider(providers.gradleProperty(riderVersionProp).get()) {
                useInstaller = false
            }
        }

        // The in-IDE MCP server: lets us publish McpToolset tools the workbench's agents can call. Bundled
        // in the platform on BOTH 262 and 263 (the mcpServer seam is byte-for-byte identical — see the API map),
        // so the mcp/ package is version-agnostic and needs no per-target handling.
        bundledPlugin("com.intellij.mcpServer")

        // Agent Workbench: optional compile-time dep for its launch / MCP-wiring EPs. The type surface
        // differs wholesale 262↔263 (the 263 rework), so the awb262/awb263 source set is compiled against the
        // matching coordinates. Plugin id is unchanged (`com.intellij.agent.workbench`) on both. When building
        // locally it's an installed user plugin; in CI it comes from the Marketplace. Re-verify the EP shapes
        // (javap the jars) whenever the base moves.
        if (agentWorkbenchPluginPath != null) {
            localPlugin(file(agentWorkbenchPluginPath))
        } else if (awbTarget == "263") {
            // Resolution order for 263: local path (handled above), else a non-empty `agentWorkbenchVersion263`,
            // else FAIL FAST. AWB 263 is published on NO public channel yet (verified 2026-07-06: newest
            // Marketplace AWB is 262.8665.20260706; the 263 build is not bundled in riderRD-2026.3-SNAPSHOT either), so
            // `agentWorkbenchVersion263` is intentionally empty and this normally throws with instructions —
            // rather than silently resolving a wrong (262) AWB build against the 263 base.
            val v = providers.gradleProperty("agentWorkbenchVersion263").orNull?.takeIf { it.isNotBlank() }
                ?: error(
                    "awbTarget=263 but no Agent Workbench 263 build is available. AWB 263 is not " +
                        "published on any public channel yet (verified 2026-07-06). To build the 263 variant, set " +
                        "`agentWorkbenchPluginPath263` (or the AGENT_WORKBENCH_PLUGIN_263 env var) to a locally-" +
                        "installed AWB 263 plugin directory; or, once it is published, set `agentWorkbenchVersion263` " +
                        "to its Marketplace version. Until then the src/awb263 sources cannot be compiled.",
                )
            plugin("com.intellij.agent.workbench", v)
        } else {
            plugin("com.intellij.agent.workbench", providers.gradleProperty("agentWorkbenchVersion").get())
        }

        pluginVerifier()
        zipSigner()
    }
}

// Add the selected variant's AWB source tree to the main source set. Shared code (src/main) references the
// per-variant classes by their (identical) package + name, so it compiles against whichever tree is added here.
// The Kotlin sources go on the Kotlin source set (so the Kotlin compiler picks them up); the resources go on
// the base source set (so processResources bundles the variant's META-INF/docent-awb.xml).
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir("src/awb$awbTarget/kotlin")
    }
}
sourceSets {
    named("main") {
        resources.srcDir("src/awb$awbTarget/resources")
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
            // Per-target build range. Now that a 263 artifact exists, the 262 artifact is CAPPED at 262.* so the
            // Marketplace serves each IDE its matching variant (an uncapped 262 would otherwise also match 263,
            // where its old EP + compiled AWB references are dead). 263 starts at build 263 and is left open.
            if (awbTarget == "263") {
                sinceBuild = "263"
                // untilBuild intentionally left open (263 is the newest line).
            } else {
                sinceBuild = "252"
                untilBuild = "262.*"
            }
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
        // AWB split its API across two roots after the 2026.2 upgrade: some classes still live under
        // com.intellij.agent.workbench (prompt.core, sessions.state, chat) while session/launch types
        // moved to com.intellij.platform.ai.agent. The 2026.3 rework added a THIRD root,
        // com.intellij.air.* (used by the awb263 variant). All are AWB-provided, so treat all three as
        // external (only the compiled-in variant references one set; the prefixes are harmless supersets).
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
        // The verifier IDE list (2026.1.3 = build 261) is only meaningful for the 262 target: 261 ≥ our
        // 262-target since-build 252 and is a real published build to check core portability against. For the
        // 263 target there are NO public 263 IDE builds yet, and a 261 IDE is BELOW the 263 since-build so the
        // verifier would (correctly) reject the plugin as incompatible — a false negative about portability. So
        // we only populate `ides {}` for 262; on 263 `verifyPlugin` has no IDEs to run against and effectively
        // no-ops until a public 263 build exists (revisit then).
        if (awbTarget == "262") {
            ides {
                // Released, non-Rider products at the latest public build (261 ≥ our since-build 252).
                // If the core ever reaches for a Rider-only or too-new platform API, this turns red.
                // Since 2025.3 (253) the Community/Ultimate downloads are unified, so the old
                // IntellijIdeaCommunity / PyCharmCommunity coordinates are gone — use the merged types.
                create(IntelliJPlatformType.IntellijIdea, "2026.1.3")
                create(IntelliJPlatformType.PyCharm, "2026.1.3")
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// --- Rider navigation check ----------------------------------------------------------------------
// NB: the runRider / runIdea dev tasks are 262-oriented — they launch the locally-installed 262 Rider/IDEA
// (riderLocalPath/ideaLocalPath). Under `-PawbTarget=263` they are meaningless until a local 263 IDE exists:
// `riderLocalPath` resolves to null on the 263 target (it's a 262 install), so runRider isn't even registered.
//
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
