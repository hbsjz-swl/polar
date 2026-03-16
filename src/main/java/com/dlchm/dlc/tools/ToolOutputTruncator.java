package com.dlchm.dlc.tools;

public class ToolOutputTruncator {

    private final int maxChars;

    public ToolOutputTruncator(int maxChars) {
        this.maxChars = maxChars;
    }

    public String truncate(String output) {
        if (output == null) return "";
        if (output.length() <= maxChars) return output;
        int half = maxChars / 2;
        int truncated = output.length() - maxChars;
        return output.substring(0, half)
                + "\n\n... [" + truncated + " chars truncated] ...\n\n"
                + output.substring(output.length() - half);
    }
}
