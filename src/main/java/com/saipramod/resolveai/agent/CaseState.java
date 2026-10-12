package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.Domain.Customer;
import com.saipramod.resolveai.domain.Domain.Intake;
import com.saipramod.resolveai.domain.Domain.Order;
import com.saipramod.resolveai.domain.PolicyDocs;
import com.saipramod.resolveai.tools.Tools.FraudAssessment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Short-term memory for one case. Every node reads and writes this object, so evidence
 * gathered early (the order, the risk score) is available to later steps without
 * re-calling tools, and the full trace is available for audit and the operator UI.
 */
public class CaseState {

    public record TraceEvent(int step, long atMs, String node, String type, String detail) { }

    final String caseId;
    final String customerId;
    final String message;

    Intake intake;
    Order order;
    boolean orderLookupFailed;
    List<Order> recentOrders;
    Customer customer;
    FraudAssessment fraud;
    List<PolicyDocs.Hit> policyHits;
    PolicyEngine.PolicyDecision policy;
    DecisionPolicy.Decision decision;
    String reply;
    String refundId;
    String guardTripped;

    final List<TraceEvent> trace = new ArrayList<>();
    final Set<String> completedCalls = new HashSet<>();
    final Set<String> attemptedCalls = new HashSet<>();
    int steps;
    int repeatsBlocked;
    int toolCalls;
    int toolSuccesses;
    int toolFailures;
    long elapsedMs;
    long llmInputTokens;
    long llmOutputTokens;

    public CaseState(String caseId, String customerId, String message) {
        this.caseId = caseId;
        this.customerId = customerId;
        this.message = message;
    }

    void log(String node, String type, String detail) {
        trace.add(new TraceEvent(steps, elapsedMs, node, type, detail));
    }

    void addLlmUsage(String prompt, String completion, long latencyMs) {
        llmInputTokens += estimateTokens(prompt);
        llmOutputTokens += estimateTokens(completion);
        elapsedMs += latencyMs;
    }

    /** ~4 characters per token is the usual rule of thumb for English text. */
    static long estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }

    public String caseId() { return caseId; }
    public String customerId() { return customerId; }
    public String message() { return message; }
    public Intake intake() { return intake; }
    public Order order() { return order; }
    public Customer customer() { return customer; }
    public FraudAssessment fraud() { return fraud; }
    public List<PolicyDocs.Hit> policyHits() { return policyHits; }
    public PolicyEngine.PolicyDecision policy() { return policy; }
    public DecisionPolicy.Decision decision() { return decision; }
    public String reply() { return reply; }
    public String refundId() { return refundId; }
    public String guardTripped() { return guardTripped; }
    public List<TraceEvent> trace() { return trace; }
    public int steps() { return steps; }
    public int repeatsBlocked() { return repeatsBlocked; }
    public int toolCalls() { return toolCalls; }
    public int toolSuccesses() { return toolSuccesses; }
    public int toolFailures() { return toolFailures; }
    public long elapsedMs() { return elapsedMs; }
    public long llmInputTokens() { return llmInputTokens; }
    public long llmOutputTokens() { return llmOutputTokens; }
    public boolean orderLookupFailed() { return orderLookupFailed; }
    public List<Order> recentOrders() { return recentOrders; }
}
