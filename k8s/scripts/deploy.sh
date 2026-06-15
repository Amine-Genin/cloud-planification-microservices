#!/usr/bin/env bash
# =============================================================================
# Phase 4 — Déploiement complet sur Kubernetes / K3s
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${K8S_DIR}/.." && pwd)"

echo "=============================================="
echo "  Déploiement Kubernetes — namespace planification"
echo "=============================================="

# Vérifier kubectl
if ! command -v kubectl >/dev/null 2>&1; then
  echo "[ERREUR] kubectl introuvable."
  exit 1
fi

# Build images si demandé
if [ "${BUILD_IMAGES:-1}" = "1" ]; then
  echo "[INFO] Build des images Docker..."
  "${SCRIPT_DIR}/build-images.sh"
fi

# Import K3s si disponible
if command -v k3s >/dev/null 2>&1 && [ "${IMPORT_K3S:-1}" = "1" ]; then
  echo "[INFO] Import des images dans K3s..."
  "${SCRIPT_DIR}/import-images-k3s.sh"
fi

# Synchroniser init.sql (source : service-catalogue, service-locaux)
"${SCRIPT_DIR}/sync-init-sql.sh"

# Appliquer les manifests
echo "[INFO] kubectl apply -k ${K8S_DIR} ..."
kubectl apply -k "${K8S_DIR}"

echo "[INFO] Attente des pods Ready (max 300s)..."
kubectl wait --for=condition=Ready pods --all -n planification --timeout=300s || true

echo ""
kubectl get pods -n planification -o wide
echo ""
kubectl get svc -n planification
echo ""
kubectl get ingress -n planification 2>/dev/null || true

echo ""
echo "=============================================="
echo "  ACCÈS AUX API"
echo "=============================================="
echo ""
echo "Option A — NodePort (recommandé pour démo) :"
echo "  Catalogue : http://<NODE_IP>:30081/api/cours"
echo "  Locaux    : http://<NODE_IP>:30082/api/locaux"
echo "  Emploi    : http://<NODE_IP>:30083/api/emploi-du-temps"
echo ""
echo "Option B — Ingress (ajouter dans hosts) :"
echo "  127.0.0.1 planification.local"
echo "  http://planification.local/api/cours"
echo "  http://planification.local/api/locaux"
echo "  http://planification.local/api/emploi-du-temps"
echo ""
echo "Vérifier : kubectl get pods -n planification"
echo "Logs     : kubectl logs -n planification deploy/service-catalogue"
