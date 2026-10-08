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

    @Tool(name = "delegate_task", description = """
            Delegate one self-contained, bounded subtask to a child DLC agent and get its summary back.

            Use it when a task splits into 2+ independent work items that do not depend on each other \
            (e.g. auditing several files, researching separate topics, triaging independent failures), \
            to overlap the work instead of doing it serially.

            The child starts with a blank context: it cannot see this conversation, the user's original \
            wording, or anything you already learned. So the prompt MUST restate every fact, path, \
            constraint and expected output format it needs. "Look into the thing we discussed" fails; \
            "Read src/main/java/com/example/Foo.java and list every method that swallows an exception" works.

            The child has no browser tools and cannot request human approval, and it cannot delegate \
            further. Keep browser work and approval-gated commands in the main session. The call is \
            synchronous and blocks until the child returns or the timeout elapses — you will get its \
            final summary as the tool result, and nothing until then.""")
    public String delegateTask(
            @ToolParam(description = "Self-contained brief for the child agent: full context, exact task, "
                    + "constraints, and the exact output format you want back") String prompt,
            @ToolParam(required = false, description = "Maximum seconds to wait for the child") Integer timeoutSeconds) {
        return manager.delegate(prompt, timeoutSeconds);
    }
}
