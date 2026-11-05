#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# images.lock.env is generated on the server with the pulled image's digest.
set -a
source ./images.lock.env
set +a
docker run --rm \
  -v "$PWD/letsencrypt:/etc/letsencrypt" \
  -v "$PWD/acme-webroot:/var/www/acme" \
  "$CERTBOT_IMAGE" renew --non-interactive \
  --webroot --webroot-path /var/www/acme "$@"
docker compose -f compose.yml -f compose.ip.yml -f compose.lock.yml \
  exec -T proxy caddy reload --config /etc/caddy/Caddyfile
