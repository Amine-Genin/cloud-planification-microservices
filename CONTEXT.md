# CONTEXT — Projet Planification & Logistique Académique (UMP Oujda)

> Dépôt : `cloud-planification-microservices`  
> Stack : 3 microservices Jakarta EE 10 / WildFly 27 + MySQL 8, Docker Compose, Vagrant, Kubernetes/K3s

---

## 1. Architecture générale

### Liste des services, ports, conteneurs (app + DB)

| Service applicatif | Conteneur app | Port hôte → conteneur | Conteneur DB | Port DB (interne) | Base MySQL | Utilisateur DB |
|---|---|---|---|---|---|---|
| **service-catalogue** | `service-catalogue` | **8081** → 8080 | `catalogue-db` | 3306 | `catalogue_db` | `catalogue_user` / `catalogue_pass` |
| **service-locaux** | `service-locaux` | **8082** → 8080 | `locaux-db` | 3306 | `locaux_db` | `locaux_user` / `locaux_pass` |
| **service-emploi-du-temps** | `service-emploi-du-temps` | **8083** → 8080 | `emploi-db` | 3306 | `emploi_db` | `emploi_user` / `emploi_pass` |

**Total : 6 conteneurs Docker** (3 WildFly + 3 MySQL 8.0).

**Ports alternatifs selon l'environnement :**

| Environnement | Catalogue | Locaux | Emploi du temps |
|---|---|---|---|
| Docker Compose (hôte Windows) | 8081 | 8082 | 8083 |
| Vagrant (forwarded host → guest) | 18081 → 8081 | 18082 → 8082 | 18083 → 8083 |
| K3s NodePort | 30081 | 30082 | 30083 |
| K3s Ingress (host `planification.local`) | 80 `/api/cours` | 80 `/api/locaux` | 80 `/api/emploi-du-temps` |
| K3s API server (Vagrant) | 6443 | — | — |

**Réseau Docker :** `planification-net` (driver `bridge`, nom explicite partagé entre les 3 compose inclus).

**Context-root JAX-RS :** `@ApplicationPath("/api")` dans chaque service → URLs finales `/api/cours`, `/api/locaux`, `/api/emploi-du-temps`.

**jboss-web.xml** (tous les services) : `<context-root>/</context-root>` — le WAR est déployé à la racine, pas sous `/service-catalogue`.

### Schéma textuel des dépendances REST

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         RÉSEAU planification-net                          │
│                    (DNS Docker : noms de service = hostname)                │
└─────────────────────────────────────────────────────────────────────────────┘

  Client externe (curl, Postman, test.sh, navigateur)
       │
       ├── GET/POST/PUT/DELETE  http://localhost:8081/api/cours/*
       │                              │
       │                              ▼
       │                    ┌─────────────────────┐
       │                    │  service-catalogue  │──── JDBC ────► catalogue-db:3306
       │                    │  (WildFly :8080)    │                (catalogue_db)
       │                    └─────────────────────┘
       │                              ▲
       │                              │ GET /api/cours/{id}
       │                              │ (CatalogueClient)
       │
       ├── GET/POST/PUT/PATCH/DELETE  http://localhost:8082/api/locaux/*
       │                              │
       │                              ▼
       │                    ┌─────────────────────┐
       │                    │   service-locaux    │──── JDBC ────► locaux-db:3306
       │                    │  (WildFly :8080)    │                (locaux_db)
       │                    └─────────────────────┘
       │                              ▲
       │                              │ GET /api/locaux/disponibles
       │                              │ PATCH /api/locaux/{id}/disponibilite?valeur=...
       │                              │ (LocalClient)
       │
       └── GET/POST/DELETE  http://localhost:8083/api/emploi-du-temps/*
                                      │
                                      ▼
                            ┌─────────────────────────┐
                            │ service-emploi-du-temps │──── JDBC ────► emploi-db:3306
                            │   (WildFly :8080)       │                (emploi_db)
                            │   ORCHESTRATEUR         │
                            └─────────────────────────┘
                                      │
                    CATALOGUE_BASE_URL=http://service-catalogue:8080
                    LOCAUX_BASE_URL=http://service-locaux:8080

Couplage UNIDIRECTIONNEL :
  • emploi-du-temps → catalogue  (HTTP REST)
  • emploi-du-temps → locaux     (HTTP REST)
  • catalogue NE connaît PAS emploi-du-temps
  • locaux NE connaît PAS emploi-du-temps
  • Aucun accès direct aux bases des autres services (database per service)
```

**Flux planification (POST /api/emploi-du-temps) :**
1. `EmploiDuTempsService.planifierSeance()` valide les champs
2. `CatalogueClient.verifierCoursExiste(coursId)` → `GET {CATALOGUE_BASE_URL}/api/cours/{id}`
3. `LocalClient.verifierSalleDisponible(localId)` → `GET {LOCAUX_BASE_URL}/api/locaux/disponibles?capaciteMin=0`
4. `SeanceRepository.existeConflit()` → détection chevauchement horaire (409 si conflit)
5. `LocalClient.reserverSalle(localId)` → `PATCH {LOCAUX_BASE_URL}/api/locaux/{id}/disponibilite?valeur=OCCUPE`
6. Persistance JPA de la `Seance` dans `emploi_db`
7. À la suppression : `LocalClient.libererSalle()` → PATCH `valeur=DISPONIBLE`

---

## 2. Pour chaque microservice

### 2.1 Service Catalogue (`service-catalogue`)

#### Entités JPA

**`Cours`** — table `cours` (pas de relations JPA vers d'autres entités)

| Champ Java | Type | Colonne SQL | Contraintes |
|---|---|---|---|
| `id` | `Long` | `id` | PK, `@GeneratedValue(IDENTITY)` |
| `code` | `String` | `code` | NOT NULL, UNIQUE, max 20 (`uk_cours_code`) |
| `intitule` | `String` | `intitule` | NOT NULL, max 255 |
| `syllabus` | `String` | `syllabus` | TEXT (`@Lob`), optionnel |
| `prerequis` | `String` | `prerequis` | max 500, optionnel |
| `credits` | `int` | `credits` | entier (validé >= 0 en service) |

**Persistence unit :** `cataloguePU` → JNDI `java:jboss/datasources/CatalogueDS`, schema-generation `update`.

#### Endpoints REST exposés

Base URL : `http://localhost:8081/api/cours`  
Content-Type : `application/json`

| Méthode | Path | Payload (requête) | Réponse succès | Codes erreur |
|---|---|---|---|---|
| `GET` | `/api/cours` | — | `200` — tableau JSON `[{id, code, intitule, syllabus, prerequis, credits}, ...]` | `500` |
| `GET` | `/api/cours/{id}` | — | `200` — objet `Cours` | `404`, `500` |
| `GET` | `/api/cours/code/{code}` | — | `200` — objet `Cours` | `404`, `500` |
| `POST` | `/api/cours` | `{"code":"INF311","intitule":"DevOps","syllabus":"...","prerequis":"INF301","credits":5}` | `201` — cours créé avec `id` | `400`, `500` |
| `PUT` | `/api/cours/{id}` | corps JSON complet `Cours` | `200` — cours mis à jour | `400`, `404`, `500` |
| `DELETE` | `/api/cours/{id}` | — | `204` — corps vide | `404`, `500` |

Format erreur JSON : `{"erreur":"...", "statut":400}`

#### Dockerfile (multi-stage, contenu exact)

```dockerfile
# Stage 1 — BUILD
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2 — RUN
FROM quay.io/wildfly/wildfly:27.0.1.Final-jdk17

USER root
RUN mkdir -p /opt/jboss/wildfly/modules/com/mysql/main
COPY --from=build /root/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar \
    /opt/jboss/wildfly/modules/com/mysql/main/mysql-connector-j.jar
COPY docker/module.xml /opt/jboss/wildfly/modules/com/mysql/main/module.xml
COPY docker/entrypoint.sh /opt/jboss/entrypoint.sh
RUN sed -i 's/\r$//' /opt/jboss/entrypoint.sh \
    && chmod +x /opt/jboss/entrypoint.sh \
    && chown -R jboss:jboss /opt/jboss/wildfly/modules/com /opt/jboss/entrypoint.sh

USER jboss
COPY --from=build /app/target/service-catalogue.war $JBOSS_HOME/standalone/deployments/

ENV DATASOURCE_NAME=CatalogueDS
EXPOSE 8080

ENTRYPOINT ["/bin/bash", "/opt/jboss/entrypoint.sh"]
```

#### Variables d'environnement / config WildFly

**Variables d'environnement (Docker Compose / K8s ConfigMap + Secret) :**

| Variable | Valeur par défaut (Compose) | Rôle |
|---|---|---|
| `DATASOURCE_NAME` | `CatalogueDS` | Nom JNDI datasource WildFly |
| `DB_HOST` | `catalogue-db` | Hôte MySQL |
| `DB_PORT` | `3306` | Port MySQL |
| `DB_NAME` | `catalogue_db` | Base de données |
| `DB_USER` | `catalogue_user` | Utilisateur JDBC |
| `DB_PASSWORD` | `catalogue_pass` | Mot de passe JDBC |

**entrypoint.sh** configure via CLI offline (`embed-server`) :
- Driver JDBC MySQL module `com.mysql`
- Datasource JNDI `java:jboss/datasources/CatalogueDS`
- Connection properties : `useSSL=false`, `serverTimezone=UTC`, `allowPublicKeyRetrieval=true`
- Démarrage : `standalone.sh -b 0.0.0.0 -bmanagement 0.0.0.0`

**persistence.xml :**
```xml
<persistence-unit name="cataloguePU" transaction-type="JTA">
    <jta-data-source>java:jboss/datasources/CatalogueDS</jta-data-source>
    <class>ma.ump.catalogue.entity.Cours</class>
    ...
</persistence-unit>
```

**Données initiales :** `service-catalogue/init.sql` monté dans `catalogue-db` via `/docker-entrypoint-initdb.d/init.sql` (10 cours pré-chargés).

---

### 2.2 Service Locaux (`service-locaux`)

#### Entités JPA

**`Local`** — table `locaux` (pas de relations `@ManyToOne` / `@OneToMany`)

| Champ Java | Type | Colonne SQL | Contraintes |
|---|---|---|---|
| `id` | `Long` | `id` | PK, auto-incrément |
| `code` | `String` | `code` | NOT NULL, UNIQUE, max 30 (`uk_local_code`) |
| `nom` | `String` | `nom` | NOT NULL, max 255 |
| `type` | `TypeLocal` (enum) | `type` | NOT NULL, STRING — `AMPHI`, `SALLE_TP`, `SALLE_COURS`, `LABO` |
| `capacite` | `int` | `capacite` | NOT NULL |
| `batiment` | `String` | `batiment` | max 100, optionnel |
| `etage` | `int` | `etage` | |
| `projecteur` | `boolean` | `projecteur` | |
| `tableauNumerique` | `boolean` | `tableau_numerique` | |
| `climatisation` | `boolean` | `climatisation` | |
| `accessiblePMR` | `boolean` | `accessible_pmr` | |
| `disponibilite` | `DisponibiliteLocal` (enum) | `disponibilite` | NOT NULL, défaut `DISPONIBLE` — valeurs : `DISPONIBLE`, `OCCUPE`, `EN_MAINTENANCE` |

**Enums (pas d'entités JPA séparées) :**
- `TypeLocal` : `AMPHI`, `SALLE_TP`, `SALLE_COURS`, `LABO`
- `DisponibiliteLocal` : `DISPONIBLE`, `OCCUPE`, `EN_MAINTENANCE`

**Persistence unit :** `locauxPU` → JNDI `java:jboss/datasources/LocauxDS`.

#### Endpoints REST exposés

Base URL : `http://localhost:8082/api/locaux`

| Méthode | Path | Payload / paramètres | Réponse succès | Codes erreur |
|---|---|---|---|---|
| `GET` | `/api/locaux` | — | `200` — tableau de `Local` | `500` |
| `GET` | `/api/locaux/{id}` | — | `200` — objet `Local` | `404`, `500` |
| `GET` | `/api/locaux/disponibles` | query `capaciteMin` (défaut `0`) | `200` — locaux `DISPONIBLE` filtrés | `400`, `500` |
| `GET` | `/api/locaux/type/{type}` | `{type}` = `AMPHI`, `SALLE_TP`, etc. | `200` — tableau filtré | `400`, `500` |
| `POST` | `/api/locaux` | JSON `Local` complet | `201` — local créé | `400`, `500` |
| `PUT` | `/api/locaux/{id}` | JSON `Local` complet | `200` — local mis à jour | `400`, `404`, `500` |
| `PATCH` | `/api/locaux/{id}/disponibilite` | query `valeur=DISPONIBLE\|OCCUPE\|EN_MAINTENANCE` | `200` — local mis à jour | `400`, `404`, `500` |
| `DELETE` | `/api/locaux/{id}` | — | `204` | `404`, `500` |

Exemple POST :
```json
{
  "code": "TP-A12",
  "nom": "Salle TP A12",
  "type": "SALLE_TP",
  "capacite": 35,
  "batiment": "Bloc A",
  "etage": 1,
  "projecteur": true,
  "tableauNumerique": false,
  "climatisation": true,
  "accessiblePMR": true,
  "disponibilite": "DISPONIBLE"
}
```

#### Dockerfile (multi-stage, contenu exact)

```dockerfile
# Stage 1 — BUILD
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2 — RUN
FROM quay.io/wildfly/wildfly:27.0.1.Final-jdk17

USER root
RUN mkdir -p /opt/jboss/wildfly/modules/com/mysql/main
COPY --from=build /root/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar \
    /opt/jboss/wildfly/modules/com/mysql/main/mysql-connector-j.jar
COPY docker/module.xml /opt/jboss/wildfly/modules/com/mysql/main/module.xml
COPY docker/entrypoint.sh /opt/jboss/entrypoint.sh
RUN sed -i 's/\r$//' /opt/jboss/entrypoint.sh \
    && chmod +x /opt/jboss/entrypoint.sh \
    && chown -R jboss:jboss /opt/jboss/wildfly/modules/com /opt/jboss/entrypoint.sh

USER jboss
COPY --from=build /app/target/service-locaux.war $JBOSS_HOME/standalone/deployments/

ENV DATASOURCE_NAME=LocauxDS
EXPOSE 8080

ENTRYPOINT ["/bin/bash", "/opt/jboss/entrypoint.sh"]
```

#### Variables d'environnement / config WildFly

| Variable | Valeur Compose | Rôle |
|---|---|---|
| `DATASOURCE_NAME` | `LocauxDS` | Nom datasource |
| `DB_HOST` | `locaux-db` | Hôte MySQL |
| `DB_PORT` | `3306` | |
| `DB_NAME` | `locaux_db` | |
| `DB_USER` | `locaux_user` | |
| `DB_PASSWORD` | `locaux_pass` | |

JNDI : `java:jboss/datasources/LocauxDS`  
Données initiales : `service-locaux/init.sql`

---

### 2.3 Service Emploi du Temps (`service-emploi-du-temps`)

#### Entités JPA

**`Seance`** — table `seances` (références par ID vers catalogue/locaux, sans FK JPA inter-BDD)

| Champ Java | Type | Colonne SQL | Contraintes |
|---|---|---|---|
| `id` | `Long` | `id` | PK, auto-incrément |
| `coursId` | `Long` | `cours_id` | NOT NULL — référence logique vers `service-catalogue` |
| `localId` | `Long` | `local_id` | NOT NULL — référence logique vers `service-locaux` |
| `jour` | `JourSemaine` (enum) | `jour` | NOT NULL, STRING — `LUNDI`…`SAMEDI` |
| `heureDebut` | `String` | `heure_debut` | NOT NULL, format `HH:mm`, max 5 |
| `heureFin` | `String` | `heure_fin` | NOT NULL, format `HH:mm`, max 5 |
| `semestre` | `String` | `semestre` | NOT NULL, max 20 |
| `type` | `TypeSeance` (enum) | `type` | NOT NULL, STRING — `COURS_MAGISTRAL`, `TP`, `TD` |

**Enums :**
- `JourSemaine` : `LUNDI`, `MARDI`, `MERCREDI`, `JEUDI`, `VENDREDI`, `SAMEDI`
- `TypeSeance` : `COURS_MAGISTRAL`, `TP`, `TD`

**DTOs clients (non-JPA) :** `CoursDTO`, `LocalDTO` — parsing JSON-P des réponses REST.

**Persistence unit :** `emploiPU` → JNDI `java:jboss/datasources/EmploiDS`. Pas de `init.sql` (schéma créé par Hibernate `update`).

#### Endpoints REST exposés

Base URL : `http://localhost:8083/api/emploi-du-temps`

| Méthode | Path | Payload | Réponse succès | Codes erreur |
|---|---|---|---|---|
| `GET` | `/api/emploi-du-temps` | — | `200` — tableau de `Seance` | `500` |
| `GET` | `/api/emploi-du-temps/{id}` | — | `200` — `Seance` | `404`, `500` |
| `GET` | `/api/emploi-du-temps/jour/{jour}` | `{jour}` = `LUNDI`, etc. | `200` — séances filtrées | `400`, `500` |
| `POST` | `/api/emploi-du-temps` | voir ci-dessous | `201` — séance planifiée | `400`, `409`, `500` |
| `DELETE` | `/api/emploi-du-temps/{id}` | — | `204` + libération salle | `404`, `500` |

Exemple POST planification :
```json
{
  "coursId": 1,
  "localId": 1,
  "jour": "LUNDI",
  "heureDebut": "08:00",
  "heureFin": "10:00",
  "semestre": "S2-2026",
  "type": "COURS_MAGISTRAL"
}
```

**Appels REST sortants (clients HTTP internes) :**

| Client | Méthode | URL appelée |
|---|---|---|
| `CatalogueClient` | `GET` | `{CATALOGUE_BASE_URL}/api/cours/{coursId}` |
| `LocalClient` | `GET` | `{LOCAUX_BASE_URL}/api/locaux/disponibles?capaciteMin=0` |
| `LocalClient` | `PATCH` | `{LOCAUX_BASE_URL}/api/locaux/{localId}/disponibilite?valeur=OCCUPE` |
| `LocalClient` | `PATCH` | `{LOCAUX_BASE_URL}/api/locaux/{localId}/disponibilite?valeur=DISPONIBLE` |

#### Dockerfile (multi-stage, contenu exact)

```dockerfile
# Stage 1 — BUILD
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2 — RUN
FROM quay.io/wildfly/wildfly:27.0.1.Final-jdk17

USER root
RUN mkdir -p /opt/jboss/wildfly/modules/com/mysql/main
COPY --from=build /root/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar \
    /opt/jboss/wildfly/modules/com/mysql/main/mysql-connector-j.jar
COPY docker/module.xml /opt/jboss/wildfly/modules/com/mysql/main/module.xml
COPY docker/entrypoint.sh /opt/jboss/entrypoint.sh
RUN sed -i 's/\r$//' /opt/jboss/entrypoint.sh \
    && chmod +x /opt/jboss/entrypoint.sh \
    && chown -R jboss:jboss /opt/jboss/wildfly/modules/com /opt/jboss/entrypoint.sh

USER jboss
COPY --from=build /app/target/service-emploi-du-temps.war $JBOSS_HOME/standalone/deployments/

ENV DATASOURCE_NAME=EmploiDS
ENV CATALOGUE_BASE_URL=http://service-catalogue:8080
ENV LOCAUX_BASE_URL=http://service-locaux:8080
EXPOSE 8080

ENTRYPOINT ["/bin/bash", "/opt/jboss/entrypoint.sh"]
```

#### Variables d'environnement / config WildFly

| Variable | Valeur Compose | Rôle |
|---|---|---|
| `DATASOURCE_NAME` | `EmploiDS` | Nom datasource |
| `DB_HOST` | `emploi-db` | Hôte MySQL emploi |
| `DB_PORT` | `3306` | |
| `DB_NAME` | `emploi_db` | |
| `DB_USER` | `emploi_user` | |
| `DB_PASSWORD` | `emploi_pass` | |
| `CATALOGUE_BASE_URL` | `http://service-catalogue:8080` | URL base service catalogue |
| `LOCAUX_BASE_URL` | `http://service-locaux:8080` | URL base service locaux |

Lecture Java : `System.getenv().getOrDefault("CATALOGUE_BASE_URL", "http://service-catalogue:8080")`

**Module MySQL JDBC (`docker/module.xml`, identique aux 3 services) :**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<module xmlns="urn:jboss:module:1.9" name="com.mysql">
    <resources>
        <resource-root path="mysql-connector-j.jar"/>
    </resources>
    <dependencies>
        <module name="java.se"/>
        <module name="jakarta.transaction.api"/>
    </dependencies>
</module>
```

---

## 3. Docker Compose

### Contenu exact — `compose.yaml` (maître avec `include`)

```yaml
# Projet 2 — Phase 2 : Orchestration maître des microservices UMP Oujda
# Usage : docker compose up --build -d
# Réseau partagé : planification-net

include:
  - path: ./service-catalogue/compose.yaml
  - path: ./service-locaux/compose.yaml
  - path: ./service-emploi-du-temps/compose.yaml

services:
  service-catalogue:
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/cours"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s

  service-locaux:
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/locaux"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s

  service-emploi-du-temps:
    depends_on:
      emploi-db:
        condition: service_healthy
      service-catalogue:
        condition: service_healthy
      service-locaux:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/emploi-du-temps"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s

networks:
  planification-net:
    name: planification-net
    driver: bridge
```

### Contenu exact — `service-catalogue/compose.yaml`

```yaml
services:
  catalogue-db:
    image: mysql:8.0
    container_name: catalogue-db
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: catalogue_db
      MYSQL_USER: catalogue_user
      MYSQL_PASSWORD: catalogue_pass
    volumes:
      - catalogue-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - planification-net

  service-catalogue:
    build: .
    container_name: service-catalogue
    ports:
      - "8081:8080"
    environment:
      DATASOURCE_NAME: CatalogueDS
      DB_HOST: catalogue-db
      DB_PORT: "3306"
      DB_NAME: catalogue_db
      DB_USER: catalogue_user
      DB_PASSWORD: catalogue_pass
    depends_on:
      catalogue-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/cours"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s
    networks:
      - planification-net

volumes:
  catalogue-data:

networks:
  planification-net:
    name: planification-net
    driver: bridge
```

### Contenu exact — `service-locaux/compose.yaml`

```yaml
services:
  locaux-db:
    image: mysql:8.0
    container_name: locaux-db
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: locaux_db
      MYSQL_USER: locaux_user
      MYSQL_PASSWORD: locaux_pass
    volumes:
      - locaux-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - planification-net

  service-locaux:
    build: .
    container_name: service-locaux
    ports:
      - "8082:8080"
    environment:
      DATASOURCE_NAME: LocauxDS
      DB_HOST: locaux-db
      DB_PORT: "3306"
      DB_NAME: locaux_db
      DB_USER: locaux_user
      DB_PASSWORD: locaux_pass
    depends_on:
      locaux-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/locaux"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s
    networks:
      - planification-net

volumes:
  locaux-data:

networks:
  planification-net:
    name: planification-net
    driver: bridge
```

### Contenu exact — `service-emploi-du-temps/compose.yaml`

```yaml
services:
  emploi-db:
    image: mysql:8.0
    container_name: emploi-db
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: emploi_db
      MYSQL_USER: emploi_user
      MYSQL_PASSWORD: emploi_pass
    volumes:
      - emploi-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - planification-net

  service-emploi-du-temps:
    build: .
    container_name: service-emploi-du-temps
    ports:
      - "8083:8080"
    environment:
      DATASOURCE_NAME: EmploiDS
      DB_HOST: emploi-db
      DB_PORT: "3306"
      DB_NAME: emploi_db
      DB_USER: emploi_user
      DB_PASSWORD: emploi_pass
      CATALOGUE_BASE_URL: http://service-catalogue:8080
      LOCAUX_BASE_URL: http://service-locaux:8080
    depends_on:
      emploi-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/emploi-du-temps"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 60s
    networks:
      - planification-net

volumes:
  emploi-data:

networks:
  planification-net:
    name: planification-net
    driver: bridge
```

### Réseau Docker utilisé

- **Nom :** `planification-net`
- **Driver :** `bridge`
- **Déclaration :** dans chaque sous-compose ET dans le compose maître (même `name: planification-net` pour fusion)
- **Résolution DNS interne :** les noms de service (`service-catalogue`, `catalogue-db`, etc.) sont résolvables entre conteneurs

### Healthchecks / wait-for-it — mis en place et pourquoi

**Note : le binaire `wait-for-it` n'est PAS utilisé dans ce projet.** Les attentes sont implémentées par :

#### A. Docker Compose — `healthcheck` + `depends_on: condition: service_healthy`

| Conteneur | Healthcheck | Pourquoi |
|---|---|---|
| `catalogue-db`, `locaux-db`, `emploi-db` | `mysqladmin ping -h localhost -uroot -proot` | MySQL doit accepter les connexions avant que WildFly configure le datasource JDBC |
| `service-catalogue` | `curl -f http://localhost:8080/api/cours` | WildFly + déploiement WAR + datasource opérationnels |
| `service-locaux` | `curl -f http://localhost:8080/api/locaux` | idem |
| `service-emploi-du-temps` | `curl -f http://localhost:8080/api/emploi-du-temps` | idem |

**Ordre de démarrage garanti :**
- Chaque app attend sa DB (`depends_on` + `service_healthy`)
- Le compose **maître** ajoute pour `service-emploi-du-temps` :
  - `emploi-db: service_healthy`
  - `service-catalogue: service_healthy`
  - `service-locaux: service_healthy`
- **Raison :** l'orchestrateur appelle catalogue et locaux dès le premier POST ; ils doivent être UP avant emploi-du-temps

**Paramètres communs WildFly :** `interval: 15s`, `timeout: 5s`, `retries: 3`, `start_period: 60s` (WildFly démarre lentement ~60-90 s)

**Paramètres MySQL :** `interval: 10s`, `retries: 5`, `start_period: 30s`

#### B. `test.sh` — fonction `wait_for_service()`

Boucle curl jusqu'à HTTP 200 (max 60 tentatives × 2 s) avant les 14 tests d'intégration.

#### C. `vagrant/start-app.sh` — boucle curl (max 180 s)

Attend que les 3 APIs répondent après `docker compose up`.

#### D. Kubernetes — initContainers (équivalent K8s de wait-for-it)

- `wait-for-mysql` (busybox + `nc -z <db> 3306`) sur chaque Deployment WildFly
- `wait-for-catalogue` + `wait-for-locaux` (curlimages/curl) sur `service-emploi-du-temps` uniquement
- **Probes** : `livenessProbe` + `readinessProbe` HTTP GET sur les mêmes paths `/api/...`

---

## 4. Vagrant (Phase 3)

### Contenu du Vagrantfile

```ruby
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
#   Docker Compose : http://localhost:18081/api/cours  (évite conflit avec Docker Desktop local)
#   K3s NodePort   : http://localhost:30081/api/cours
#   API K3s        : https://localhost:6443
# Si Docker Desktop n'est pas lancé sur l'hôte, vous pouvez remapper host: 8081-8083.

Vagrant.configure("2") do |config|

  # Box commune aux deux VMs
  config.vm.box = "ubuntu/jammy64"

  # Premier boot Ubuntu + VirtualBox 7 sur Windows : SSH peut prendre > 5 min
  config.vm.boot_timeout = 600
  config.ssh.connect_timeout = 60
  config.ssh.keep_alive = true

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

    # Phase 3 — Docker Compose (guest 8081-8083 → host 18081-18083 pour éviter conflit Docker Desktop)
    master.vm.network "forwarded_port", guest: 8081, host: 18081, host_ip: "127.0.0.1", id: "catalogue"
    master.vm.network "forwarded_port", guest: 8082, host: 18082, host_ip: "127.0.0.1", id: "locaux"
    master.vm.network "forwarded_port", guest: 8083, host: 18083, host_ip: "127.0.0.1", id: "emploi"

    # Phase 4 — K3s NodePort + API server
    master.vm.network "forwarded_port", guest: 30081, host: 30081, host_ip: "127.0.0.1", id: "k8s-catalogue", auto_correct: true
    master.vm.network "forwarded_port", guest: 30082, host: 30082, host_ip: "127.0.0.1", id: "k8s-locaux", auto_correct: true
    master.vm.network "forwarded_port", guest: 30083, host: 30083, host_ip: "127.0.0.1", id: "k8s-emploi", auto_correct: true
    master.vm.network "forwarded_port", guest: 6443,  host: 6443,  host_ip: "127.0.0.1", id: "k8s-api", auto_correct: true

    master.vm.provider "virtualbox" do |vb|
      vb.name = "ump-planification-k3s-master"
      vb.memory = 4096
      vb.cpus = 2
      # vb.gui = true   # décommenter pour voir l'écran VM si boot bloqué
      vb.customize ["modifyvm", :id, "--natdnshostresolver1", "on"]
      vb.customize ["modifyvm", :id, "--ioapic", "on"]
      # Correctifs courants VBox 7 + ubuntu/jammy64 (timeout SSH au boot)
      vb.customize ["modifyvm", :id, "--uart1", "off"]
      vb.customize ["modifyvm", :id, "--graphicscontroller", "vmsvga"]
      vb.customize ["modifyvm", :id, "--audio", "none"]
      vb.customize ["modifyvm", :id, "--clipboard", "disabled"]
      vb.customize ["modifyvm", :id, "--draganddrop", "disabled"]
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
      vb.customize ["modifyvm", :id, "--uart1", "off"]
      vb.customize ["modifyvm", :id, "--graphicscontroller", "vmsvga"]
      vb.customize ["modifyvm", :id, "--audio", "none"]
    end

    worker.vm.provider "libvirt" do |lv|
      lv.memory = 2048
      lv.cpus = 1
    end

    # Docker requis pour l'agent K3s (k3s agent)
    worker.vm.provision "docker", type: "shell", path: "vagrant/provision.sh"
  end

end
```

### Provisioning utilisé

| Script | Type | Quand | Contenu |
|---|---|---|---|
| `vagrant/provision.sh` | shell (une fois) | premier `vagrant up` | Installe Docker Engine, Compose plugin, git, curl, rsync sur Ubuntu ; ajoute `vagrant` au groupe `docker` |
| `vagrant/start-app.sh` | shell (`run: always`) | chaque `vagrant up` | rsync `/vagrant` → `/home/vagrant/projet2-planification` (ext4 natif, évite vboxsf) ; corrige CRLF ; `docker compose up --build -d` OU mode K3s si `k3s` actif ; boucle d'attente curl |
| `vagrant/stop-app.sh` | shell (manuel) | arrêt conteneurs | `docker compose down` |

**Pas d'Ansible** — provisioning 100 % shell bash.

**Ansible :** non utilisé.

### Ressources des VM

| VM | Hostname | IP privée | RAM | CPU | Rôle |
|---|---|---|---|---|---|
| `k3s-master` (primary) | `k3s-master` | `192.168.56.10` | **4096 Mo** | **2** | Docker Compose Phase 3 / K3s control-plane Phase 4 |
| `k3s-worker` | `k3s-worker` | `192.168.56.11` | **2048 Mo** | **1** | Worker K3s (join manuel Phase 4) |

Box : `ubuntu/jammy64` (Ubuntu 22.04 LTS)  
Providers : VirtualBox (principal) + libvirt (alternatif)

---

## 5. Kubernetes / K3s (Phase 4)

### Liste des manifests

| Type | Fichier(s) |
|---|---|
| **Namespace** | `k8s/namespace.yaml` |
| **Kustomization** | `k8s/kustomization.yaml` |
| **Deployment** | `k8s/apps/service-catalogue-deployment.yaml`, `service-locaux-deployment.yaml`, `service-emploi-du-temps-deployment.yaml` |
| **Service (ClusterIP)** | `k8s/apps/service-catalogue-service.yaml`, `service-locaux-service.yaml`, `service-emploi-du-temps-service.yaml` |
| **Service (headless MySQL)** | `k8s/databases/catalogue-db-service.yaml`, `locaux-db-service.yaml`, `emploi-db-service.yaml` |
| **StatefulSet** | `k8s/databases/catalogue-db-statefulset.yaml`, `locaux-db-statefulset.yaml`, `emploi-db-statefulset.yaml` |
| **Ingress** | `k8s/ingress.yaml` |
| **Service NodePort** | `k8s/nodeport-services.yaml` (3 services) |
| **Secret** | `k8s/secrets/catalogue-db-secret.yaml`, `locaux-db-secret.yaml`, `emploi-db-secret.yaml` |
| **ConfigMap** | `k8s/configmaps/catalogue-app-configmap.yaml`, `locaux-app-configmap.yaml`, `emploi-app-configmap.yaml` |
| **ConfigMap (init SQL, généré)** | via `configMapGenerator` dans `kustomization.yaml` → `catalogue-db-init`, `locaux-db-init` |
| **PVC** | Pas de manifest PVC séparé — **`volumeClaimTemplates`** dans chaque StatefulSet MySQL (1 Gi, `ReadWriteOnce`) |

**Scripts auxiliaires :** `k8s/scripts/build-images.sh`, `import-images-k3s.sh`, `sync-init-sql.sh`, `deploy.sh`, `undeploy.sh`

**Images Docker K8s :** `ump/service-catalogue:1.0.0`, `ump/service-locaux:1.0.0`, `ump/service-emploi-du-temps:1.0.0`

### Contenu exact de chaque manifest

#### `k8s/namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: planification
  labels:
    app.kubernetes.io/part-of: ump-planification
    app.kubernetes.io/managed-by: kustomize
```

#### `k8s/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: planification

resources:
  - namespace.yaml
  # Secrets (mots de passe MySQL)
  - secrets/catalogue-db-secret.yaml
  - secrets/locaux-db-secret.yaml
  - secrets/emploi-db-secret.yaml
  # ConfigMaps applicatifs
  - configmaps/catalogue-app-configmap.yaml
  - configmaps/locaux-app-configmap.yaml
  - configmaps/emploi-app-configmap.yaml
  # Bases MySQL (StatefulSets + Services headless)
  - databases/catalogue-db-service.yaml
  - databases/catalogue-db-statefulset.yaml
  - databases/locaux-db-service.yaml
  - databases/locaux-db-statefulset.yaml
  - databases/emploi-db-service.yaml
  - databases/emploi-db-statefulset.yaml
  # Applications WildFly (Deployments + Services ClusterIP)
  - apps/service-catalogue-service.yaml
  - apps/service-catalogue-deployment.yaml
  - apps/service-locaux-service.yaml
  - apps/service-locaux-deployment.yaml
  - apps/service-emploi-du-temps-service.yaml
  - apps/service-emploi-du-temps-deployment.yaml
  # Routage HTTP
  - ingress.yaml
  - nodeport-services.yaml

# init.sql montés dans les conteneurs MySQL (identique Docker Compose)
configMapGenerator:
  - name: catalogue-db-init
    files:
      - init.sql=init-scripts/catalogue-init.sql
  - name: locaux-db-init
    files:
      - init.sql=init-scripts/locaux-init.sql
```

#### `k8s/secrets/catalogue-db-secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: catalogue-db-secret
  namespace: planification
  labels:
    app: catalogue-db
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: root
  MYSQL_DATABASE: catalogue_db
  MYSQL_USER: catalogue_user
  MYSQL_PASSWORD: catalogue_pass
```

#### `k8s/secrets/locaux-db-secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: locaux-db-secret
  namespace: planification
  labels:
    app: locaux-db
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: root
  MYSQL_DATABASE: locaux_db
  MYSQL_USER: locaux_user
  MYSQL_PASSWORD: locaux_pass
```

#### `k8s/secrets/emploi-db-secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: emploi-db-secret
  namespace: planification
  labels:
    app: emploi-db
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: root
  MYSQL_DATABASE: emploi_db
  MYSQL_USER: emploi_user
  MYSQL_PASSWORD: emploi_pass
```

#### `k8s/configmaps/catalogue-app-configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: catalogue-app-config
  namespace: planification
  labels:
    app: service-catalogue
data:
  DATASOURCE_NAME: CatalogueDS
  DB_HOST: catalogue-db
  DB_PORT: "3306"
  DB_NAME: catalogue_db
  DB_USER: catalogue_user
```

#### `k8s/configmaps/locaux-app-configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: locaux-app-config
  namespace: planification
  labels:
    app: service-locaux
data:
  DATASOURCE_NAME: LocauxDS
  DB_HOST: locaux-db
  DB_PORT: "3306"
  DB_NAME: locaux_db
  DB_USER: locaux_user
```

#### `k8s/configmaps/emploi-app-configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: emploi-app-config
  namespace: planification
  labels:
    app: service-emploi-du-temps
data:
  DATASOURCE_NAME: EmploiDS
  DB_HOST: emploi-db
  DB_PORT: "3306"
  DB_NAME: emploi_db
  DB_USER: emploi_user
  CATALOGUE_BASE_URL: http://service-catalogue:8080
  LOCAUX_BASE_URL: http://service-locaux:8080
```

#### `k8s/databases/catalogue-db-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: catalogue-db
  namespace: planification
  labels:
    app: catalogue-db
spec:
  type: ClusterIP
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
  selector:
    app: catalogue-db
  clusterIP: None
```

#### `k8s/databases/catalogue-db-statefulset.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: catalogue-db
  namespace: planification
  labels:
    app: catalogue-db
spec:
  serviceName: catalogue-db
  replicas: 1
  selector:
    matchLabels:
      app: catalogue-db
  template:
    metadata:
      labels:
        app: catalogue-db
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - containerPort: 3306
              name: mysql
          envFrom:
            - secretRef:
                name: catalogue-db-secret
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
            - name: init-scripts
              mountPath: /docker-entrypoint-initdb.d
          livenessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 15
            periodSeconds: 5
            timeoutSeconds: 3
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
      volumes:
        - name: init-scripts
          configMap:
            name: catalogue-db-init
  volumeClaimTemplates:
    - metadata:
        name: data
        labels:
          app: catalogue-db
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

#### `k8s/databases/locaux-db-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: locaux-db
  namespace: planification
  labels:
    app: locaux-db
spec:
  type: ClusterIP
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
  selector:
    app: locaux-db
  clusterIP: None
```

#### `k8s/databases/locaux-db-statefulset.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: locaux-db
  namespace: planification
  labels:
    app: locaux-db
spec:
  serviceName: locaux-db
  replicas: 1
  selector:
    matchLabels:
      app: locaux-db
  template:
    metadata:
      labels:
        app: locaux-db
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - containerPort: 3306
              name: mysql
          envFrom:
            - secretRef:
                name: locaux-db-secret
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
            - name: init-scripts
              mountPath: /docker-entrypoint-initdb.d
          livenessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 15
            periodSeconds: 5
            timeoutSeconds: 3
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
      volumes:
        - name: init-scripts
          configMap:
            name: locaux-db-init
  volumeClaimTemplates:
    - metadata:
        name: data
        labels:
          app: locaux-db
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

#### `k8s/databases/emploi-db-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emploi-db
  namespace: planification
  labels:
    app: emploi-db
spec:
  type: ClusterIP
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
  selector:
    app: emploi-db
  clusterIP: None
```

#### `k8s/databases/emploi-db-statefulset.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: emploi-db
  namespace: planification
  labels:
    app: emploi-db
spec:
  serviceName: emploi-db
  replicas: 1
  selector:
    matchLabels:
      app: emploi-db
  template:
    metadata:
      labels:
        app: emploi-db
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - containerPort: 3306
              name: mysql
          envFrom:
            - secretRef:
                name: emploi-db-secret
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
          livenessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            exec:
              command:
                - sh
                - -c
                - mysqladmin ping -h localhost -uroot -p$MYSQL_ROOT_PASSWORD
            initialDelaySeconds: 15
            periodSeconds: 5
            timeoutSeconds: 3
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
  volumeClaimTemplates:
    - metadata:
        name: data
        labels:
          app: emploi-db
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

#### `k8s/apps/service-catalogue-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: service-catalogue
  namespace: planification
  labels:
    app: service-catalogue
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: 8080
  selector:
    app: service-catalogue
```

#### `k8s/apps/service-catalogue-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-catalogue
  namespace: planification
  labels:
    app: service-catalogue
spec:
  replicas: 1
  selector:
    matchLabels:
      app: service-catalogue
  template:
    metadata:
      labels:
        app: service-catalogue
    spec:
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - |
              echo "Attente MySQL catalogue-db:3306..."
              until nc -z catalogue-db 3306; do sleep 3; done
              echo "MySQL catalogue-db disponible."
      containers:
        - name: service-catalogue
          image: ump/service-catalogue:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          envFrom:
            - configMapRef:
                name: catalogue-app-config
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: catalogue-db-secret
                  key: MYSQL_PASSWORD
          livenessProbe:
            httpGet:
              path: /api/cours
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /api/cours
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
```

#### `k8s/apps/service-locaux-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: service-locaux
  namespace: planification
  labels:
    app: service-locaux
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: 8080
  selector:
    app: service-locaux
```

#### `k8s/apps/service-locaux-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-locaux
  namespace: planification
  labels:
    app: service-locaux
spec:
  replicas: 1
  selector:
    matchLabels:
      app: service-locaux
  template:
    metadata:
      labels:
        app: service-locaux
    spec:
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - |
              echo "Attente MySQL locaux-db:3306..."
              until nc -z locaux-db 3306; do sleep 3; done
              echo "MySQL locaux-db disponible."
      containers:
        - name: service-locaux
          image: ump/service-locaux:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          envFrom:
            - configMapRef:
                name: locaux-app-config
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: locaux-db-secret
                  key: MYSQL_PASSWORD
          livenessProbe:
            httpGet:
              path: /api/locaux
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /api/locaux
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
```

#### `k8s/apps/service-emploi-du-temps-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: service-emploi-du-temps
  namespace: planification
  labels:
    app: service-emploi-du-temps
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: 8080
  selector:
    app: service-emploi-du-temps
```

#### `k8s/apps/service-emploi-du-temps-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-emploi-du-temps
  namespace: planification
  labels:
    app: service-emploi-du-temps
spec:
  replicas: 1
  selector:
    matchLabels:
      app: service-emploi-du-temps
  template:
    metadata:
      labels:
        app: service-emploi-du-temps
    spec:
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.36
          command:
            - sh
            - -c
            - |
              echo "Attente MySQL emploi-db:3306..."
              until nc -z emploi-db 3306; do sleep 3; done
              echo "MySQL emploi-db disponible."
        - name: wait-for-catalogue
          image: curlimages/curl:8.5.0
          command:
            - sh
            - -c
            - |
              echo "Attente service-catalogue..."
              until curl -sf http://service-catalogue:8080/api/cours; do sleep 5; done
              echo "service-catalogue disponible."
        - name: wait-for-locaux
          image: curlimages/curl:8.5.0
          command:
            - sh
            - -c
            - |
              echo "Attente service-locaux..."
              until curl -sf http://service-locaux:8080/api/locaux; do sleep 5; done
              echo "service-locaux disponible."
      containers:
        - name: service-emploi-du-temps
          image: ump/service-emploi-du-temps:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          envFrom:
            - configMapRef:
                name: emploi-app-config
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: emploi-db-secret
                  key: MYSQL_PASSWORD
          livenessProbe:
            httpGet:
              path: /api/emploi-du-temps
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: /api/emploi-du-temps
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
```

#### `k8s/ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: planification-ingress
  namespace: planification
  labels:
    app.kubernetes.io/part-of: ump-planification
  annotations:
    # K3s utilise Traefik par défaut
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: planification.local
      http:
        paths:
          # Catalogue — CoursResource @Path("/cours")
          - path: /api/cours
            pathType: Prefix
            backend:
              service:
                name: service-catalogue
                port:
                  number: 8080
          # Locaux — LocalResource @Path("/locaux")
          - path: /api/locaux
            pathType: Prefix
            backend:
              service:
                name: service-locaux
                port:
                  number: 8080
          # Emploi du temps — EmploiDuTempsResource @Path("/emploi-du-temps")
          - path: /api/emploi-du-temps
            pathType: Prefix
            backend:
              service:
                name: service-emploi-du-temps
                port:
                  number: 8080
```

#### `k8s/nodeport-services.yaml`

```yaml
# Services NodePort — accès direct sans Ingress (optionnel)
# Ports alignés sur Docker Compose Phase 2 : 8081, 8082, 8083
---
apiVersion: v1
kind: Service
metadata:
  name: service-catalogue-nodeport
  namespace: planification
  labels:
    app: service-catalogue
spec:
  type: NodePort
  selector:
    app: service-catalogue
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      nodePort: 30081
---
apiVersion: v1
kind: Service
metadata:
  name: service-locaux-nodeport
  namespace: planification
  labels:
    app: service-locaux
spec:
  type: NodePort
  selector:
    app: service-locaux
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      nodePort: 30082
---
apiVersion: v1
kind: Service
metadata:
  name: service-emploi-du-temps-nodeport
  namespace: planification
  labels:
    app: service-emploi-du-temps
spec:
  type: NodePort
  selector:
    app: service-emploi-du-temps
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      nodePort: 30083
```

### Comment le routage Ingress est configuré (paths `/api/...`)

1. **IngressClass :** `traefik` (inclus par défaut dans K3s)
2. **Host :** `planification.local` — nécessite entrée `/etc/hosts` : `127.0.0.1 planification.local`
3. **Annotation Traefik :** `traefik.ingress.kubernetes.io/router.entrypoints: web` (port 80)
4. **Règles pathType `Prefix` :**

| Path Ingress | Service backend | Port | Endpoint JAX-RS correspondant |
|---|---|---|---|
| `/api/cours` | `service-catalogue` | 8080 | `@ApplicationPath("/api")` + `@Path("/cours")` |
| `/api/locaux` | `service-locaux` | 8080 | `@Path("/locaux")` |
| `/api/emploi-du-temps` | `service-emploi-du-temps` | 8080 | `@Path("/emploi-du-temps")` |

5. **Pas de rewrite URL** — le path est transmis tel quel au pod WildFly (context-root `/` + JAX-RS `/api/...`)
6. **Exemples d'accès Ingress :**
   - `http://planification.local/api/cours`
   - `http://planification.local/api/locaux/disponibles?capaciteMin=30`
   - `http://planification.local/api/emploi-du-temps/jour/LUNDI`

7. **Alternative NodePort** (sans Ingress) : accès direct `http://<IP_NOEUD>:30081/api/cours`, etc.

---

## 6. Difficultés techniques rencontrées et solutions

| # | Problème | Cause | Solution appliquée |
|---|---|---|---|
| 1 | Conteneurs WildFly `Exited(255)` | Fins de ligne Windows **CRLF** dans `entrypoint.sh` → bash Linux rejette le script | `sed -i 's/\r$//'` dans chaque Dockerfile ; `.gitattributes` : `*.sh text eol=lf` ; `start-app.sh` corrige aussi les `.sh` après rsync Vagrant |
| 2 | HTTP **404** sur `/api/cours` | WildFly déploie le WAR sous `/service-catalogue/*` par défaut | `jboss-web.xml` avec `<context-root>/</context-root>` dans chaque service |
| 3 | Erreur JDBC / datasource non trouvé | Module MySQL JDBC absent dans WildFly | `module.xml` + copie `mysql-connector-j-8.3.0.jar` ; configuration datasource via CLI `embed-server` dans `entrypoint.sh` |
| 4 | Conflit horaire retournait HTTP **500** au lieu de **409** | `EJBException` enveloppait `EmploiDuTempsException` à la frontière EJB | `@ApplicationException(rollback = true)` sur `EmploiDuTempsException` + `EmploiDuTempsExceptionMapper` JAX-RS |
| 5 | POST cours retourne **500 DataException** | Code cours > 20 caractères (`VARCHAR(20)`) — ex. `TEST-COURS-1234567890` | `test.sh` utilise codes courts `TST-{timestamp}` ; documentation du piège dans les guides de test |
| 6 | `curl` JSON sous **PowerShell** échoue | PowerShell gère mal les guillemets dans les corps JSON | Utiliser **Git Bash**, fichier JSON temporaire, ou **Postman** |
| 7 | Docker volumes/bind mounts échouent sur **Vagrant vboxsf** | VirtualBox monte `/vagrant` en système de fichiers partagé incompatible avec Docker volumes | `rsync` vers `/home/vagrant/projet2-planification` (ext4 natif) dans `start-app.sh` |
| 8 | Timeout SSH au boot Vagrant (`Timed out while waiting for the machine to boot`) | Premier boot Ubuntu + VirtualBox 7 sur Windows lent | `boot_timeout = 600` ; correctifs VBox (`--uart1 off`, `--ioapic on`) ; host-only network `192.168.56.x` |
| 9 | Ports **8081-8083** déjà utilisés sur Windows | Conflit Docker Desktop local + Vagrant | Vagrant forward **18081-18083** sur l'hôte ; ou arrêter Docker Desktop local |
| 10 | Pod K3s **ImagePullBackOff** | Images `ump/service-*:1.0.0` buildées localement, non poussées vers un registry | `./k8s/scripts/import-images-k3s.sh` (`docker save \| k3s ctr images import`) |
| 11 | MySQL K8s sans données initiales | PVC déjà existant → `init.sql` ignoré au redéploiement | Supprimer PVC : `kubectl delete pvc --all -n planification` puis `kubectl apply -k k8s/` |
| 12 | Emploi-du-temps pod ne démarre pas en K8s | Catalogue/locaux pas encore Ready | InitContainers `wait-for-catalogue` + `wait-for-locaux` (curl) dans le Deployment emploi |
| 13 | Conteneurs pas `healthy` après 3 min | WildFly + MySQL démarrent lentement (~90-120 s) | `start_period: 60s` sur healthchecks ; attendre ; `docker compose logs` |

---

## 7. Captures/preuves de fonctionnement disponibles

**Aucune capture d'écran (.png/.jpg) ni fichier `.log` committé dans le dépôt.**

### Scripts et résultats de tests automatisés

| Fichier | Description |
|---|---|
| `test.sh` | **14 tests d'intégration automatisés** (Git Bash) — healthchecks, POST cours/local/séance, conflit 409, GET jour, DELETE, vérification DISPONIBLE, endpoints lecture ; sortie `[PASS]`/`[FAIL]` ; exit code 0/1 |
| `postman_collection.json` | Collection Postman pour tests manuels des 3 APIs |

### Guides de démo avec commandes curl et résultats attendus

| Fichier | Contenu |
|---|---|
| `DEMO_TEST_CATALOGUE.txt` | Tests pas-à-pas service-catalogue (port 8081) — GET, POST, PUT, DELETE, 404 |
| `DEMO_TEST_LOCAUX.txt` | Tests pas-à-pas service-locaux (port 8082) — CRUD, PATCH disponibilité, filtres |
| `DEMO_TEST_EMPLOI_DU_TEMPS.txt` | Tests pas-à-pas orchestrateur (port 8083) — planification, conflit 409, suppression |
| `GUIDE_DEMO_COMPLET.txt` | Guide maître démo soutenance (Phase 2/3/4, checklist, dépannage) |

### Documentation technique (preuves textuelles / procédures)

| Fichier | Contenu |
|---|---|
| `RAPPORT_GENERAL.txt` | Rapport complet architecture, modèle de données, Docker, tests, FAQ soutenance |
| `PHASE3_VAGRANT.txt` | Guide Vagrant — commandes, architecture VM, dépannage |
| `PHASE4_K8S.txt` | Guide K3s/K8s — déploiement, NodePort, Ingress, dépannage |
| `AYMANE_SERVICE_CATALOGUE.txt` | Documentation détaillée service catalogue (membre trinôme) |
| `AMINE_SERVICE_LOCAUX.txt` | Documentation détaillée service locaux (membre trinôme) |
| `README.md` | Instructions démarrage rapide du projet |

### Commandes pour générer des preuves à l'exécution

```bash
# Phase 2 — Docker Compose
docker compose up --build -d
docker compose ps                    # 6 conteneurs "healthy"
./test.sh                            # 14 [PASS]

# Phase 3 — Vagrant
vagrant up k3s-master
curl http://localhost:18081/api/cours

# Phase 4 — K3s
./k8s/scripts/deploy.sh
kubectl get pods -n planification    # 6 pods Running
export CATALOGUE_URL=http://localhost:30081
export LOCAUX_URL=http://localhost:30082
export EMPLOI_URL=http://localhost:30083
./test.sh
```

### Logs consultables (non stockés dans le repo)

```bash
docker compose logs service-catalogue
docker compose logs service-emploi-du-temps
kubectl logs -n planification deploy/service-catalogue
kubectl logs -n planification statefulset/catalogue-db
```
