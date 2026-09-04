#!/usr/bin/env bash
# Publica el portal:
#   1) Caddy :80 delante de portal-java (IP pública del host / port-forward).
#   2) Túnel Cloudflare (HTTPS) si este host no recibe inbound (Cloud Agent).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f docker-compose.local.yml -f docker-compose.public.yml)

echo "==> Levantando stack local + Caddy :80"
"${COMPOSE[@]}" up -d

echo "==> Esperando portal"
for _ in $(seq 1 30); do
  if curl -sf -o /dev/null --max-time 2 http://127.0.0.1:8080/login; then
    break
  fi
  sleep 2
done

if curl -sf -o /dev/null --max-time 3 http://127.0.0.1:80/login; then
  echo "Caddy :80 OK  →  http://<IP-PUBLICA-DEL-HOST>/"
else
  echo "AVISO: Caddy :80 no respondió (puerto ocupado o Caddy aún arrancando)"
fi

CLOUDFLARED="${CLOUDFLARED_BIN:-cloudflared}"
if ! command -v "$CLOUDFLARED" >/dev/null 2>&1; then
  echo "==> Instalando cloudflared (túnel HTTPS de salida)"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cloudflared" \
    https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
  chmod +x "$tmp/cloudflared"
  if [ -w /usr/local/bin ]; then
    mv "$tmp/cloudflared" /usr/local/bin/cloudflared
    CLOUDFLARED=cloudflared
  else
    sudo mv "$tmp/cloudflared" /usr/local/bin/cloudflared
    CLOUDFLARED=cloudflared
  fi
  rm -rf "$tmp"
fi

LOG=/tmp/zonak-cloudflared.log
echo "==> Túnel Cloudflare → http://127.0.0.1:8080  (log: $LOG)"
# Quick tunnel: URL https://*.trycloudflare.com (no usa la IP del VM).
nohup "$CLOUDFLARED" tunnel --no-autoupdate --url http://127.0.0.1:8080 >"$LOG" 2>&1 &
echo $! > /tmp/zonak-cloudflared.pid

url=""
for _ in $(seq 1 40); do
  url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" | head -1 || true)"
  if [ -n "$url" ]; then
    break
  fi
  sleep 1
done

echo
echo "Portal local:     http://127.0.0.1:8080/login"
echo "Portal Caddy:     http://127.0.0.1/login   (y http://IP-PUBLICA/ si el router reenvía :80)"
if [ -n "$url" ]; then
  echo "Portal Internet:  $url/login"
else
  echo "Portal Internet:  (revisar $LOG — el túnel puede tardar unos segundos)"
fi
echo "Login: admin / admin  (cambiar en cuanto entre)"
echo "No publicar :5432 ni :8081 a Internet."
