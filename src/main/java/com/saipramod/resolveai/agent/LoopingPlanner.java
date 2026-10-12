package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.tools.Tools.ToolCall;

import java.util.Optional;
import java.util.Random;

/**
 * Chaos-testing planner that imitates a common LLM-agent failure: with probability p it
 * re-issues its previous call instead of making progress. Used by the eval to show the
 * loop guard turns a would-be infinite loop into a bounded, escalated case.
 */
public class LoopingPlanner implements EvidencePlanner {

    private final EvidencePlanner delegate;
    private final double repeatProbability;
    private final Random random;
    private ToolCall last;

    public LoopingPlanner(EvidencePlanner delegate, double repeatProbability, long seed) {
        this.delegate = delegate;
        this.repeatProbability = repeatProbability;
        this.random = new Random(seed);
    }

    @Override
    public Optional<ToolCall> next(CaseState state) {
        if (last != null && state.attemptedCalls.contains(last.signature()) && random.nextDouble() < repeatProbability) {
            return Optional.of(last);
        }
        Optional<ToolCall> next = delegate.next(state);
        last = next.orElse(null);
        return next;
    }

    @Override
    public String name() {
        return "looping(" + repeatProbability + ")";
    }
}
