plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

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
//     the `agentWorkbenchPluginPath` property / AGENT_WORKBENCH_PLUGIN env var. Both are
//     machine-specific, so they are supplied at build time rather than hard-coded.
//
//   * Download (CI, no local IDE): when those paths are unset we download the Rider build named by
//     the `riderVersion` property and resolve the Agent Workbench plugin from the JetBrains
//     Marketplace at `agentWorkbenchVersion`. NB: the workbench's since/until-build pins it to a
//     single Rider build, so `riderVersion` and `agentWorkbenchVersion` are tightly coupled — bump
//     them together (see gradle.properties).
//
// The platformType/platformVersion properties are vestigial (kept for reference/fallback).
val riderLocalPath: String? =
    providers.gradleProperty("riderLocalPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("RIDER_HOME").orNull?.takeIf { it.isNotBlank() }

val agentWorkbenchPluginPath: String? =
    providers.gradleProperty("agentWorkbenchPluginPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("AGENT_WORKBENCH_PLUGIN").orNull?.takeIf { it.isNotBlank() }

dependencies {
    intellijPlatform {
        if (riderLocalPath != null) {
            local(riderLocalPath)
        } else {
            // useInstaller = false is REQUIRED for Rider: the IJP plugin can't consume Rider's
            // installer distribution, so we pull the maven artifact (com.jetbrains.intellij.rider:
            // riderRD) instead. The 262 / 2026.2 platform is still EAP-only, so `riderVersion` is a
            // `-SNAPSHOT` from the snapshots repo (already part of defaultRepositories()).
            rider(providers.gradleProperty("riderVersion").get()) {
                useInstaller = false
            }
        }

        // The in-IDE MCP server: lets us publish McpToolset tools the workbench's agents can call.
        bundledPlugin("com.intellij.mcpServer")

        // Agent Workbench: optional compile-time dep for its launch / MCP-wiring EPs
        // (sessionLaunchContributor, AwbMcpConfigBuilder, AgentSessionProvider, …). The 262 API
        // differs from 261 (e.g. 261's container.McpStreamUrlProvider was removed;
        // sessionLaunchContributor is new), so the resolved build MUST match the platform base. When
        // building locally it's an installed user plugin; in CI it comes from the Marketplace. If
        // Rider updates, re-point/-version this and re-verify the EP shapes (javap the jars).
        if (agentWorkbenchPluginPath != null) {
            localPlugin(file(agentWorkbenchPluginPath))
        } else {
            plugin("com.intellij.agent.workbench", providers.gradleProperty("agentWorkbenchVersion").get())
        }

        pluginVerifier()
        zipSigner()
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
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild intentionally left open for the prototype phase.
        }
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
