#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER_NAME="${CONTAINER_NAME:-zona-k-postgres-local}"
DATABASE="${DATABASE:-zona_k_facturacion}"
DB_USER="${DB_USER:-zona_k_app}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.local.yml}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/backups/db}"
SQL_FORMAT="${SQL_FORMAT:-0}"

usage() {
  cat <<'EOF'
Uso: scripts/backup-db.sh [--sql]

Variables opcionales:
  CONTAINER_NAME  (default: zona-k-postgres-local)
  DATABASE        (default: zona_k_facturacion)
  DB_USER         (default: zona_k_app)
  OUTPUT_DIR      (default: backups/db)
  COMPOSE_FILE    (default: docker-compose.local.yml)
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --sql) SQL_FORMAT=1 ;;
    *) echo "Opción desconocida: $arg" >&2; usage; exit 1 ;;
  esac
done

if ! docker version >/dev/null 2>&1; then
  echo "Docker no está disponible." >&2
  exit 1
fi

ensure_postgres() {
  if docker ps --filter "name=^/${CONTAINER_NAME}$" --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
    return
  fi

  if docker ps -a --filter "name=^/${CONTAINER_NAME}$" --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
    echo "Iniciando contenedor ${CONTAINER_NAME}..."
    docker start "${CONTAINER_NAME}" >/dev/null
    return
  fi

  echo "Levantando servicio postgres desde ${COMPOSE_FILE}..."
  (cd "${ROOT_DIR}" && docker compose -f "${COMPOSE_FILE}" up -d postgres)
}

ensure_postgres
mkdir -p "${OUTPUT_DIR}"

STAMP="$(date +%Y%m%d-%H%M%S)"

if [[ "${SQL_FORMAT}" == "1" ]]; then
  OUT_FILE="${OUTPUT_DIR}/zona_k_facturacion_${STAMP}.sql"
  echo "Exportando ${DATABASE} (SQL) -> ${OUT_FILE}"
  docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -d "${DATABASE}" --no-owner --no-acl > "${OUT_FILE}"
else
  OUT_FILE="${OUTPUT_DIR}/zona_k_facturacion_${STAMP}.dump"
  CONTAINER_DUMP="/tmp/zona_k_backup_${STAMP}.dump"
  echo "Exportando ${DATABASE} (custom) -> ${OUT_FILE}"
  docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -d "${DATABASE}" -Fc -f "${CONTAINER_DUMP}"
  docker cp "${CONTAINER_NAME}:${CONTAINER_DUMP}" "${OUT_FILE}"
  docker exec "${CONTAINER_NAME}" rm -f "${CONTAINER_DUMP}" >/dev/null || true
fi

SIZE_KB="$(du -k "${OUT_FILE}" | awk '{print $1}')"
echo
echo "Backup listo:"
echo "  Archivo: ${OUT_FILE}"
echo "  Tamaño:  ${SIZE_KB} KB"
echo
echo "Para restaurar:"
echo "  ./scripts/restore-db.sh \"${OUT_FILE}\""
