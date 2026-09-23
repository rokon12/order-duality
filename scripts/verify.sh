#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d --wait mysql
mvn -B -ntp clean verify -Pintegration
echo 'Evidence: target/evidence/experiments.txt'
