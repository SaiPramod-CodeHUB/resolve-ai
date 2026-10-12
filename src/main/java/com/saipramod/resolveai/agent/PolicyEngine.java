package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.Domain.CarrierStatus;
import com.saipramod.resolveai.domain.Domain.Category;
import com.saipramod.resolveai.domain.Domain.Intake;
import com.saipramod.resolveai.domain.Domain.IssueType;
import com.saipramod.resolveai.domain.Domain.Order;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deterministic encoding of {@link com.saipramod.resolveai.domain.PolicyDocs}. Money
 * decisions are made here, not by the LLM: the model extracts facts, code applies policy,
 * and every decision cites the section it used.
 */
public class PolicyEngine {

    public enum Eligibility { ELIGIBLE, INELIGIBLE, NEEDS_INFO, MANUAL_REVIEW }

    public record PolicyDecision(Eligibility eligibility, double amount, List<String> sections, String reason) { }

    public PolicyDecision evaluate(Intake intake, Order order, LocalDate today) {
        if (order == null) {
            return new PolicyDecision(Eligibility.NEEDS_INFO, 0, List.of(), "Order could not be identified");
        }
        IssueType issue = intake.issue();
        long sinceDelivery = order.delivered() == null ? -1 : ChronoUnit.DAYS.between(order.delivered(), today);
        return switch (issue) {
            case DUPLICATE_CHARGE -> order.chargeCount() > 1
                    ? eligible(order.amount() * (order.chargeCount() - 1), "P7",
                    "Ledger shows " + order.chargeCount() + " charges")
                    : ineligible("P7", "Ledger shows a single charge");
            case NOT_RECEIVED -> notReceived(order, today);
            case DAMAGED -> {
                if (order.delivered() == null) {
                    yield review("P3", "Damage reported on an order not marked delivered");
                }
                if (sinceDelivery > 30) {
                    yield ineligible("P3", "Reported " + sinceDelivery + " days after delivery (limit 30)");
                }
                yield intake.photoProvided()
                        ? eligible(order.amount(), "P3", "Damage reported with photo within 30 days")
                        : new PolicyDecision(Eligibility.NEEDS_INFO, 0, List.of("P3"), "Photo of the damage required");
            }
            case WRONG_ITEM -> order.delivered() != null && sinceDelivery <= 30
                    ? eligible(order.amount(), "P4", "Wrong item reported within 30 days")
                    : ineligible("P4", "Outside 30-day window or not delivered");
            case CHANGED_MIND -> changedMind(order, today, sinceDelivery);
            case LATE_DELIVERY -> {
                if (order.category() == Category.FINAL_SALE) {
                    yield ineligible("P5", "Final-sale items are excluded from late-delivery goodwill");
                }
                long late = order.delivered() == null ? 0 : ChronoUnit.DAYS.between(order.expectedDelivery(), order.delivered());
                yield late > 5
                        ? eligible(round(order.amount() * 0.15), "P8", "Delivered " + late + " days late")
                        : ineligible("P8", order.delivered() == null ? "Not delivered yet" : "Delivered " + late + " days late (needs > 5)");
            }
            case OTHER -> review("", "Issue type not covered by policy");
        };
    }

    private PolicyDecision notReceived(Order order, LocalDate today) {
        if (order.category() == Category.DIGITAL) {
            return review("P6", "Digital order reported as not received");
        }
        long overdue = ChronoUnit.DAYS.between(order.expectedDelivery(), today);
        if (order.carrierStatus() == CarrierStatus.LOST) {
            return eligible(order.amount(), "P2", "Carrier marked the parcel LOST");
        }
        if (order.carrierStatus() == CarrierStatus.DELIVERED) {
            return review("P2", "Carrier says DELIVERED but customer reports not received");
        }
        return overdue > 7
                ? eligible(order.amount(), "P2", "Parcel " + overdue + " days past expected delivery")
                : ineligible("P2", "Still in transit, " + Math.max(0, overdue) + " days past expected date (refund after 7)");
    }

    private PolicyDecision changedMind(Order order, LocalDate today, long sinceDelivery) {
        if (order.category() == Category.FINAL_SALE) {
            return ineligible("P5", "Final-sale item");
        }
        if (order.category() == Category.DIGITAL) {
            long sincePurchase = ChronoUnit.DAYS.between(order.placed(), today);
            return !order.digitalAccessed() && sincePurchase <= 14
                    ? eligible(order.amount(), "P6", "Digital item never accessed, within 14 days")
                    : ineligible("P6", order.digitalAccessed() ? "Digital item already accessed" : "More than 14 days since purchase");
        }
        if (order.delivered() == null) {
            return review("P1", "Change of mind before delivery: cancel instead of refund");
        }
        return sinceDelivery <= 30
                ? eligible(order.amount(), "P1", "Change of mind within 30 days of delivery")
                : ineligible("P1", sinceDelivery + " days since delivery (limit 30)");
    }

    private static PolicyDecision eligible(double amount, String section, String reason) {
        return new PolicyDecision(Eligibility.ELIGIBLE, round(amount), List.of(section), reason);
    }

    private static PolicyDecision ineligible(String section, String reason) {
        return new PolicyDecision(Eligibility.INELIGIBLE, 0, List.of(section), reason);
    }

    private static PolicyDecision review(String section, String reason) {
        return new PolicyDecision(Eligibility.MANUAL_REVIEW, 0, section.isEmpty() ? List.of() : List.of(section), reason);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
