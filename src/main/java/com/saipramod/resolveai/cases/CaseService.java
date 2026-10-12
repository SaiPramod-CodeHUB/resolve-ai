package com.saipramod.resolveai.cases;

import com.saipramod.resolveai.agent.CaseState;
import com.saipramod.resolveai.agent.ResolutionAgent;
import com.saipramod.resolveai.domain.DataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs cases through the agent, stores them and handles the human approval step. */
public class CaseService {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    public record Pricing(double inputPerMTok, double outputPerMTok) {
        public double cost(long in, long out) {
            return Math.round((in * inputPerMTok + out * outputPerMTok) / 1_000_000.0 * 1_000_000) / 1_000_000.0;
        }
    }

    private final ResolutionAgent agent;
    private final DataStore store;
    private final Pricing pricing;
    private final Map<String, CaseRecord> cases = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    public CaseService(ResolutionAgent agent, DataStore store, Pricing pricing) {
        this.agent = agent;
        this.store = store;
        this.pricing = pricing;
    }

    public CaseRecord submit(String customerId, String message) {
        String id = "CASE-" + String.format("%04d", seq.getAndIncrement());
        CaseState s = agent.run(id, customerId, message);
        CaseRecord rec = CaseRecord.from(s, pricing.cost(s.llmInputTokens(), s.llmOutputTokens()));
        cases.put(id, rec);
        audit.info("case={} customer={} outcome={} rule={} amount={} steps={} toolCalls={} refund={}",
                id, customerId, rec.outcome(), rec.rule(), rec.proposedAmount(), rec.steps(), rec.toolCalls(),
                rec.refundId());
        return rec;
    }

    public CaseRecord approve(String caseId, String reviewer, Double amountOverride, String note) {
        CaseRecord rec = pending(caseId);
        double amount = amountOverride != null ? amountOverride : rec.proposedAmount();
        if (amount <= 0) {
            throw new IllegalArgumentException("No refund amount proposed for " + caseId + "; pass one explicitly");
        }
        if (rec.orderAmount() != null && amount > rec.orderAmount() * 2) {
            throw new IllegalArgumentException("Amount exceeds twice the order value; refusing");
        }
        var refund = store.createRefund(rec.orderId(), amount, "Approved by " + reviewer + ": " + rec.reason(),
                "human:" + reviewer);
        var event = new CaseState.TraceEvent(rec.steps() + 1, rec.simulatedLatencyMs(), "HUMAN_REVIEW", "APPROVAL",
                reviewer + " approved $" + amount + " -> " + refund.refundId() + (note == null ? "" : " (" + note + ")"));
        CaseRecord updated = rec.reviewed(CaseRecord.Status.APPROVED_REFUNDED, reviewer, note, refund.refundId(), event);
        cases.put(caseId, updated);
        audit.info("case={} APPROVED by={} amount={} refund={}", caseId, reviewer, amount, refund.refundId());
        return updated;
    }

    public CaseRecord reject(String caseId, String reviewer, String note) {
        CaseRecord rec = pending(caseId);
        var event = new CaseState.TraceEvent(rec.steps() + 1, rec.simulatedLatencyMs(), "HUMAN_REVIEW", "APPROVAL",
                reviewer + " rejected" + (note == null ? "" : " (" + note + ")"));
        CaseRecord updated = rec.reviewed(CaseRecord.Status.REJECTED, reviewer, note, null, event);
        cases.put(caseId, updated);
        audit.info("case={} REJECTED by={}", caseId, reviewer);
        return updated;
    }

    private CaseRecord pending(String caseId) {
        CaseRecord rec = get(caseId);
        if (rec.status() != CaseRecord.Status.PENDING_APPROVAL) {
            throw new IllegalStateException(caseId + " is " + rec.status() + ", not pending approval");
        }
        if (rec.orderId() == null) {
            throw new IllegalStateException(caseId + " has no identified order");
        }
        return rec;
    }

    public CaseRecord get(String id) {
        CaseRecord rec = cases.get(id);
        if (rec == null) {
            throw new NoSuchElementException("No case " + id);
        }
        return rec;
    }

    public List<CaseRecord> list(CaseRecord.Status status) {
        return cases.values().stream()
                .filter(c -> status == null || c.status() == status)
                .sorted(Comparator.comparing(CaseRecord::id).reversed())
                .toList();
    }

    public Map<String, Object> metrics() {
        List<CaseRecord> all = list(null);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (CaseRecord.Status st : CaseRecord.Status.values()) {
            byStatus.put(st.name(), all.stream().filter(c -> c.status() == st).count());
        }
        long autonomous = all.stream().filter(c -> c.outcome().equals("AUTO_REFUND") || c.outcome().equals("AUTO_DENY")).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cases", all.size());
        m.put("byStatus", byStatus);
        m.put("autonomousResolutionRate", all.isEmpty() ? 0 : (double) autonomous / all.size());
        m.put("avgSteps", all.stream().mapToInt(CaseRecord::steps).average().orElse(0));
        m.put("avgCostUsd", all.stream().mapToDouble(CaseRecord::estimatedCostUsd).average().orElse(0));
        return m;
    }
}
