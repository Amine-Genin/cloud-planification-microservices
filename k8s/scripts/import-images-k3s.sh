#!/usr/bin/env bash
# =============================================================================
# Phase 4 — Import des images Docker dans K3s (containerd)
# À exécuter sur le nœud K3s (ou dans la VM Vagrant Phase 3)
# =============================================================================
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:-1.0.0}"
IMAGES=(
  "ump/service-catalogue:${IMAGE_TAG}"
  "ump/service-locaux:${IMAGE_TAG}"
  "ump/service-emploi-du-temps:${IMAGE_TAG}"
)

echo "=============================================="
echo "  Import images vers K3s — tag ${IMAGE_TAG}"
echo "=============================================="

for img in "${IMAGES[@]}"; do
  if ! docker image inspect "${img}" >/dev/null 2>&1; then
    echo "[ERREUR] Image absente : ${img}"
    echo "         Lancez d'abord : ./build-images.sh"
    exit 1
  fi
  echo "[INFO] Import ${img}..."
  docker save "${img}" | sudo k3s ctr images import -
done

echo "[OK] Images importées dans K3s."
sudo k3s ctr images list | grep "ump/service-" || true
