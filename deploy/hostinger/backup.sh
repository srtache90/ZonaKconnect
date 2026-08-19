#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# shellcheck disable=SC1091
source .env

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${ROOT_DIR}/backups/${STAMP}"
mkdir -p "$OUT_DIR"

echo "==> Backup Postgres -> ${OUT_DIR}/postgres.sql.gz"
docker compose --env-file .env exec -T postgres \
  pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" | gzip > "${OUT_DIR}/postgres.sql.gz"

echo "==> Backup MinIO (datos) -> ${OUT_DIR}/minio-data.tgz"
docker run --rm \
  -v zona-k-hostinger-minio:/data:ro \
  -v "${OUT_DIR}:/backup" \
  alpine tar czf /backup/minio-data.tgz -C /data .

echo "Backup completo en ${OUT_DIR}"
