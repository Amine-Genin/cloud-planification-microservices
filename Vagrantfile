# -*- mode: ruby -*-
# vi: set ft=ruby :
#
# Phase 3/4 — Vagrant : cluster K3s (master + worker) ou Docker Compose
# Projet 2 — Planification & Logistique — UMP Oujda
#
# Prérequis hôte : VirtualBox + Vagrant
# Usage :
#   vagrant up k3s-master          # VM principale (Docker Compose ou K3s)
#   vagrant up k3s-worker          # Nœud worker K3s
#   vagrant up                     # Démarre les deux VMs
#
# Depuis Windows (ports forwardés sur k3s-master) :
#   Docker Compose : http://localhost:8081/api/cours
#   K3s NodePort   : http://localhost:30081/api/cours
#   API K3s        : https://localhost:6443

Vagrant.configure("2") do |config|

  # Box commune aux deux VMs
  config.vm.box = "ubuntu/jammy64"

  # Dossier du projet synchronisé (lecture depuis l'hôte Windows)
  # Docker/K8s ne supportent pas bien vboxsf → copie native dans start-app.sh
  config.vm.synced_folder ".", "/vagrant",
    mount_options: ["dmode=777", "fmode=666"]

  # ---------------------------------------------------------------------------
  # k3s-master — VM principale (192.168.56.10, 4 Go RAM)
  # Phase 3 : Docker Compose  |  Phase 4 : K3s control-plane
  # ---------------------------------------------------------------------------
  config.vm.define "k3s-master", primary: true do |master|
    master.vm.hostname = "k3s-master"
    master.vm.network "private_network", ip: "192.168.56.10"

    # Phase 3 — Docker Compose (ports identiques à compose.yaml)
    master.vm.network "forwarded_port", guest: 8081, host: 8081, host_ip: "127.0.0.1"
    master.vm.network "forwarded_port", guest: 8082, host: 8082, host_ip: "127.0.0.1"
    master.vm.network "forwarded_port", guest: 8083, host: 8083, host_ip: "127.0.0.1"

    # Phase 4 — K3s NodePort + API server
    master.vm.network "forwarded_port", guest: 30081, host: 30081, host_ip: "127.0.0.1"
    master.vm.network "forwarded_port", guest: 30082, host: 30082, host_ip: "127.0.0.1"
    master.vm.network "forwarded_port", guest: 30083, host: 30083, host_ip: "127.0.0.1"
    master.vm.network "forwarded_port", guest: 6443,  host: 6443,  host_ip: "127.0.0.1"

    master.vm.provider "virtualbox" do |vb|
      vb.name = "ump-planification-k3s-master"
      vb.memory = 4096
      vb.cpus = 2
      vb.customize ["modifyvm", :id, "--natdnshostresolver1", "on"]
      vb.customize ["modifyvm", :id, "--ioapic", "on"]
    end

    master.vm.provider "libvirt" do |lv|
      lv.memory = 4096
      lv.cpus = 2
    end

    # 1) Une seule fois : Docker, Git, curl, rsync
    master.vm.provision "docker", type: "shell", path: "vagrant/provision.sh"

    # 2) À chaque vagrant up : sync projet + Docker Compose OU mode K3s
    master.vm.provision "app", type: "shell", path: "vagrant/start-app.sh", run: "always"
  end

  # ---------------------------------------------------------------------------
  # k3s-worker — Nœud worker (192.168.56.11, 2 Go RAM)
  # Rejoint le cluster K3s via le master (installation manuelle Phase 4)
  # ---------------------------------------------------------------------------
  config.vm.define "k3s-worker" do |worker|
    worker.vm.hostname = "k3s-worker"
    worker.vm.network "private_network", ip: "192.168.56.11"

    worker.vm.provider "virtualbox" do |vb|
      vb.name = "ump-planification-k3s-worker"
      vb.memory = 2048
      vb.cpus = 1
      vb.customize ["modifyvm", :id, "--natdnshostresolver1", "on"]
      vb.customize ["modifyvm", :id, "--ioapic", "on"]
    end

    worker.vm.provider "libvirt" do |lv|
      lv.memory = 2048
      lv.cpus = 1
    end

    # Docker requis pour l'agent K3s (k3s agent)
    worker.vm.provision "docker", type: "shell", path: "vagrant/provision.sh"
  end

end
