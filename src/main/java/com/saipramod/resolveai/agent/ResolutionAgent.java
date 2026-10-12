package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.DataStore;
import com.saipramod.resolveai.domain.Domain.Customer;
import com.saipramod.resolveai.domain.Domain.Order;
import com.saipramod.resolveai.domain.Domain.Outcome;
import com.saipramod.resolveai.domain.PolicyDocs;
import com.saipramod.resolveai.tools.Tools.FraudAssessment;
import com.saipramod.resolveai.tools.Tools.Privilege;
import com.saipramod.resolveai.tools.Tools.ToolCall;
import com.saipramod.resolveai.tools.Tools.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow graph, written as explicit nodes instead of a free-running agent loop:
 *
 * <pre>
 *   INTAKE ──> GATHER_EVIDENCE (planner + tools, bounded) ──> CHECK_POLICY ──> DECIDE
 *                                                                           ├─ AUTO_REFUND ─> EXECUTE ─┐
 *                                                                           ├─ AUTO_DENY ──────────────┤
 *                                                                           ├─ NEEDS_INFO ─────────────┼─> RESPOND
 *                                                                           └─ ESCALATE ─> approval q ─┘
 * </pre>
 *
 * The LLM is used where language is the problem (intake, planning, the reply). Money moves
 * only through {@link DecisionPolicy} + {@link PolicyEngine}, and only the EXECUTE node may
 * call a WRITE tool.
 */
public class ResolutionAgent {

    /**
     * @param maxSteps   hard cap on steps per case (intake + tool calls + policy + decide + execute + respond)
     * @param maxRepeats repeated tool calls the agent will skip (and re-plan) before giving up on the case
     */
    public record Limits(int maxSteps, int maxRepeats) {
        public static Limits defaults() {
            return new Limits(14, 2);
        }
    }

    /** Assumed latency of an LLM call when running offline (keeps simulated latency honest). */
    static final long SIMULATED_INTAKE_MS = 700;
    static final long SIMULATED_REPLY_MS = 900;

    private final IntakeClassifier classifier;
    private final EvidencePlanner planner;
    private final ToolExecutor executor;
    private final PolicyEngine policyEngine;
    private final DecisionPolicy decisionPolicy;
    private final ReplyWriter replyWriter;
    private final DataStore store;
    private final Limits limits;

    public ResolutionAgent(IntakeClassifier classifier, EvidencePlanner planner, ToolExecutor executor,
                           PolicyEngine policyEngine, DecisionPolicy decisionPolicy, ReplyWriter replyWriter,
                           DataStore store, Limits limits) {
        this.classifier = classifier;
        this.planner = planner;
        this.executor = executor;
        this.policyEngine = policyEngine;
        this.decisionPolicy = decisionPolicy;
        this.replyWriter = replyWriter;
        this.store = store;
        this.limits = limits;
    }

    public CaseState run(String caseId, String customerId, String message) {
        CaseState s = new CaseState(caseId, customerId, message);
        intake(s);
        gatherEvidence(s);
        checkPolicy(s);
        decide(s);
        execute(s);
        respond(s);
        return s;
    }

    private void intake(CaseState s) {
        s.steps++;
        long t0 = System.nanoTime();
        s.intake = classifier.classify(s.message);
        long measured = (System.nanoTime() - t0) / 1_000_000;
        s.addLlmUsage(LlmIntakeClassifier.SYSTEM + s.message, String.valueOf(s.intake),
                classifier.usesLlm() ? measured : SIMULATED_INTAKE_MS);
        s.log("INTAKE", "LLM", s.intake.toString());
    }

    private void gatherEvidence(CaseState s) {
        while (true) {
            if (s.steps >= limits.maxSteps()) {
                trip(s, "step budget of " + limits.maxSteps() + " exhausted");
                return;
            }
            long t0 = System.nanoTime();
            Optional<ToolCall> next = planner.next(s);
            if (planner.usesLlm()) {
                s.elapsedMs += (System.nanoTime() - t0) / 1_000_000;
            }
            if (next.isEmpty()) {
                return;
            }
            ToolCall call = next.get();
            s.steps++;
            var tool = executor.tools().get(call.name());
            if (tool != null && tool.privilege() == Privilege.WRITE) {
                trip(s, "planner tried to call WRITE tool " + call.name());
                return;
            }
            if (!s.attemptedCalls.add(call.signature())) {
                // Loop guard: refuse the duplicate, let the planner try again, give up after maxRepeats.
                s.repeatsBlocked++;
                if (s.repeatsBlocked > limits.maxRepeats()) {
                    trip(s, "loop detected: " + s.repeatsBlocked + " repeated calls, last " + call.signature());
                    return;
                }
                s.log("GATHER_EVIDENCE", "GUARD", "blocked repeat of " + call.signature()
                        + " (" + s.repeatsBlocked + "/" + limits.maxRepeats() + "), re-planning");
                continue;
            }
            apply(s, call, executor.execute(call, s, false));
        }
    }

    private void apply(CaseState s, ToolCall call, ToolResult r) {
        switch (call.name()) {
            case "lookupOrder" -> {
                if (r.ok()) {
                    Order o = (Order) r.data();
                    if (s.customerId != null && !s.customerId.equals(o.customerId())) {
                        // Possible account takeover or social engineering: never act on someone else's order.
                        s.orderLookupFailed = true;
                        trip(s, o.id() + " belongs to another customer");
                    } else {
                        s.order = o;
                    }
                } else {
                    s.orderLookupFailed = true;
                }
            }
            case "listRecentOrders" -> {
                if (r.ok()) {
                    @SuppressWarnings("unchecked")
                    List<Order> orders = (List<Order>) r.data();
                    s.recentOrders = orders;
                }
            }
            case "getCustomerHistory" -> {
                if (r.ok()) {
                    s.customer = (Customer) r.data();
                }
            }
            case "fraudCheck" -> {
                if (r.ok()) {
                    s.fraud = (FraudAssessment) r.data();
                }
            }
            case "searchPolicy" -> {
                if (r.ok()) {
                    @SuppressWarnings("unchecked")
                    List<PolicyDocs.Hit> hits = (List<PolicyDocs.Hit>) r.data();
                    s.policyHits = hits;
                }
            }
            default -> { }
        }
    }

    private void checkPolicy(CaseState s) {
        s.steps++;
        s.policy = policyEngine.evaluate(s.intake, s.order, store.today());
        s.log("CHECK_POLICY", "DECISION", s.policy.eligibility() + " $" + s.policy.amount() + " "
                + s.policy.sections() + " " + s.policy.reason());
    }

    private void decide(CaseState s) {
        s.steps++;
        s.decision = decisionPolicy.decide(s);
        s.log("DECIDE", "DECISION", s.decision.outcome() + " via " + s.decision.rule() + ": " + s.decision.reason());
    }

    private void execute(CaseState s) {
        if (s.decision.outcome() == Outcome.AUTO_REFUND) {
            s.steps++;
            ToolResult r = executor.execute(new ToolCall("createRefund", Map.of(
                    "orderId", s.order.id(),
                    "amount", String.valueOf(s.decision.amount()),
                    "reason", s.decision.reason(),
                    "approvedBy", "agent:" + s.decision.rule())), s, true);
            if (r.ok()) {
                s.refundId = ((DataStore.RefundRecord) r.data()).refundId();
                s.log("EXECUTE", "REFUND", s.refundId + " $" + s.decision.amount());
            } else {
                s.decision = new DecisionPolicy.Decision(Outcome.ESCALATE, "R11",
                        "Refund execution failed: " + r.error(), s.decision.amount());
                s.log("EXECUTE", "ESCALATION", s.decision.reason());
            }
        } else if (s.decision.outcome() == Outcome.ESCALATE) {
            s.log("EXECUTE", "ESCALATION", "queued for human review: " + s.decision.reason());
        }
    }

    private void respond(CaseState s) {
        s.steps++;
        long t0 = System.nanoTime();
        s.reply = replyWriter.write(s);
        if (!replyWriter.usesLlm()) {
            s.addLlmUsage("reply facts " + s.decision + s.message, s.reply, SIMULATED_REPLY_MS);
        } else {
            s.elapsedMs += (System.nanoTime() - t0) / 1_000_000;
        }
        s.log("RESPOND", "LLM", s.reply);
    }

    private static void trip(CaseState s, String why) {
        s.guardTripped = why;
        s.log("GATHER_EVIDENCE", "GUARD", why);
    }

    public String describe() {
        return "intake=" + classifier.name() + ", planner=" + planner.name();
    }
}
