#!/usr/bin/env bash
# =============================================================================
# Phase 3 — Arrêt des conteneurs dans la VM Vagrant
# Usage (depuis l'hôte) : vagrant ssh -c "/vagrant/vagrant/stop-app.sh"
# =============================================================================
set -euo pipefail

APP_DIR="/home/vagrant/projet2-planification"

if [ -d "${APP_DIR}" ]; then
  cd "${APP_DIR}"
  docker compose down
  echo "[OK] Conteneurs arrêtés."
else
  echo "[WARN] ${APP_DIR} introuvable — rien à arrêter."
fi
