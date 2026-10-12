package com.saipramod.resolveai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.saipramod.resolveai.agent.CaseState;
import com.saipramod.resolveai.agent.DecisionPolicy;
import com.saipramod.resolveai.agent.EvidencePlanner;
import com.saipramod.resolveai.agent.KeywordIntakeClassifier;
import com.saipramod.resolveai.agent.LoopingPlanner;
import com.saipramod.resolveai.agent.PolicyEngine;
import com.saipramod.resolveai.agent.ReplyWriter;
import com.saipramod.resolveai.agent.ResolutionAgent;
import com.saipramod.resolveai.agent.RuleEvidencePlanner;
import com.saipramod.resolveai.agent.ToolExecutor;
import com.saipramod.resolveai.cases.CaseService;
import com.saipramod.resolveai.domain.DataStore;
import com.saipramod.resolveai.domain.Domain.Outcome;
import com.saipramod.resolveai.domain.SeedData;
import com.saipramod.resolveai.tools.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the agent over simulated cases with known correct outcomes, under three conditions:
 * clean, chaos (transient tool failures) and a planner that loops. Usage:
 * <pre>mvn -q compile exec:java                      # 500 cases, seed 42
 * mvn -q compile exec:java -Dexec.args="1000 7"</pre>
 */
public final class EvalRunner {

    public record ConfigResult(String config, int cases, double decisionAccuracy, double autonomousRate,
                               double escalationPrecision, double escalationRecall, int unsafeRefunds,
                               int overEscalations, double refundAmountAccuracy, double toolSuccessRate,
                               double avgSteps, int maxSteps, int guardTrips, double p50LatencyMs, double p95LatencyMs,
                               double avgCostPerCaseUsd, double costPerAutoResolutionUsd,
                               Map<String, Double> accuracyByScenario, List<String> sampleErrors) { }

    private static final CaseService.Pricing PRICING = new CaseService.Pricing(3.0, 15.0);

    private EvalRunner() { }

    public static void main(String[] args) throws IOException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42;
        List<ConfigResult> results = List.of(
                run("clean", n, seed, 0.0, 0.0),
                run("chaos: 15% tool failures", n, seed, 0.15, 0.0),
                run("looping planner (30% repeats)", n, seed, 0.0, 0.30));
        Path out = Path.of("eval/results");
        Files.createDirectories(out);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("results.json").toFile(), results);
        String md = markdown(results, n, seed);
        Files.writeString(out.resolve("results.md"), md);
        System.out.println(md);
    }

    public static ConfigResult run(String name, int n, long seed, double toolFailureRate, double loopProbability) {
        DataStore store = new DataStore(SeedData.TODAY);
        List<ScenarioGenerator.EvalCase> cases = new ScenarioGenerator().generate(n, seed, store);
        ToolExecutor executor = new ToolExecutor(Tools.all(store), toolFailureRate, seed);
        EvidencePlanner planner = loopProbability > 0
                ? new LoopingPlanner(new RuleEvidencePlanner(), loopProbability, seed) : new RuleEvidencePlanner();
        ResolutionAgent agent = new ResolutionAgent(new KeywordIntakeClassifier(), planner, executor, new PolicyEngine(),
                new DecisionPolicy(DecisionPolicy.Config.defaults()), new ReplyWriter.Template(), store,
                ResolutionAgent.Limits.defaults());

        int correct = 0, autonomous = 0, unsafe = 0, overEsc = 0, guardTrips = 0;
        int escTp = 0, escFp = 0, escFn = 0, refundCorrect = 0, refundTotal = 0;
        long toolCalls = 0, toolOk = 0, steps = 0;
        int maxSteps = 0;
        double cost = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, int[]> byScenario = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        int i = 0;
        for (ScenarioGenerator.EvalCase c : cases) {
            CaseState s = agent.run("EVAL-" + i++, c.customerId(), c.message());
            Outcome got = s.decision().outcome();
            boolean ok = got == c.expected();
            if (ok && got == Outcome.AUTO_REFUND) {
                refundTotal++;
                if (Math.abs(s.decision().amount() - c.expectedAmount()) < 0.01) {
                    refundCorrect++;
                } else {
                    ok = false;
                }
            }
            correct += ok ? 1 : 0;
            if (got == Outcome.AUTO_REFUND || got == Outcome.AUTO_DENY) {
                autonomous++;
            }
            if (got == Outcome.AUTO_REFUND && c.expected() != Outcome.AUTO_REFUND) {
                unsafe++;
            }
            if (got == Outcome.ESCALATE && c.expected() != Outcome.ESCALATE) {
                overEsc++;
            }
            if (got == Outcome.ESCALATE && c.expected() == Outcome.ESCALATE) {
                escTp++;
            } else if (got == Outcome.ESCALATE) {
                escFp++;
            } else if (c.expected() == Outcome.ESCALATE) {
                escFn++;
            }
            if (s.guardTripped() != null) {
                guardTrips++;
            }
            toolCalls += s.toolCalls();
            toolOk += s.toolSuccesses();
            steps += s.steps();
            maxSteps = Math.max(maxSteps, s.steps());
            latencies.add(s.elapsedMs());
            cost += PRICING.cost(s.llmInputTokens(), s.llmOutputTokens());
            int[] sc = byScenario.computeIfAbsent(c.scenario(), k -> new int[2]);
            sc[0]++;
            sc[1] += ok ? 1 : 0;
            if (!ok && errors.size() < 12) {
                errors.add(String.format("[%s] expected %s got %s (%s: %s) msg=\"%s\"", c.scenario(), c.expected(), got,
                        s.decision().rule(), s.decision().reason(), c.message()));
            }
        }
        latencies.sort(null);
        Map<String, Double> accByScenario = new LinkedHashMap<>();
        byScenario.forEach((k, v) -> accByScenario.put(k, r3((double) v[1] / v[0])));
        return new ConfigResult(name, n, r3((double) correct / n), r3((double) autonomous / n),
                r3(escTp + escFp == 0 ? 0 : (double) escTp / (escTp + escFp)),
                r3(escTp + escFn == 0 ? 0 : (double) escTp / (escTp + escFn)),
                unsafe, overEsc, r3(refundTotal == 0 ? 0 : (double) refundCorrect / refundTotal),
                r3(toolCalls == 0 ? 0 : (double) toolOk / toolCalls), r3((double) steps / n), maxSteps, guardTrips,
                percentile(latencies, 50), percentile(latencies, 95),
                r6(cost / n), r6(autonomous == 0 ? 0 : cost / autonomous), accByScenario, errors);
    }

    static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double r6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }

    static String markdown(List<ConfigResult> results, int n, long seed) {
        StringBuilder sb = new StringBuilder("## ResolveAI agent eval\n\n");
        sb.append(String.format(Locale.ROOT, "%d simulated cases (seed %d), %d scenario types, offline intake (keyword classifier).%n%n",
                n, seed, results.get(0).accuracyByScenario().size()));
        sb.append("| Config | Decision acc. | Autonomous rate | Esc. precision | Esc. recall | Unsafe refunds | Over-escalations | Tool success | Avg steps | Max steps | Guard trips | p50 / p95 latency* | Cost / case** |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ConfigResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | **%.1f%%** | %.1f%% | %.3f | %.3f | **%d** | %d | %.1f%% | %.2f | %d | %d | %.0f / %.0f ms | $%.5f |%n",
                    r.config(), r.decisionAccuracy() * 100, r.autonomousRate() * 100, r.escalationPrecision(),
                    r.escalationRecall(), r.unsafeRefunds(), r.overEscalations(), r.toolSuccessRate() * 100,
                    r.avgSteps(), r.maxSteps(), r.guardTrips(), r.p50LatencyMs(), r.p95LatencyMs(), r.avgCostPerCaseUsd()));
        }
        sb.append("\n\\* Simulated: fixed per-tool latencies, 700 ms intake + 900 ms reply LLM calls, retry backoff.  \n");
        sb.append("\\*\\* Estimated from prompt/response size at an assumed $3 / $15 per million input/output tokens.\n\n");
        sb.append("Accuracy by scenario (clean run):\n\n| Scenario | Accuracy |\n|---|---|\n");
        results.get(0).accuracyByScenario().forEach((k, v) ->
                sb.append(String.format(Locale.ROOT, "| %s | %.0f%% |%n", k, v * 100)));
        sb.append("\nSample errors (clean run):\n\n");
        results.get(0).sampleErrors().forEach(e -> sb.append("- ").append(e).append('\n'));
        return sb.toString();
    }
}
