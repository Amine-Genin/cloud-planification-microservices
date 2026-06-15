#!/usr/bin/env bash
# =============================================================================
# Phase 4 — Build des images Docker pour Kubernetes
# Tags alignés sur les Deployments : ump/service-*:1.0.0
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMAGE_TAG="${IMAGE_TAG:-1.0.0}"

echo "=============================================="
echo "  Build images Kubernetes — tag ${IMAGE_TAG}"
echo "  Projet : ${PROJECT_ROOT}"
echo "=============================================="

cd "${PROJECT_ROOT}"

echo "[1/3] Build service-catalogue..."
docker build -t "ump/service-catalogue:${IMAGE_TAG}" ./service-catalogue

echo "[2/3] Build service-locaux..."
docker build -t "ump/service-locaux:${IMAGE_TAG}" ./service-locaux

echo "[3/3] Build service-emploi-du-temps..."
docker build -t "ump/service-emploi-du-temps:${IMAGE_TAG}" ./service-emploi-du-temps

echo ""
echo "[OK] Images construites :"
docker images | grep "ump/service-" || true
echo ""
echo "Prochaine étape :"
echo "  K3s  → ./import-images-k3s.sh"
echo "  Kind → kind load docker-image ump/service-catalogue:${IMAGE_TAG} ..."
