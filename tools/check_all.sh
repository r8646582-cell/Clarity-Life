#!/usr/bin/env bash
# Runs every contract check. Used by CI and by hand: bash tools/check_all.sh
# Python 3.9+ standard library only (zoneinfo needs the system time-zone database, present on Linux and macOS).
set -euo pipefail
cd "$(dirname "$0")/.."
export PYTHONDONTWRITEBYTECODE=1
python3 tools/check_prompts.py
python3 tools/test_cards.py
python3 tools/test_planner.py
python3 tools/test_textcheck.py
python3 tools/test_promise_times.py
python3 tools/test_plan_dates.py
python3 tools/test_context.py
python3 tools/test_appliers.py
python3 tools/test_bench.py
python3 tools/check_schema.py
python3 tools/mutation_check.py
echo "ALL CONTRACT CHECKS PASSED"
