#!/usr/bin/env bash
# Builds the project (no tests) and runs one scenario in-process.
# Usage: scripts/run-scenario.sh <greenfield|brownfield|ambiguous>
# Evidence is written to working_tree/<scenario>/ (git-ignored).
set -euo pipefail

scenario="${1:?usage: run-scenario.sh <greenfield|brownfield|ambiguous>}"
cd "$(dirname "$0")/.."

# Compile and resolve the runtime classpath once; the scenario JVM then runs
# with a plain classpath, so the in-process compiler sees every dependency.
mvn -B -q -DskipTests compile dependency:build-classpath \
    -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime

java -cp "target/classes:$(cat target/classpath.txt)" \
    com.agentsdlc.orchestrator.scenario.ScenarioMain "$scenario" working_tree .
