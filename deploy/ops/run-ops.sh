#!/bin/sh
set -eu
mkdir -p /root/cognora-ops/audit
touch /root/cognora-ops/known_hosts
docker run --rm --network host \
  -e COGNORA_OPS_SSH_PASSWORD \
  -v /root/cognora-ops/audit:/root/.cognora-ops \
  -v /root/cognora-ops/known_hosts:/root/.ssh/known_hosts:ro \
  cognora/ops-cli:1.1 "$@"
