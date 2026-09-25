package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.metrics.MetricsReport;
import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a scenario.
 *
 * @param name    scenario name
 * @param success whether every check passed
 * @param checks  individual verifications
 * @param metrics computed metrics
 * @param workDir directory holding the evidence
 */
public record ScenarioResult(String name, boolean success, List<ScenarioEnvironment.Check> checks,
                             MetricsReport metrics, Path workDir) {
}
