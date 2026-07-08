# =============================================================================
# service-emploi-du-temps — Image Docker multi-stage
# Projet 2 — Planification & Logistique — UMP Oujda
#
# Microservice REST (Jakarta EE / WildFly) : planification des emplois du temps.
# Agrège les données des services catalogue et locaux via HTTP.
# Build Maven → déploiement WAR sur WildFly 27 + connecteur MySQL.
#
# Variables d'environnement au runtime (voir entrypoint.sh) :
#   DATASOURCE_NAME, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
#   CATALOGUE_BASE_URL, LOCAUX_BASE_URL (URLs internes Docker/K8s)
# =============================================================================

# --- Stage 1 : BUILD ---------------------------------------------------------
# Image Maven + JDK 17 pour compiler le WAR sans inclure les outils de build
# dans l'image finale (réduction de la taille et de la surface d'attaque).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Téléchargement des dépendances en cache Docker (layer séparé du code source)
COPY pom.xml .
RUN mvn dependency:go-offline

# Compilation du projet Java → target/service-emploi-du-temps.war
COPY src ./src
RUN mvn clean package -DskipTests

# --- Stage 2 : RUN -----------------------------------------------------------
# Serveur d'application WildFly 27 avec JDK 17 (image officielle Quay.io)
FROM quay.io/wildfly/wildfly:27.0.1.Final-jdk17

# Installation du driver MySQL comme module WildFly (requis pour la datasource)
USER root
RUN mkdir -p /opt/jboss/wildfly/modules/com/mysql/main
COPY --from=build /root/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar \
    /opt/jboss/wildfly/modules/com/mysql/main/mysql-connector-j.jar
COPY docker/module.xml /opt/jboss/wildfly/modules/com/mysql/main/module.xml

# Script d'initialisation : configure la datasource JDBC au démarrage
COPY docker/entrypoint.sh /opt/jboss/entrypoint.sh
# Correction CRLF Windows + permissions d'exécution
RUN sed -i 's/\r$//' /opt/jboss/entrypoint.sh \
    && chmod +x /opt/jboss/entrypoint.sh \
    && chown -R jboss:jboss /opt/jboss/wildfly/modules/com /opt/jboss/entrypoint.sh

# Déploiement automatique du WAR (WildFly hot-deploy via standalone/deployments/)
USER jboss
COPY --from=build /app/target/service-emploi-du-temps.war $JBOSS_HOME/standalone/deployments/

# Nom de la datasource WildFly (EmploiDS) — lu par entrypoint.sh
ENV DATASOURCE_NAME=EmploiDS
# URLs des microservices amont (résolution DNS interne Docker Compose / K8s)
ENV CATALOGUE_BASE_URL=http://service-catalogue:8080
ENV LOCAUX_BASE_URL=http://service-locaux:8080
EXPOSE 8080

ENTRYPOINT ["/bin/bash", "/opt/jboss/entrypoint.sh"]
