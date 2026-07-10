package com.intellij.air.shared.core.thread;

/**
 * COMPILE-ONLY STUB — never shipped (see the awbStub source set in build.gradle.kts).
 *
 * Agent Workbench 263.1445 renamed {@code AgentThreadTerminalLaunchSpec} to {@code AgentThreadLaunchSpec}
 * (same fields). The Docent still compiles against the 262.8665 AWB, where the new name doesn't exist, but
 * {@code DocentLaunchContributor} must declare a method whose JVM descriptor references the NEW class so it
 * satisfies the 263.1445 {@code AgentThreadLaunchContributor} interface at runtime. This empty placeholder
 * exists purely to put that name on the compile classpath; at runtime the FQN resolves to the real AWB class,
 * and all member access goes through reflection (so this stub deliberately declares NO members — nothing to
 * drift out of sync).
 */
@SuppressWarnings("unused")
public final class AgentThreadLaunchSpec {
    private AgentThreadLaunchSpec() {
    }
}
