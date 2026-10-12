package com.saipramod.resolveai.config;

import com.saipramod.resolveai.agent.DecisionPolicy;
import com.saipramod.resolveai.agent.EvidencePlanner;
import com.saipramod.resolveai.agent.IntakeClassifier;
import com.saipramod.resolveai.agent.KeywordIntakeClassifier;
import com.saipramod.resolveai.agent.LlmEvidencePlanner;
import com.saipramod.resolveai.agent.LlmIntakeClassifier;
import com.saipramod.resolveai.agent.PolicyEngine;
import com.saipramod.resolveai.agent.ReplyWriter;
import com.saipramod.resolveai.agent.ResolutionAgent;
import com.saipramod.resolveai.agent.RuleEvidencePlanner;
import com.saipramod.resolveai.agent.ToolExecutor;
import com.saipramod.resolveai.cases.CaseService;
import com.saipramod.resolveai.domain.DataStore;
import com.saipramod.resolveai.domain.SeedData;
import com.saipramod.resolveai.tools.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offline by default; with LLM_PROVIDER=anthropic the intake, (optionally) planner and reply use Claude. */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    DataStore dataStore() {
        return SeedData.demo();
    }

    @Bean
    ToolExecutor toolExecutor(DataStore store, ResolveAiProperties p) {
        return new ToolExecutor(Tools.all(store), p.toolFailureRate(), System.nanoTime());
    }

    @Bean
    ResolutionAgent resolutionAgent(ObjectProvider<ChatModel> chatModel, ToolExecutor executor, DataStore store,
                                    ResolveAiProperties p) {
        ChatModel model = chatModel.getIfAvailable();
        ChatClient chat = model == null ? null : ChatClient.create(model);
        IntakeClassifier keyword = new KeywordIntakeClassifier();
        IntakeClassifier intake = chat == null ? keyword : new LlmIntakeClassifier(chat, keyword);
        EvidencePlanner rules = new RuleEvidencePlanner();
        EvidencePlanner planner = chat != null && p.llmPlanner() ? new LlmEvidencePlanner(chat, executor.tools(), rules) : rules;
        ReplyWriter reply = chat == null ? new ReplyWriter.Template() : new ReplyWriter.Llm(chat);
        ResolutionAgent agent = new ResolutionAgent(intake, planner, executor, new PolicyEngine(),
                new DecisionPolicy(new DecisionPolicy.Config(p.autoRefundLimit(), p.riskThreshold(), p.minConfidence())),
                reply, store, new ResolutionAgent.Limits(p.maxSteps(), p.maxRepeats()));
        log.info("ResolveAI agent: {}", agent.describe());
        return agent;
    }

    @Bean
    CaseService caseService(ResolutionAgent agent, DataStore store, ResolveAiProperties p) {
        return new CaseService(agent, store, new CaseService.Pricing(p.inputPricePerMTok(), p.outputPricePerMTok()));
    }
}
