package com.dlchm.dlc.tools;

/** Internal signal used to pause a tool call until a human decides. */
public class ApprovalRequiredException extends RuntimeException {
    private final String requestId;
    private final String toolName;
    private final String summary;

    public ApprovalRequiredException(String requestId, String toolName, String summary) {
        super("Approval required for " + toolName);
        this.requestId = requestId;
        this.toolName = toolName;
        this.summary = summary;
    }

    public String requestId() { return requestId; }
    public String toolName() { return toolName; }
    public String summary() { return summary; }
}
