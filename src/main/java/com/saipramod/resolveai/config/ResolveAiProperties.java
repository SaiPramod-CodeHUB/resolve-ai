package com.saipramod.resolveai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param autoRefundLimit     refunds above this always need a human
 * @param riskThreshold       fraud risk at or above this always needs a human
 * @param minConfidence       intake confidence below this always needs a human
 * @param maxSteps            hard cap on agent steps per case
 * @param maxRepeats          duplicate tool calls skipped before the loop guard escalates
 * @param toolFailureRate     chaos testing: probability a tool call fails transiently
 * @param llmPlanner          let the LLM choose tool calls (ReAct) instead of the rule planner
 * @param inputPricePerMTok   USD per million input tokens, for cost estimates (set to your model's price)
 * @param outputPricePerMTok  USD per million output tokens
 */
@ConfigurationProperties(prefix = "resolveai")
public record ResolveAiProperties(
        double autoRefundLimit,
        double riskThreshold,
        double minConfidence,
        int maxSteps,
        int maxRepeats,
        double toolFailureRate,
        boolean llmPlanner,
        double inputPricePerMTok,
        double outputPricePerMTok) { }
