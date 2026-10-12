package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.Domain.IssueType;
import com.saipramod.resolveai.domain.Domain.Outcome;

/**
 * Auto-resolve vs. escalate rules. Ordered, first match wins, and every decision records
 * the rule id so the operator dashboard and the audit log can show why.
 */
public class DecisionPolicy {

    public record Config(double autoRefundLimit, double riskThreshold, double minConfidence) {
        public static Config defaults() {
            return new Config(200.0, 0.5, 0.6);
        }
    }

    public record Decision(Outcome outcome, String rule, String reason, double amount) { }

    private final Config config;

    public DecisionPolicy(Config config) {
        this.config = config;
    }

    public Decision decide(CaseState s) {
        var intake = s.intake;
        if (s.guardTripped != null) {
            return escalate("R0", "Safety guard: " + s.guardTripped);
        }
        if (intake.suspiciousInstructions()) {
            return escalate("R1", "Message contains instructions aimed at the agent");
        }
        if (intake.humanRequested()) {
            return escalate("R2", "Customer requested a human or threatened a dispute");
        }
        if (intake.issue() == IssueType.OTHER || intake.multipleIssues() || intake.confidence() < config.minConfidence()) {
            return escalate("R3", "Ambiguous request (issue=" + intake.issue() + ", confidence=" + intake.confidence()
                    + (intake.multipleIssues() ? ", multiple issues" : "") + ")");
        }
        if (s.order == null) {
            return new Decision(Outcome.NEEDS_INFO, "R4", "Could not identify the order; ask the customer for the order id", 0);
        }
        var p = s.policy;
        switch (p.eligibility()) {
            case NEEDS_INFO -> {
                return new Decision(Outcome.NEEDS_INFO, "R5", p.reason(), 0);
            }
            case MANUAL_REVIEW -> {
                return escalate("R6", "Policy requires manual review: " + p.reason());
            }
            case INELIGIBLE -> {
                return new Decision(Outcome.AUTO_DENY, "R10", p.reason(), 0);
            }
            case ELIGIBLE -> {
                if (s.fraud == null) {
                    return escalate("R7", "Fraud check unavailable; never auto-refund without a risk score");
                }
                if (s.fraud.risk() >= config.riskThreshold()) {
                    return escalate("R7", "Fraud risk " + s.fraud.risk() + " >= " + config.riskThreshold()
                            + " " + s.fraud.signals());
                }
                if (p.amount() > config.autoRefundLimit()) {
                    return new Decision(Outcome.ESCALATE, "R8", "Refund $" + p.amount() + " exceeds auto limit $"
                            + config.autoRefundLimit(), p.amount());
                }
                return new Decision(Outcome.AUTO_REFUND, "R9", p.reason(), p.amount());
            }
            default -> throw new IllegalStateException();
        }
    }

    private Decision escalate(String rule, String reason) {
        return new Decision(Outcome.ESCALATE, rule, reason, 0);
    }

    public Config config() {
        return config;
    }
}
