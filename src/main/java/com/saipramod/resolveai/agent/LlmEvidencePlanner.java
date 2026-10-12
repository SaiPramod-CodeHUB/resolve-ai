package com.saipramod.resolveai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saipramod.resolveai.tools.Tools.AgentTool;
import com.saipramod.resolveai.tools.Tools.Privilege;
import com.saipramod.resolveai.tools.Tools.ToolCall;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ReAct-style planner: the model sees what is known so far and the READ tools, and picks
 * the next call as JSON. It never sees WRITE tools; refunds are executed by the decision
 * node only. Malformed output falls back to the rule planner; repeated calls are caught by
 * the agent's loop guard.
 */
public class LlmEvidencePlanner implements EvidencePlanner {

    private final ChatClient chat;
    private final Map<String, AgentTool> tools;
    private final EvidencePlanner fallback;
    private final ObjectMapper json = new ObjectMapper();

    public LlmEvidencePlanner(ChatClient chat, Map<String, AgentTool> tools, EvidencePlanner fallback) {
        this.chat = chat;
        this.tools = tools;
        this.fallback = fallback;
    }

    @Override
    public Optional<ToolCall> next(CaseState s) {
        StringBuilder toolList = new StringBuilder();
        tools.values().stream().filter(t -> t.privilege() == Privilege.READ)
                .forEach(t -> toolList.append("- ").append(t.name()).append(": ").append(t.description()).append('\n'));
        String prompt = """
                You gather evidence for a refund case. Pick ONE next tool call, or finish.
                Needed before finishing: the order, the customer's history, a fraud check, and the relevant policy.
                Do not repeat a call that is listed as already done.
                Tools:
                %s
                Known so far:
                customerId=%s, intake=%s
                order=%s
                customer=%s, fraud=%s, policyFound=%s
                Already done: %s
                Reply with JSON only: {"tool":"<name>","args":{...}} or {"tool":"done"}
                """.formatted(toolList, s.customerId, s.intake, s.order, s.customer, s.fraud,
                s.policyHits != null, s.attemptedCalls);
        try {
            String reply = chat.prompt().user(prompt).call().content();
            s.addLlmUsage(prompt, reply, 0);
            JsonNode node = json.readTree(extractJson(reply));
            String tool = node.path("tool").asText();
            if ("done".equals(tool)) {
                return Optional.empty();
            }
            if (!tools.containsKey(tool)) {
                return fallback.next(s);
            }
            Map<String, String> args = new LinkedHashMap<>();
            node.path("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
            return Optional.of(new ToolCall(tool, args));
        } catch (Exception e) {
            return fallback.next(s);
        }
    }

    static String extractJson(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        return start >= 0 && end > start ? reply.substring(start, end + 1) : reply;
    }

    @Override
    public String name() {
        return "llm ReAct (fallback: rule-based)";
    }

    @Override
    public boolean usesLlm() {
        return true;
    }
}
