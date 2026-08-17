#!/usr/bin/env bash
set -Eeuo pipefail

# Named volumes are created as root by Docker. RocketMQ itself must keep
# running as the image's unprivileged rocketmq user.
for directory in /home/rocketmq/logs /home/rocketmq/store; do
  mkdir -p "${directory}"
  chown -R rocketmq:rocketmq "${directory}"
  chmod 0750 "${directory}"
done

if [[ "$#" -eq 0 ]]; then
  echo "RocketMQ command is required" >&2
  exit 64
fi

exec runuser -u rocketmq -- "$@"
