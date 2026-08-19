#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "Falta .env. Copia .env.example a .env y completa los valores."
  exit 1
fi

if [[ ! -f certs/dian-certificate.p12 ]]; then
  echo "AVISO: no hay certs/dian-certificate.p12"
  echo "Para DIAN real coloca el .p12 en deploy/hostinger/certs/dian-certificate.p12"
  echo "Para pruebas con mock, en .env pon DIAN_MOCK_ENABLED=true"
fi

mkdir -p certs backups

echo "==> Construyendo e iniciando servicios Hostinger..."
docker compose --env-file .env up -d --build

echo "==> Estado:"
docker compose --env-file .env ps

echo
echo "Listo. Portal: https://$(grep -E '^DOMAIN=' .env | cut -d= -f2-)"
echo "Logs: docker compose --env-file .env logs -f portal-java core-go dian-net"
