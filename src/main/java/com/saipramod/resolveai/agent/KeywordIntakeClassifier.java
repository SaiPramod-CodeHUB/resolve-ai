package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.Domain.IssueType;
import com.saipramod.resolveai.domain.Domain.Intake;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline stand-in for the LLM intake step: phrase lists plus regexes. It is deliberately
 * simple, so the eval shows where a rules-based front end breaks (paraphrases, slang,
 * mixed issues) and what an LLM classifier has to beat.
 */
public class KeywordIntakeClassifier implements IntakeClassifier {

    private static final Pattern ORDER_ID = Pattern.compile(
            "(?i)\\bORD[-\\s#]?(\\d{3,6})\\b|\\border\\s*(?:#|no\\.?|number|id)?\\s*:?\\s*#?(\\d{4,6})\\b|#(\\d{4,6})\\b");

    private static final Map<IssueType, List<String>> PHRASES = new EnumMap<>(Map.of(
            IssueType.NOT_RECEIVED, List.of("never arrived", "not arrived", "hasn't arrived", "has not arrived",
                    "didn't arrive", "did not arrive", "never came", "never received", "not received",
                    "haven't received", "have not received", "still waiting", "no package", "missing package",
                    "where is my", "lost", "not delivered", "didn't get", "never got", "nothing at my door",
                    "not at my door", "wasn't there"),
            IssueType.DAMAGED, List.of("broken", "damaged", "cracked", "defective", "doesn't work", "does not work",
                    "stopped working", "smashed", "shattered", "dented", "torn", "faulty", "dead on arrival"),
            IssueType.WRONG_ITEM, List.of("wrong item", "wrong size", "wrong color", "wrong colour", "not what i ordered",
                    "different item", "sent me the wrong", "wrong product", "wrong one"),
            IssueType.CHANGED_MIND, List.of("changed my mind", "don't need", "do not need", "no longer need",
                    "don't want", "want to return", "return it", "unwanted", "don't like", "didn't like"),
            IssueType.LATE_DELIVERY, List.of("arrived late", "came late", "days late", "showed up late", "took forever",
                    "too long to arrive", "delivered late", "way past"),
            IssueType.DUPLICATE_CHARGE, List.of("charged twice", "double charged", "double-charged", "duplicate charge",
                    "two charges", "billed twice", "charged two times", "charged me twice")));

    private static final Pattern PHOTO = Pattern.compile("(?i)\\b(photo|picture|pic|attached|image|screenshot)s?\\b");
    private static final Pattern HUMAN = Pattern.compile(
            "(?i)lawyer|attorney|legal action|\\bsue\\b|chargeback|dispute (it )?with my bank|bank dispute|"
                    + "speak to (a |an )?(human|manager|person|supervisor)|real person|escalate this");
    private static final Pattern SUSPICIOUS = Pattern.compile(
            "(?i)ignore (all |any |your |the |previous |prior )*(rules|instructions|policy|policies)|"
                    + "you are now|system prompt|developer mode|override|refund (me )?\\$\\d{3,}");

    @Override
    public Intake classify(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        Map<IssueType, Integer> hits = new EnumMap<>(IssueType.class);
        PHRASES.forEach((issue, phrases) -> {
            int n = (int) phrases.stream().filter(m::contains).count();
            if (n > 0) {
                hits.put(issue, n);
            }
        });
        IssueType issue = IssueType.OTHER;
        double confidence = 0.3;
        boolean multiple = hits.size() > 1;
        if (!hits.isEmpty()) {
            issue = hits.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            confidence = multiple ? 0.55 : 0.9;
        }
        return new Intake(issue, orderId(message), PHOTO.matcher(message).find(), HUMAN.matcher(message).find(),
                SUSPICIOUS.matcher(message).find(), multiple, confidence);
    }

    static String orderId(String message) {
        Matcher mm = ORDER_ID.matcher(message);
        if (!mm.find()) {
            return null;
        }
        for (int g = 1; g <= mm.groupCount(); g++) {
            if (mm.group(g) != null) {
                return "ORD-" + mm.group(g);
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "keyword (offline)";
    }
}
