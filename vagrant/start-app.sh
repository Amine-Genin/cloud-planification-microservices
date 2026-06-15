#!/usr/bin/env bash
# =============================================================================
# Phase 3/4 — Démarrage application (exécuté à chaque vagrant up, run: always)
# 1. Copie le projet depuis /vagrant vers un chemin natif ext4
# 2. Corrige les fins de ligne CRLF Windows sur les scripts .sh
# 3a. Mode Docker Compose (Phase 3) : docker compose up --build -d
# 3b. Mode K3s (Phase 4)           : docker compose ignoré
# 4. Attend que les 3 services applicatifs répondent
# =============================================================================
set -euo pipefail

APP_DIR="/home/vagrant/projet2-planification"
VAGRANT_SRC="/vagrant"
MAX_WAIT=180
COMPOSE_BUILD="${COMPOSE_BUILD:-1}"
FORCE_COMPOSE="${FORCE_COMPOSE:-0}"

echo "=============================================="
echo "  Démarrage microservices (Vagrant)"
echo "=============================================="

# --- Copie /vagrant → ext4 (évite les problèmes vboxsf + Docker volumes) ---
echo "[INFO] Synchronisation du projet vers ${APP_DIR}..."
mkdir -p "${APP_DIR}"
rsync -a --delete \
  --exclude '.git' \
  --exclude 'target' \
  --exclude '*.war' \
  --exclude '.idea' \
  "${VAGRANT_SRC}/" "${APP_DIR}/"

# --- Corriger CRLF Windows sur les scripts shell ---
echo "[INFO] Correction des fins de ligne (CRLF → LF)..."
find "${APP_DIR}" -name "*.sh" -type f -exec sed -i 's/\r$//' {} +
find "${APP_DIR}/k8s/scripts" -name "*.sh" -type f -exec chmod +x {} + 2>/dev/null || true

chown -R vagrant:vagrant "${APP_DIR}"
chmod +x "${APP_DIR}/test.sh" 2>/dev/null || true
chmod +x "${APP_DIR}/vagrant/"*.sh 2>/dev/null || true

cd "${APP_DIR}"

# --- Détection mode K3s ---
is_k3s_active() {
  command -v k3s >/dev/null 2>&1 \
    && systemctl is-active --quiet k3s 2>/dev/null
}

if is_k3s_active && [ "${FORCE_COMPOSE}" != "1" ]; then
  echo "[INFO] Mode K3s actif détecté — docker compose ignoré."
  RUN_MODE="k3s"
  CATALOGUE_PORT=30081
  LOCAUX_PORT=30082
  EMPLOI_PORT=30083
else
  if is_k3s_active && [ "${FORCE_COMPOSE}" = "1" ]; then
    echo "[INFO] K3s installé mais FORCE_COMPOSE=1 — lancement Docker Compose."
  else
    echo "[INFO] Mode Docker Compose (K3s non actif)."
  fi
  RUN_MODE="compose"

  if [ "${COMPOSE_BUILD}" = "1" ]; then
    echo "[INFO] docker compose up --build -d ..."
    docker compose up --build -d
  else
    echo "[INFO] docker compose up -d (sans rebuild)..."
    docker compose up -d
  fi

  CATALOGUE_PORT=8081
  LOCAUX_PORT=8082
  EMPLOI_PORT=8083
fi

# --- Attente des services ---
echo "[INFO] Attente du démarrage des services (max ${MAX_WAIT}s)..."
elapsed=0
ready=false
while [ "${elapsed}" -lt "${MAX_WAIT}" ]; do
  if curl -sf "http://localhost:${CATALOGUE_PORT}/api/cours" >/dev/null 2>&1 \
     && curl -sf "http://localhost:${LOCAUX_PORT}/api/locaux" >/dev/null 2>&1 \
     && curl -sf "http://localhost:${EMPLOI_PORT}/api/emploi-du-temps" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 10
  elapsed=$((elapsed + 10))
  echo "[INFO] En attente... (${elapsed}s / ${MAX_WAIT}s)"
  if [ "${RUN_MODE}" = "compose" ]; then
    docker compose ps || true
  else
    kubectl get pods -n planification 2>/dev/null || true
  fi
done

echo ""
echo "=============================================="
if [ "${RUN_MODE}" = "compose" ]; then
  docker compose ps
else
  kubectl get pods -n planification 2>/dev/null || echo "[INFO] Namespace planification absent — lancez ./k8s/scripts/deploy.sh"
fi
echo "=============================================="
echo ""

if [ "${ready}" = true ]; then
  echo "[OK] Les 3 services répondent (mode ${RUN_MODE})."
else
  if [ "${RUN_MODE}" = "k3s" ]; then
    echo "[WARN] Timeout — déployez K8s : vagrant ssh k3s-master -c 'cd ${APP_DIR} && ./k8s/scripts/deploy.sh'"
  else
    echo "[WARN] Timeout atteint — vérifiez : vagrant ssh k3s-master → docker compose logs"
  fi
fi

echo ""
if [ "${RUN_MODE}" = "k3s" ]; then
  echo "URLs K3s NodePort (dans la VM) :"
  echo "  Catalogue : http://localhost:30081/api/cours"
  echo "  Locaux    : http://localhost:30082/api/locaux"
  echo "  Emploi    : http://localhost:30083/api/emploi-du-temps"
  echo ""
  echo "Depuis Windows (ports forwardés) : mêmes URLs sur localhost."
  echo "API K3s : https://localhost:6443"
else
  echo "URLs Docker Compose (dans la VM) :"
  echo "  Catalogue : http://localhost:8081/api/cours"
  echo "  Locaux    : http://localhost:8082/api/locaux"
  echo "  Emploi    : http://localhost:8083/api/emploi-du-temps"
  echo ""
  echo "Depuis Windows (ports forwardés) : mêmes URLs sur localhost."
fi
echo ""
echo "Tests : vagrant ssh k3s-master -c 'cd ${APP_DIR} && ./test.sh'"
