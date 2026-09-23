#!/usr/bin/env bash
set -euo pipefail
base="${API_URL:-http://127.0.0.1:8080}"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
curl --fail --silent --show-error "$base/orders/1001" > "$scratch/order.json"
python3 - "$scratch" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
order = json.loads((p / 'order.json').read_text())
assert order['_id'] == 1001
assert order['customer']['name'] == 'Alice Rahman'
assert len(order['items']) == 2
order['status'] = 'SHIPPED'
(p / 'shipped.json').write_text(json.dumps(order))
PY
curl --fail --silent --show-error -X PUT "$base/orders/1001" \
  -H 'Content-Type: application/json' --data-binary "@$scratch/shipped.json" > "$scratch/result.json"
python3 - "$scratch/result.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1]))['status'] == 'SHIPPED'
PY
status="$(curl --silent --show-error -o "$scratch/conflict.json" -w '%{http_code}' \
  -X PUT "$base/orders/1001" -H 'Content-Type: application/json' --data-binary "@$scratch/shipped.json")"
test "$status" = 409
curl --fail --silent --show-error "$base/orders/1001/relational" > /dev/null
curl --fail --silent --show-error "$base/customers/42/orders" > /dev/null
echo 'PASS: GET, PUT, stale PUT (409), relational endpoint, customer tool projection'
