#!/usr/bin/env bash
# =============================================================================
# Phase 3 — Provision Vagrant (exécuté une fois au premier vagrant up)
# Installe : Docker Engine, Docker Compose v2 plugin, Git, curl, rsync
# =============================================================================
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "=============================================="
echo "  Phase 3 — Provision Docker (UMP Planification)"
echo "=============================================="

# --- Paquets utilitaires ---
apt-get update -qq
apt-get install -y -qq \
  ca-certificates \
  curl \
  git \
  gnupg \
  lsb-release \
  rsync \
  unzip

# --- Docker déjà installé ? ---
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "[INFO] Docker déjà installé : $(docker --version)"
  echo "[INFO] $(docker compose version)"
else
  echo "[INFO] Installation de Docker Engine + Compose plugin..."

  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc

  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
    https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y -qq \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin

  systemctl enable docker
  systemctl start docker
fi

# --- Utilisateur vagrant dans le groupe docker ---
usermod -aG docker vagrant

echo "[OK] Provision Docker terminée."
docker --version
docker compose version
