#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER_NAME="${CONTAINER_NAME:-zona-k-postgres-local}"
DATABASE="${DATABASE:-zona_k_facturacion}"
DB_USER="${DB_USER:-zona_k_app}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.local.yml}"
FORCE="${FORCE:-0}"

usage() {
  cat <<'EOF'
Uso: scripts/restore-db.sh <archivo.backup> [--force]

Variables opcionales:
  CONTAINER_NAME  (default: zona-k-postgres-local)
  DATABASE        (default: zona_k_facturacion)
  DB_USER         (default: zona_k_app)
  COMPOSE_FILE    (default: docker-compose.local.yml)
EOF
}

BACKUP_FILE=""
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --force) FORCE=1 ;;
    *)
      if [[ -z "${BACKUP_FILE}" ]]; then
        BACKUP_FILE="$arg"
      else
        echo "Opción desconocida: $arg" >&2
        usage
        exit 1
      fi
      ;;
  esac
done

if [[ -z "${BACKUP_FILE}" ]]; then
  usage
  exit 1
fi

if [[ ! -f "${BACKUP_FILE}" ]]; then
  if [[ -f "${ROOT_DIR}/${BACKUP_FILE}" ]]; then
    BACKUP_FILE="${ROOT_DIR}/${BACKUP_FILE}"
  else
    echo "No se encontró el archivo: ${BACKUP_FILE}" >&2
    exit 1
  fi
fi

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
  sleep 5
}

ensure_postgres

if [[ "${FORCE}" != "1" ]]; then
  echo "ADVERTENCIA: esto reemplazará datos en '${DATABASE}' del contenedor '${CONTAINER_NAME}'."
  echo "Archivo: ${BACKUP_FILE}"
  read -r -p "¿Continuar? (s/N): " answer
  if [[ ! "${answer}" =~ ^[sSyY]$ ]]; then
    echo "Restauración cancelada."
    exit 0
  fi
fi

EXT="${BACKUP_FILE##*.}"
CONTAINER_PATH="/tmp/zona_k_restore.${EXT}"

echo "Copiando backup al contenedor..."
docker cp "${BACKUP_FILE}" "${CONTAINER_NAME}:${CONTAINER_PATH}"

cleanup() {
  docker exec "${CONTAINER_NAME}" rm -f "${CONTAINER_PATH}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "${EXT}" == "sql" ]]; then
  echo "Restaurando desde SQL..."
  docker exec -i "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${DATABASE}" -v ON_ERROR_STOP=1 < "${BACKUP_FILE}"
else
  echo "Restaurando desde dump custom (pg_restore)..."
  docker exec "${CONTAINER_NAME}" pg_restore -U "${DB_USER}" -d "${DATABASE}" --clean --if-exists "${CONTAINER_PATH}"
fi

echo
echo "Restauración completada."
echo "Validación sugerida:"
echo "  docker exec -it ${CONTAINER_NAME} psql -U ${DB_USER} -d ${DATABASE} -c '\\dt'"
echo
echo "Si el stack completo no está arriba:"
echo "  docker compose -f ${COMPOSE_FILE} up -d"
