package com.saipramod.resolveai.agent;

import com.saipramod.resolveai.tools.Tools.ToolCall;

import java.util.Map;
import java.util.Optional;

/** Chooses the next evidence-gathering tool call, or empty when there is enough evidence. */
public interface EvidencePlanner {

    Optional<ToolCall> next(CaseState state);

    String name();

    default boolean usesLlm() {
        return false;
    }

    static ToolCall call(String name, String k1, String v1) {
        return new ToolCall(name, Map.of(k1, v1));
    }

    static ToolCall call(String name, String k1, String v1, String k2, String v2) {
        return new ToolCall(name, Map.of(k1, v1, k2, v2));
    }
}
