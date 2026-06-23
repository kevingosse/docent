package com.kevingosse.docent.awb

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry

// Sets the AWB registry key that makes the agent workbench pass the IDE MCP URL to Claude
// via --mcp-config. Without this, the IDE MCP tools are invisible to workbench-launched agents.
// The key defaults to false; our plugin's purpose is exactly this integration, so we enable it.
class EnableAwbDirectHttp : ProjectActivity {
    override suspend fun execute(project: Project) {
        runCatching {
            Registry.get("agent.workbench.mcp.use.direct.http").setValue(true)
        }
    }
}
