#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/mrs-cert.sh
compose=(docker compose -f compose.mrs.yml)
"${compose[@]}" up -d --wait mysql
"${compose[@]}" exec -T mysql sh -c 'install -o mysql -g mysql -m 600 /etc/mrs/tls/server.key /var/lib/mysql/mrs-server.key; install -o mysql -g mysql -m 644 /etc/mrs/tls/server.crt /var/lib/mysql/mrs-server.crt'
sql() { "${compose[@]}" exec -T -e MYSQL_PWD=local-mrs-root-only mysql mysql -uroot "$@"; }
shell_file() {
  "${compose[@]}" exec -T -e MYSQL_PWD=local-mrs-root-only mysql sh -c \
    'mysqlsh --mysql -uroot --password="$MYSQL_PWD" --sql --file="$1"' sh "$1"
}
if [[ "$(sql -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='order_demo' AND table_name='mrs_demo_setup'")" != 1 ]]; then
  if [[ "$(sql -Nse "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='order_demo'")" != 0 ]]; then
    echo 'Partial setup detected. Use a fresh Compose project/volume; existing data will not be reset.' >&2
    exit 1
  fi
  shell_file /workspace/sql/schema.sql
  shell_file /workspace/sql/seed.sql
  shell_file /workspace/sql/duality-views.sql
  shell_file /workspace/mrs/setup.sql
  shell_file /workspace/mrs/workflow.sql
  shell_file /workspace/mrs/endpoints.sql
  sql -e "INSTALL COMPONENT 'file://component_mysql_rest_service'
    SET PERSIST mysql_rest_service.ssl_cert='/var/lib/mysql/mrs-server.crt',
        PERSIST mysql_rest_service.ssl_key='/var/lib/mysql/mrs-server.key',
        PERSIST mysql_rest_service.http_max_request_body_size=65536,
        PERSIST mysql_rest_service.http_max_response_body_size=262144;"
  sql -e "CREATE TABLE order_demo.mrs_demo_setup (version INT PRIMARY KEY); INSERT INTO order_demo.mrs_demo_setup VALUES(1);"
fi
for attempt in $(seq 1 30); do
  state="$(sql -Nse "SHOW GLOBAL STATUS LIKE 'mysql_rest_service.state'" | awk '{print $2}')"
  if [[ "$state" == READY ]]; then
    echo "MRS ready at https://localhost:${MRS_PORT:-8443}/orders"
    exit 0
  fi
  sleep 1
done
echo "MRS did not reach READY (state: $state)" >&2
exit 1
