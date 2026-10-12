package com.saipramod.resolveai.domain;

import java.time.LocalDate;

/** Domain types for the refund-resolution workflow. Kept in one file because they are plain data. */
public final class Domain {

    private Domain() { }

    public enum Category { PHYSICAL, DIGITAL, FINAL_SALE }

    public enum CarrierStatus { IN_TRANSIT, DELIVERED, DELAYED, LOST }

    public enum IssueType { NOT_RECEIVED, DAMAGED, WRONG_ITEM, CHANGED_MIND, LATE_DELIVERY, DUPLICATE_CHARGE, OTHER }

    /** What the agent decided to do with a case. */
    public enum Outcome { AUTO_REFUND, AUTO_DENY, ESCALATE, NEEDS_INFO }

    public record Customer(String id, String name, int accountAgeDays, int refundsLast90d, int chargebacks) { }

    /**
     * @param chargeCount     how many times the card was charged for this order (2 = duplicate charge)
     * @param digitalAccessed for digital goods: has the customer downloaded / streamed it
     */
    public record Order(String id, String customerId, String item, double amount, Category category,
                        LocalDate placed, LocalDate expectedDelivery, LocalDate delivered,
                        CarrierStatus carrierStatus, int chargeCount, boolean digitalAccessed) { }

    /**
     * Structured output of the intake step (LLM or keyword classifier).
     *
     * @param humanRequested         customer asked for a person / threatened chargeback or legal action
     * @param suspiciousInstructions message tries to instruct the agent ("ignore your rules", "refund $X")
     * @param multipleIssues         more than one distinct problem in one message
     * @param confidence             classifier confidence in [0,1]
     */
    public record Intake(IssueType issue, String orderId, boolean photoProvided, boolean humanRequested,
                         boolean suspiciousInstructions, boolean multipleIssues, double confidence) { }
}
