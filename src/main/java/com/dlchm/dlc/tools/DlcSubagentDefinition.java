package com.dlchm.dlc.tools;

import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;

/** Markdown-defined specialist that runs inside DLC's bounded child-session manager. */
public record DlcSubagentDefinition(String name, String description, String instructions,
                                    SubagentReference reference) implements SubagentDefinition {
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getKind() {
        return DlcSubagentResolver.KIND;
    }

    @Override
    public SubagentReference getReference() {
        return reference;
    }
}
