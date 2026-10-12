package com.saipramod.resolveai.cases;

import com.saipramod.resolveai.agent.CaseState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What the API and operator dashboard see for a case: decision, evidence and the full audit trail. */
public record CaseRecord(
        String id,
        String customerId,
        String message,
        Status status,
        String outcome,
        String rule,
        String reason,
        double proposedAmount,
        String issue,
        double intakeConfidence,
        String orderId,
        String item,
        Double orderAmount,
        Double fraudRisk,
        List<String> fraudSignals,
        List<String> policySections,
        String policyReason,
        String reply,
        String refundId,
        int steps,
        int toolCalls,
        int toolFailures,
        long simulatedLatencyMs,
        long inputTokens,
        long outputTokens,
        double estimatedCostUsd,
        List<CaseState.TraceEvent> trace,
        Instant createdAt,
        String reviewedBy,
        String reviewNote) {

    public enum Status { RESOLVED_REFUNDED, RESOLVED_DENIED, AWAITING_CUSTOMER, PENDING_APPROVAL, APPROVED_REFUNDED, REJECTED }

    public static CaseRecord from(CaseState s, double costUsd) {
        Status status = switch (s.decision().outcome()) {
            case AUTO_REFUND -> Status.RESOLVED_REFUNDED;
            case AUTO_DENY -> Status.RESOLVED_DENIED;
            case NEEDS_INFO -> Status.AWAITING_CUSTOMER;
            case ESCALATE -> Status.PENDING_APPROVAL;
        };
        double proposed = s.decision().amount() > 0 ? s.decision().amount()
                : s.policy() != null ? s.policy().amount() : 0;
        return new CaseRecord(s.caseId(), s.customerId(), s.message(), status, s.decision().outcome().name(),
                s.decision().rule(), s.decision().reason(), proposed,
                s.intake().issue().name(), s.intake().confidence(),
                s.order() == null ? s.intake().orderId() : s.order().id(),
                s.order() == null ? null : s.order().item(),
                s.order() == null ? null : s.order().amount(),
                s.fraud() == null ? null : s.fraud().risk(),
                s.fraud() == null ? List.of() : s.fraud().signals(),
                s.policy() == null ? List.of() : s.policy().sections(),
                s.policy() == null ? null : s.policy().reason(),
                s.reply(), s.refundId(), s.steps(), s.toolCalls(), s.toolFailures(), s.elapsedMs(),
                s.llmInputTokens(), s.llmOutputTokens(), costUsd, new ArrayList<>(s.trace()), Instant.now(), null, null);
    }

    public CaseRecord reviewed(Status newStatus, String reviewer, String note, String newRefundId,
                               CaseState.TraceEvent event) {
        List<CaseState.TraceEvent> t = new ArrayList<>(trace);
        t.add(event);
        return new CaseRecord(id, customerId, message, newStatus, outcome, rule, reason, proposedAmount, issue,
                intakeConfidence, orderId, item, orderAmount, fraudRisk, fraudSignals, policySections, policyReason,
                reply, newRefundId, steps, toolCalls, toolFailures, simulatedLatencyMs, inputTokens, outputTokens,
                estimatedCostUsd, t, createdAt, reviewer, note);
    }
}
