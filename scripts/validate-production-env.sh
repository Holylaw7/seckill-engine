#!/usr/bin/env bash
set -euo pipefail

required=(
  MYSQL_ROOT_PASSWORD
  SECKILL_JWT_SECRET
  ORDER_SERVICE_SECRET
  INVENTORY_SERVICE_SECRET
  INTERNAL_ADMIN_SECRET
  CANARY_CONTROL_TOKEN
  MOCK_CHANNEL_SECRET
)

demo_values=(
  seckill-root
  seckill-engine-dev-secret-change-me
  dev-order-secret
  dev-inventory-secret
  dev-admin-secret
  dev-canary-token
  mock-channel-secret
)

failed=0
for name in "${required[@]}"; do
  value="${!name:-}"
  if [[ -z "$value" ]]; then
    echo "$name is missing" >&2
    failed=1
    continue
  fi
  for demo in "${demo_values[@]}"; do
    if [[ "$value" == "$demo" ]]; then
      echo "$name contains a demo value" >&2
      failed=1
    fi
  done
  if [[ "${#value}" -lt 32 ]]; then
    echo "$name must contain at least 32 characters" >&2
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  exit 1
fi

echo "Production secret validation passed. Values were not printed."
