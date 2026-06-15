#!/usr/bin/env bash
# Synchronise les init.sql des services vers k8s/init-scripts/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${K8S_DIR}/.." && pwd)"
INIT_DIR="${K8S_DIR}/init-scripts"

mkdir -p "${INIT_DIR}"
cp "${PROJECT_ROOT}/service-catalogue/init.sql" "${INIT_DIR}/catalogue-init.sql"
cp "${PROJECT_ROOT}/service-locaux/init.sql" "${INIT_DIR}/locaux-init.sql"
echo "[OK] init.sql synchronisés vers ${INIT_DIR}/"
