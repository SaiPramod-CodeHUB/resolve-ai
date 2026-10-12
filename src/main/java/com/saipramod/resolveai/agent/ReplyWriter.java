package com.saipramod.resolveai.agent;

import org.springframework.ai.chat.client.ChatClient;

/** Final node: the customer-facing message. It explains a decision, it never makes one. */
public interface ReplyWriter {

    String write(CaseState s);

    default boolean usesLlm() {
        return false;
    }

    /** Offline replies from templates. */
    class Template implements ReplyWriter {
        @Override
        public String write(CaseState s) {
            var d = s.decision;
            String cite = s.policy != null && !s.policy.sections().isEmpty()
                    ? " (refund policy " + String.join(", ", s.policy.sections()) + ")" : "";
            return switch (d.outcome()) {
                case AUTO_REFUND -> String.format("Good news: we've issued a refund of $%.2f for %s%s. "
                        + "It should appear on your original payment method in 3-5 business days.",
                        d.amount(), s.order.id(), cite);
                case AUTO_DENY -> "Thanks for reaching out. We're not able to refund this order: " + d.reason() + cite
                        + ". If something else is wrong with it, reply and we'll take another look.";
                case NEEDS_INFO -> "We'd like to help. " + needsInfoAsk(d.reason());
                case ESCALATE -> "Thanks for your patience. A member of our support team is reviewing your case "
                        + "and will get back to you within one business day.";
            };
        }

        private static String needsInfoAsk(String reason) {
            if (reason.contains("Photo")) {
                return "Could you reply with a photo of the damage? We'll process the refund as soon as we see it.";
            }
            return "Could you reply with your order number (it looks like ORD-1234)?";
        }
    }

    /** LLM-written reply, constrained to the facts of the decision. Falls back to the template. */
    class Llm implements ReplyWriter {
        private final ChatClient chat;
        private final ReplyWriter fallback = new Template();

        public Llm(ChatClient chat) {
            this.chat = chat;
        }

        @Override
        public String write(CaseState s) {
            String facts = "decision=" + s.decision.outcome() + ", amount=" + s.decision.amount() + ", reason="
                    + s.decision.reason() + ", policySections=" + (s.policy == null ? "[]" : s.policy.sections())
                    + ", orderId=" + (s.order == null ? "unknown" : s.order.id());
            try {
                String reply = chat.prompt()
                        .system("Write a short, warm customer-support reply (max 80 words). State ONLY the decision in "
                                + "the facts; never promise a refund or amount that is not in the facts; for ESCALATE "
                                + "say a specialist will follow up within one business day. No internal rule ids.")
                        .user("Facts: " + facts + "\nCustomer wrote: " + s.message)
                        .call().content();
                s.addLlmUsage(facts + s.message, reply, 0);
                return reply == null || reply.isBlank() ? fallback.write(s) : reply;
            } catch (RuntimeException e) {
                return fallback.write(s);
            }
        }

        @Override
        public boolean usesLlm() {
            return true;
        }
    }
}
