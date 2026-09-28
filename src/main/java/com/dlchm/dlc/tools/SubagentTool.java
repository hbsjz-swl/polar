package com.dlchm.dlc.tools;

import com.dlchm.dlc.agent.SubagentManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Exposes isolated child-agent delegation to the model/tool loop. */
public class SubagentTool {
    private final SubagentManager manager;

    public SubagentTool(SubagentManager manager) {
        this.manager = manager;
    }

    @Tool(name = "delegate_task", description = "Delegate an independent bounded task to a child Polar agent. The child has a separate session and returns a summary.")
    public String delegateTask(
            @ToolParam(description = "Self-contained task for the child agent") String prompt,
            @ToolParam(required = false, description = "Maximum seconds to wait") Integer timeoutSeconds) {
        return manager.delegate(prompt, timeoutSeconds);
    }
}
