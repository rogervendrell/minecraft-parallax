#!/usr/bin/env bash
# Creates a virtualenv on first run, installs MediaPipe, then starts the head tracker.
# Any arguments are passed through, e.g.  ./run_tracker.sh --camera 1 --no-preview
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -x .venv/bin/python ]; then
	echo "Setting up Python environment (first run only)..."
	python3 -m venv .venv
	.venv/bin/pip install --upgrade pip
	.venv/bin/pip install -r requirements.txt
fi

exec .venv/bin/python head_tracker.py "$@"
