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

// We now build against the locally-installed Rider so the compile base aligns with the agent-workbench
// / MCP integration work. Zero-download, matches the runtime exactly, and gives us
// `com.intellij.mcpServer`, which is bundled only since 2025+ and was absent from the old IC 2024.3
// base. The platformType/platformVersion properties are now vestigial.
//
// The install location is machine-specific, so it's supplied at build time rather than hard-coded:
// set the `riderLocalPath` Gradle property (e.g. in ~/.gradle/gradle.properties, the project's
// gradle.properties, or -PriderLocalPath=...) or the RIDER_HOME environment variable.
val riderLocalPath = file(
    providers.gradleProperty("riderLocalPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("RIDER_HOME").orNull?.takeIf { it.isNotBlank() }
        ?: error(
            "No local Rider configured. Set the `riderLocalPath` Gradle property or the RIDER_HOME " +
                "environment variable to your Rider install dir (see this file's comments)."
        )
)

dependencies {
    intellijPlatform {
        local(riderLocalPath)
        // The in-IDE MCP server: lets us publish McpToolset tools the workbench's agents can call.
        bundledPlugin("com.intellij.mcpServer")
        // Agent Workbench: optional compile-time dep for its launch / MCP-wiring EPs
        // (sessionLaunchContributor, AwbMcpConfigBuilder, AgentSessionProvider, …). Installed as a
        // user plugin; MUST point at the running Rider's workbench build — the 262 API differs from
        // 261 (e.g. 261's container.McpStreamUrlProvider was removed; sessionLaunchContributor is new).
        // Current: Rider 2026.2, agent-workbench 262.7581 (platform base RD-262.7581.x). If Rider
        // updates, re-point this and re-verify the EP shapes (javap the installed jars).
        //
        // Machine-specific, like riderLocalPath above: set the `agentWorkbenchPluginPath` Gradle
        // property or the AGENT_WORKBENCH_PLUGIN environment variable to the installed plugin dir.
        localPlugin(
            file(
                providers.gradleProperty("agentWorkbenchPluginPath").orNull?.takeIf { it.isNotBlank() }
                    ?: providers.environmentVariable("AGENT_WORKBENCH_PLUGIN").orNull?.takeIf { it.isNotBlank() }
                    ?: error(
                        "No Agent Workbench plugin configured. Set the `agentWorkbenchPluginPath` Gradle " +
                            "property or the AGENT_WORKBENCH_PLUGIN environment variable to the installed " +
                            "agent-workbench-plugin dir (see this file's comments)."
                    )
            )
        )
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
val runRider by intellijPlatformTesting.runIde.registering {
    localPath = riderLocalPath
    // Rider 2026.2 (build 262) reshuffled its boot layout: `com.intellij.idea.Main` and the bootstrap
    // classes it loads (e.g. com.intellij.platform.ide.bootstrap.StartupUtil) live in jars that are NOT
    // in product-info.json's bootClassPathJarNames, so the plugin's computed launch classpath omits them
    // and the launch dies with ClassNotFoundException. Naming them individually is whack-a-mole, so put
    // the whole `lib/` on the classpath (harmless duplicates; PathClassLoader + the module repository
    // sort it out). If a future Rider update breaks this again, this is the first place to look.
    task {
        classpath += fileTree(riderLocalPath.resolve("lib")) { include("*.jar") }
        // Rider 262 also needs sun.swing.text *exported* (not just --add-opens, which product-info has)
        // for the link-time superclass check of IntelliJ's GlyphViewFix. WITHOUT it the IDE process
        // starts but the IllegalAccessError is thrown inside WelcomeFrame creation, so no window ever
        // appears ("builds successfully, nothing opens"). This export is required, not cosmetic.
        jvmArgs("--add-exports=java.desktop/sun.swing.text=ALL-UNNAMED")
    }
}
