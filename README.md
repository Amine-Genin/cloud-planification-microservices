# Projet 2 — Planification & Logistique

[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Jakarta EE](https://img.shields.io/badge/Jakarta%20EE-10-purple)](https://jakarta.ee/)

**Stack :** Jakarta EE 10 · WildFly 27 · MySQL 8 · Docker Compose · REST (JAX-RS)

**Démarrage rapide :** `docker compose up --build -d` puis `./test.sh` (14 tests d’intégration).

**Université Mohammed Premier (UMP) — Oujda, Maroc**  
**Master M2I — Cloud Computing — Année universitaire 2025/2026**

---

## 1. Description du projet

Ce projet met en place une architecture **microservices** pour la planification académique à l’UMP Oujda. Trois services Jakarta EE 10 communiquent via HTTP pour gérer :

- le **catalogue des cours** ;
- les **locaux** (amphis, salles, TP, laboratoires) ;
- l’**emploi du temps** (planification des séances avec vérification inter-services).

L’orchestration est assurée par **Docker Compose v2** avec un réseau partagé `planification-net` et des healthchecks sur les bases MySQL et les applications WildFly.

**Phase 3 (Vagrant) :** la même stack peut tourner dans une VM Ubuntu provisionnée automatiquement. Voir `PHASE3_VAGRANT.txt` et `vagrant up`.

**Phase 4 (Kubernetes) :** déploiement sur K3s avec manifests `k8s/`. Voir `PHASE4_K8S.txt` et `./k8s/scripts/deploy.sh`.

---

## 2. Prérequis

| Outil | Version minimale |
|-------|------------------|
| **Docker Desktop** | 4.x (Compose v2 intégré) |
| **Java JDK** | 17 |
| **Apache Maven** | 3.9 |
| **curl** | pour les tests (inclus dans Git Bash / Linux / macOS) |
| **Postman** | optionnel (import de la collection) |

Environnement de développement optionnel : **NetBeans** + **WildFly 27** pour déploiement local hors Docker.

---

## 3. Instructions de lancement

Depuis la racine du projet :

```bash
cd projet2-planification
docker compose up --build
```

Mode détaché (arrière-plan) :

```bash
docker compose up --build -d
```

Attendre environ **90 à 120 secondes** (démarrage WildFly + healthchecks). Vérifier :

```bash
docker compose ps
```

Tous les conteneurs doivent être **Up** et **healthy**.

Pour réinitialiser les bases et recharger les données `init.sql` :

```bash
docker compose down -v
docker compose up --build -d
```

### Phase 3 — Lancement via Vagrant (VM Ubuntu + Docker)

Prérequis hôte : **VirtualBox** + **Vagrant**.

```bash
cd projet2-planification
vagrant up
```

Après provisionnement (10–20 min la première fois), les API sont accessibles sur la machine hôte :

- http://localhost:8081/api/cours
- http://localhost:8082/api/locaux
- http://localhost:8083/api/emploi-du-temps

Tests dans la VM :

```bash
vagrant ssh -c "cd /home/vagrant/projet2-planification && ./test.sh"
```

Documentation complète : `PHASE3_VAGRANT.txt`.

### Phase 4 — Déploiement Kubernetes (K3s)

Prérequis : **kubectl** + cluster **K3s** (ou Docker Desktop Kubernetes).

```bash
cd projet2-planification
chmod +x k8s/scripts/*.sh   # Git Bash / Linux
./k8s/scripts/deploy.sh
```

Accès API via **NodePort** (aligné sur les ports Compose) :

- http://localhost:30081/api/cours
- http://localhost:30082/api/locaux
- http://localhost:30083/api/emploi-du-temps

Ou via **Ingress** : ajouter `127.0.0.1 planification.local` dans `hosts`, puis http://planification.local/api/cours

Documentation complète : `PHASE4_K8S.txt`.

---

## 4. Tableau des services et URLs

| Service | Conteneur | Port hôte | Base MySQL | URL API de base |
|---------|-----------|-----------|------------|-----------------|
| Catalogue Cours | `service-catalogue` | **8081** | `catalogue_db` | http://localhost:8081/api/cours |
| Locaux | `service-locaux` | **8082** | `locaux_db` | http://localhost:8082/api/locaux |
| Emploi du Temps | `service-emploi-du-temps` | **8083** | `emploi_db` | http://localhost:8083/api/emploi-du-temps |

### Documentation API (Swagger / OpenAPI)

Les microservices exposent des API **JAX-RS REST** sans UI Swagger embarquée par défaut. Points d’entrée documentés :

| Service | URL documentation pratique |
|---------|----------------------------|
| Catalogue | http://localhost:8081/api/cours |
| Locaux | http://localhost:8082/api/locaux |
| Emploi du temps | http://localhost:8083/api/emploi-du-temps |

Pour une exploration interactive, importer la collection Postman : `postman_collection.json`.

---

## 5. Exécution du script de tests `test.sh`

Le script enchaîne automatiquement le flux métier complet (création cours → local → séance → conflit 409 → lecture → suppression → libération salle).

**Linux / macOS / Git Bash (Windows) :**

```bash
cd projet2-planification
chmod +x test.sh
./test.sh
```

Sous **Git Bash** (fourni avec Git for Windows) depuis le dossier du projet. Après modification du code Java, reconstruire :

```bash
docker compose up --build -d
```

Le script :

1. Attend que les 3 services répondent (boucle de retry).
2. Crée un cours et un local de test.
3. Planifie une séance → attend **HTTP 201**.
4. Tente un créneau conflictuel → attend **HTTP 409**.
5. Vérifie la séance via `GET /jour/{jour}`.
6. Supprime la séance → attend **HTTP 204**.
7. Vérifie que le local est **DISPONIBLE** à nouveau.
8. Affiche **PASS** / **FAIL** en couleur.

Code de sortie : `0` si succès, `1` en cas d’échec.

---

## 6. Import de la collection Postman

1. Ouvrir **Postman**.
2. **Import** → **File** → sélectionner `projet2-planification/postman_collection.json`.
3. La collection contient 3 dossiers :
   - **Catalogue Cours** (port 8081)
   - **Locaux** (port 8082)
   - **Emploi du Temps** (port 8083)
4. Variables de collection : `catalogue_base`, `locaux_base`, `emploi_base` (déjà pointées vers `localhost`).

Lancer les requêtes **après** `docker compose up` lorsque les services sont healthy.

---

## 7. Schéma d’architecture (ASCII)

```
                    ┌─────────────────────────────────────────┐
                    │         Docker Compose (Phase 2)       │
                    │         Réseau : planification-net      │
                    └─────────────────────────────────────────┘
                                          │
        ┌─────────────────────────────────┼─────────────────────────────────┐
        │                                 │                                 │
        ▼                                 ▼                                 ▼
┌───────────────┐               ┌───────────────┐               ┌───────────────────────┐
│ service-      │               │ service-      │               │ service-emploi-du-    │
│ catalogue     │               │ locaux        │               │ temps                 │
│ WildFly :8081 │               │ WildFly :8082 │               │ WildFly :8083         │
│ (host 8081)   │               │ (host 8082)   │               │ (host 8083)           │
└───────┬───────┘               └───────┬───────┘               └───────────┬───────────┘
        │                               │                                   │
        │ JPA                             │ JPA                               │ JPA
        ▼                               ▼                                   │ HTTP clients
┌───────────────┐               ┌───────────────┐                           │
│ catalogue-db  │               │ locaux-db     │              ┌────────────┴────────────┐
│ MySQL 8.0     │               │ MySQL 8.0     │              │ GET /api/cours/{id}     │
│ init.sql      │               │ init.sql      │              │ GET /api/locaux/        │
│ (10 cours)    │               │ (10 locaux)   │              │   disponibles           │
└───────────────┘               └───────────────┘              │ PATCH disponibilite   │
                                                               └────────────┬────────────┘
                                                                            │
                                                                            ▼
                                                               ┌───────────────────────┐
                                                               │ emploi-db             │
                                                               │ MySQL 8.0             │
                                                               │ (séances planifiées)  │
                                                               └───────────────────────┘
```

---

## 8. Communication inter-services

Le service **emploi-du-temps** ne stocke pas les détails des cours ni des salles : il conserve des références (`coursId`, `localId`) et délègue la validation aux autres microservices.

### Variables d’environnement (Docker)

| Variable | Valeur par défaut (Compose) |
|----------|-----------------------------|
| `CATALOGUE_BASE_URL` | `http://service-catalogue:8080` |
| `LOCAUX_BASE_URL` | `http://service-locaux:8080` |

### Flux `planifierSeance()`

1. **CatalogueClient** — `GET /api/cours/{id}` → si absent : **400**
2. **LocalClient** — `GET /api/locaux/disponibles` → si la salle n’est pas disponible : **400**
3. **Base emploi** — détection de conflit (même salle, même jour, horaires chevauchants) → **409**
4. **LocalClient** — `PATCH /api/locaux/{id}/disponibilite?valeur=OCCUPE`
5. Persistance de la séance → **201**

### Flux `supprimerSeance()`

1. Recherche de la séance → **404** si introuvable
2. **LocalClient** — `PATCH` → `DISPONIBLE`
3. Suppression en base → **204**

---

## 9. Structure du dépôt

```
projet2-planification/
├── compose.yaml                 # Orchestration maître (include)
├── postman_collection.json      # Tests manuels Postman
├── test.sh                      # Tests automatiques curl
├── README.md
├── service-catalogue/
│   ├── compose.yaml
│   ├── init.sql
│   ├── Dockerfile
│   └── src/...
├── service-locaux/
│   ├── compose.yaml
│   ├── init.sql
│   ├── Dockerfile
│   └── src/...
└── service-emploi-du-temps/
    ├── compose.yaml
    ├── Dockerfile
    └── src/...
```

---

## 10. Auteurs — Trinôme M2I UMP Oujda 2025/2026

| Étudiant | Filière | Établissement |
|----------|---------|---------------|
| **Membre 1** — _à compléter_ | Master M2I | Université Mohammed Premier, Oujda |
| **Membre 2** — _à compléter_ | Master M2I | Université Mohammed Premier, Oujda |
| **Membre 3** — _à compléter_ | Master M2I | Université Mohammed Premier, Oujda |

**Encadrement :** Module Cloud Computing — Semestre 2 — Année 2025/2026.

---

## Licence et usage

Projet académique — usage pédagogique UMP Oujda. Toute reproduction doit mentionner l’Université Mohammed Premier et l’année universitaire 2025/2026.
