package com.saipramod.resolveai.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The refund policy as the agent sees it: numbered sections that can be searched and cited.
 * {@link com.saipramod.resolveai.agent.PolicyEngine} encodes the same rules deterministically,
 * so the citation shown to the customer and the rule actually applied cannot drift apart.
 */
public final class PolicyDocs {

    public static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("P1", "Change of mind: physical items can be refunded within 30 days of delivery. "
                + "Refund is the item price. Final-sale and digital items are excluded.");
        SECTIONS.put("P2", "Not received: if the carrier marks a parcel LOST, or it is DELAYED more than 7 days past "
                + "the expected delivery date, refund in full. Parcels still in transit within 7 days of the expected "
                + "date are not yet eligible. Parcels the carrier marks DELIVERED but the customer did not receive "
                + "require manual investigation.");
        SECTIONS.put("P3", "Damaged or defective: full refund within 30 days of delivery when the customer provides "
                + "a photo of the damage. Without a photo, ask the customer for one. Applies to final-sale items too.");
        SECTIONS.put("P4", "Wrong item received: full refund within 30 days of delivery. Applies to final-sale items too.");
        SECTIONS.put("P5", "Final sale: final-sale items are not refundable for change of mind or late delivery.");
        SECTIONS.put("P6", "Digital goods: refundable within 14 days only if never downloaded or streamed.");
        SECTIONS.put("P7", "Duplicate charge: if the payment ledger shows the order was charged more than once, "
                + "refund every extra charge in full.");
        SECTIONS.put("P8", "Late delivery: if a parcel arrived more than 5 days after the expected date, offer a "
                + "goodwill refund of 15% of the order value.");
        SECTIONS.put("P9", "Authority limits: agents may refund up to $200 per order automatically. Larger amounts, "
                + "elevated fraud risk, legal or chargeback threats and requests for a human require escalation.");
    }

    private PolicyDocs() { }

    public record Hit(String id, String text) { }

    /** Keyword search over sections; this is what the agent's searchPolicy tool returns. */
    public static List<Hit> search(String query, int k) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        SECTIONS.forEach((id, text) -> {
            String t = text.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                if (term.length() > 2 && t.contains(term)) {
                    score++;
                }
            }
            scored.add(Map.entry(id, score));
        });
        scored.sort((a, b) -> b.getValue() - a.getValue());
        List<Hit> out = new ArrayList<>();
        for (var e : scored) {
            if (out.size() >= k || e.getValue() == 0) {
                break;
            }
            out.add(new Hit(e.getKey(), SECTIONS.get(e.getKey())));
        }
        return out;
    }
}
