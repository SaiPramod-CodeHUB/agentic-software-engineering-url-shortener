#!/usr/bin/env bash
# Runs ANY requirement through the governed pipeline, with live human approvals.
#
#   ./run-request.sh "Add an API that returns a QR payload for a short link"
#   ./run-request.sh --auto-approve "..."      # unattended: approvals granted and recorded as auto
#
# Real generation needs Claude:  export AGENTIC_LLM=claude ANTHROPIC_API_KEY=sk-ant-...
# (optional: ANTHROPIC_MODEL, default claude-sonnet-5). Without it, only the demo features build.
# Evidence: working_tree/request/
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# -lt 1 ]]; then
    echo 'usage: ./run-request.sh [--auto-approve] "<requirement>"' >&2
    exit 2
fi
mvn -B -q -DskipTests compile dependency:build-classpath \
    -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
exec java -cp "target/classes:$(cat target/classpath.txt)" \
    com.agentsdlc.orchestrator.scenario.ScenarioMain request "$@"
