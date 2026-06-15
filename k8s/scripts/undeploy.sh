#!/usr/bin/env bash
# =============================================================================
# Phase 4 — Suppression du déploiement Kubernetes
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "[INFO] Suppression namespace planification..."
kubectl delete -k "${K8S_DIR}" --ignore-not-found=true

echo "[INFO] Attente suppression PVC (optionnel)..."
kubectl get pvc -n planification 2>/dev/null || echo "[OK] Namespace supprimé."
