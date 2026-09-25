package com.agentsdlc.orchestrator.metrics;

import java.util.Map;

/**
 * Pipeline metrics for one scenario, serialised to {@code metrics.json}.
 *
 * @param runs                 number of runs
 * @param runSuccessRate       share of runs that fully succeeded
 * @param taskSuccessRate      share of attempted tasks that succeeded (incl. via fallback)
 * @param counters             event counters (tasks, retries, fallbacks, rollbacks, safe-stops, re-plans, ...)
 * @param stageLatencyMeanMs   mean execution time per SDLC stage
 * @param endToEndLatencyP50Ms median run duration
 * @param endToEndLatencyP95Ms 95th-percentile run duration (nearest rank)
 * @param incidentsResolved    number of opened-and-resolved incident pairs
 * @param mttrMs               mean time to recovery over resolved incidents, or {@code null} if none
 */
public record MetricsReport(int runs, double runSuccessRate, double taskSuccessRate, Map<String, Long> counters,
                            Map<String, Double> stageLatencyMeanMs, long endToEndLatencyP50Ms,
                            long endToEndLatencyP95Ms, int incidentsResolved, Long mttrMs) {
}
