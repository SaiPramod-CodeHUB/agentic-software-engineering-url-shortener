#!/usr/bin/env bash
# Runs the brownfield scenario; exits 0 only if every check passes.
exec "$(dirname "$0")/scripts/run-scenario.sh" brownfield
