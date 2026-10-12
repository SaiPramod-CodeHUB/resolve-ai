package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.domain.Domain.Intake;

/** First node: free-text customer message -> structured intake. */
public interface IntakeClassifier {

    Intake classify(String message);

    String name();

    /** True when the call goes to a real model (latency/cost are then measured, not simulated). */
    default boolean usesLlm() {
        return false;
    }
}
