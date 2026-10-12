package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.tools.Tools.ToolCall;

import java.util.Optional;

import static com.saipramod.resolveai.agent.EvidencePlanner.call;

/**
 * Deterministic planner: identify the order (by id, or by the customer's only recent
 * order), then customer history, fraud check and policy search. It is what the LLM
 * planner falls back to, and the reference the LLM planner is evaluated against.
 */
public class RuleEvidencePlanner implements EvidencePlanner {

    @Override
    public Optional<ToolCall> next(CaseState s) {
        String orderId = s.intake.orderId();
        if (s.order == null && !s.orderLookupFailed) {
            if (orderId != null) {
                return untried(s, call("lookupOrder", "orderId", orderId));
            }
            if (s.customerId != null && s.recentOrders == null) {
                return untried(s, call("listRecentOrders", "customerId", s.customerId));
            }
            if (s.recentOrders != null && s.recentOrders.size() == 1) {
                return untried(s, call("lookupOrder", "orderId", s.recentOrders.get(0).id()));
            }
            return Optional.empty();   // ambiguous: several or no recent orders -> ask the customer
        }
        if (s.order == null) {
            return Optional.empty();
        }
        String customerId = s.order.customerId();
        if (s.customer == null) {
            Optional<ToolCall> c = untried(s, call("getCustomerHistory", "customerId", customerId));
            if (c.isPresent()) {
                return c;
            }
        }
        if (s.fraud == null) {
            Optional<ToolCall> c = untried(s, call("fraudCheck", "customerId", customerId, "orderId", s.order.id()));
            if (c.isPresent()) {
                return c;
            }
        }
        if (s.policyHits == null) {
            return untried(s, call("searchPolicy", "query", s.intake.issue().name().replace('_', ' ').toLowerCase()
                    + " " + s.order.category().name().toLowerCase().replace('_', ' ')));
        }
        return Optional.empty();
    }

    private static Optional<ToolCall> untried(CaseState s, ToolCall c) {
        return s.attemptedCalls.contains(c.signature()) ? Optional.empty() : Optional.of(c);
    }

    @Override
    public String name() {
        return "rule-based";
    }
}
