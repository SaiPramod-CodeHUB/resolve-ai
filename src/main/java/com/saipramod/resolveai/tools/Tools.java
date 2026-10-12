package com.saipramod.resolveai.tools;

import com.saipramod.resolveai.domain.DataStore;
import com.saipramod.resolveai.domain.Domain.Customer;
import com.saipramod.resolveai.domain.Domain.Order;
import com.saipramod.resolveai.domain.PolicyDocs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The agent's tools. Each is small, typed at the edges and safe to retry (reads) or idempotent (refund). */
public final class Tools {

    public enum Privilege { READ, WRITE }

    public record ToolCall(String name, Map<String, String> args) {
        public String signature() {
            return name + new java.util.TreeMap<>(args);
        }
    }

    public record ToolResult(boolean ok, Object data, String error, boolean retryable) {
        public static ToolResult ok(Object data) {
            return new ToolResult(true, data, null, false);
        }

        public static ToolResult notFound(String what) {
            return new ToolResult(false, null, what + " not found", false);
        }

        public static ToolResult transientError(String msg) {
            return new ToolResult(false, null, msg, true);
        }
    }

    public interface AgentTool {
        String name();

        String description();

        Privilege privilege();

        /** Simulated latency used for the virtual clock in evals. */
        long latencyMs();

        ToolResult invoke(Map<String, String> args);
    }

    public record FraudAssessment(double risk, List<String> signals) { }

    private Tools() { }

    public static Map<String, AgentTool> all(DataStore store) {
        Map<String, AgentTool> m = new LinkedHashMap<>();
        for (AgentTool t : List.of(lookupOrder(store), recentOrders(store), customerHistory(store),
                searchPolicy(), fraudCheck(store), createRefund(store))) {
            m.put(t.name(), t);
        }
        return m;
    }

    static AgentTool lookupOrder(DataStore store) {
        return tool("lookupOrder", "Fetch an order by id: item, amount, category, dates, carrier status, charge count.",
                Privilege.READ, 40, args -> store.order(args.get("orderId"))
                        .map(ToolResult::ok).orElse(ToolResult.notFound("order " + args.get("orderId"))));
    }

    static AgentTool recentOrders(DataStore store) {
        return tool("listRecentOrders", "List a customer's orders from the last 60 days (newest first).",
                Privilege.READ, 50, args -> ToolResult.ok(store.recentOrders(args.get("customerId"), 60)));
    }

    static AgentTool customerHistory(DataStore store) {
        return tool("getCustomerHistory", "Customer profile: account age, refunds in last 90 days, chargebacks.",
                Privilege.READ, 60, args -> store.customer(args.get("customerId"))
                        .map(ToolResult::ok).orElse(ToolResult.notFound("customer " + args.get("customerId"))));
    }

    static AgentTool searchPolicy() {
        return tool("searchPolicy", "Search the refund policy. Returns numbered sections to cite.",
                Privilege.READ, 80, args -> ToolResult.ok(PolicyDocs.search(args.getOrDefault("query", ""), 3)));
    }

    static AgentTool fraudCheck(DataStore store) {
        return tool("fraudCheck", "Risk score in [0,1] for refunding this customer/order, with the signals behind it.",
                Privilege.READ, 120, args -> {
                    Customer c = store.customer(args.get("customerId")).orElse(null);
                    Order o = store.order(args.get("orderId")).orElse(null);
                    if (c == null) {
                        return ToolResult.notFound("customer " + args.get("customerId"));
                    }
                    return ToolResult.ok(assess(c, o));
                });
    }

    static AgentTool createRefund(DataStore store) {
        return tool("createRefund", "Issue a refund to the original payment method. WRITE: requires authorization.",
                Privilege.WRITE, 150, args -> {
                    double amount = Double.parseDouble(args.get("amount"));
                    return ToolResult.ok(store.createRefund(args.get("orderId"), amount, args.get("reason"),
                            args.getOrDefault("approvedBy", "agent")));
                });
    }

    /** Transparent, rule-based risk model; signals are returned so a human reviewer can see why. */
    public static FraudAssessment assess(Customer c, Order o) {
        double risk = 0;
        List<String> signals = new ArrayList<>();
        if (c.refundsLast90d() >= 3) {
            risk += 0.35;
            signals.add(c.refundsLast90d() + " refunds in last 90 days");
        } else if (c.refundsLast90d() == 2) {
            risk += 0.15;
            signals.add("2 refunds in last 90 days");
        }
        if (c.chargebacks() > 0) {
            risk += 0.30;
            signals.add(c.chargebacks() + " prior chargeback(s)");
        }
        if (c.accountAgeDays() < 14) {
            risk += 0.20;
            signals.add("account is " + c.accountAgeDays() + " days old");
        }
        if (o != null && o.amount() > 400) {
            risk += 0.10;
            signals.add("high order value");
        }
        return new FraudAssessment(Math.min(1.0, Math.round(risk * 100) / 100.0), signals);
    }

    private static AgentTool tool(String name, String description, Privilege privilege, long latency,
                                  java.util.function.Function<Map<String, String>, ToolResult> fn) {
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return description; }
            public Privilege privilege() { return privilege; }
            public long latencyMs() { return latency; }
            public ToolResult invoke(Map<String, String> args) { return fn.apply(args); }
        };
    }
}
