package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.tools.Tools.AgentTool;
import com.saipramod.resolveai.tools.Tools.Privilege;
import com.saipramod.resolveai.tools.Tools.ToolCall;
import com.saipramod.resolveai.tools.Tools.ToolResult;

import java.util.Map;
import java.util.Random;

/**
 * Runs a tool call with the guarantees the agent loop relies on:
 * privilege check (WRITE tools only when the decision node authorised them), bounded
 * retries with exponential backoff for transient errors, optional fault injection for
 * chaos testing, and a trace entry for every attempt.
 */
public class ToolExecutor {

    public static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 200;

    private final Map<String, AgentTool> tools;
    private final double failureRate;
    private final Random random;

    public ToolExecutor(Map<String, AgentTool> tools, double failureRate, long seed) {
        this.tools = tools;
        this.failureRate = failureRate;
        this.random = new Random(seed);
    }

    public ToolResult execute(ToolCall call, CaseState state, boolean writeAuthorized) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            state.log("tools", "TOOL_ERROR", "unknown tool " + call.name());
            state.toolFailures++;
            return new ToolResult(false, null, "unknown tool " + call.name(), false);
        }
        if (tool.privilege() == Privilege.WRITE && !writeAuthorized) {
            state.log("tools", "GUARD", "blocked unauthorised WRITE call " + call.signature());
            return new ToolResult(false, null, "WRITE tool requires authorisation", false);
        }
        state.toolCalls++;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            state.elapsedMs += tool.latencyMs();
            ToolResult result = injectFault() ? ToolResult.transientError("503 upstream timeout (injected)")
                    : safeInvoke(tool, call);
            if (result.ok()) {
                state.toolSuccesses++;
                state.completedCalls.add(call.signature());
                state.log("tools", "TOOL_CALL", call.signature() + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                return result;
            }
            if (!result.retryable()) {
                state.toolFailures++;
                state.completedCalls.add(call.signature());   // a definitive answer, e.g. not found
                state.log("tools", "TOOL_ERROR", call.signature() + " -> " + result.error());
                return result;
            }
            long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
            state.log("tools", "TOOL_RETRY", call.signature() + " -> " + result.error()
                    + (attempt < MAX_ATTEMPTS ? ", retrying in " + backoff + " ms" : ", giving up"));
            if (attempt < MAX_ATTEMPTS) {
                state.elapsedMs += backoff;
            }
        }
        state.toolFailures++;
        return ToolResult.transientError("retries exhausted for " + call.name());
    }

    private boolean injectFault() {
        return failureRate > 0 && random.nextDouble() < failureRate;
    }

    private static ToolResult safeInvoke(AgentTool tool, ToolCall call) {
        try {
            return tool.invoke(call.args());
        } catch (RuntimeException e) {
            return new ToolResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    public Map<String, AgentTool> tools() {
        return tools;
    }
}
