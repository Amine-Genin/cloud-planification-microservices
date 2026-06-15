-- Données initiales — Service Locaux (UMP Oujda)
-- Exécuté automatiquement au premier démarrage de locaux-db

USE locaux_db;

CREATE TABLE IF NOT EXISTS locaux (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(30) NOT NULL,
    nom VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    capacite INT NOT NULL,
    batiment VARCHAR(100),
    etage INT NOT NULL,
    projecteur TINYINT(1) NOT NULL DEFAULT 0,
    tableauNumerique TINYINT(1) NOT NULL DEFAULT 0,
    climatisation TINYINT(1) NOT NULL DEFAULT 0,
    accessiblePMR TINYINT(1) NOT NULL DEFAULT 0,
    disponibilite VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_local_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO locaux (code, nom, type, capacite, batiment, etage, projecteur, tableauNumerique, climatisation, accessiblePMR, disponibilite) VALUES
('AMPHI-A', 'Amphi Ibn Khaldoun', 'AMPHI', 200, 'Faculté des Sciences', 0, 1, 1, 1, 1, 'DISPONIBLE'),
('AMPHI-B', 'Amphi Ibn Rochd', 'AMPHI', 180, 'Faculté des Sciences', 0, 1, 1, 1, 1, 'DISPONIBLE'),
('SALLE-C101', 'Salle Cours 101', 'SALLE_COURS', 45, 'Bloc C', 1, 1, 0, 1, 0, 'DISPONIBLE'),
('SALLE-C102', 'Salle Cours 102', 'SALLE_COURS', 40, 'Bloc C', 1, 1, 0, 1, 1, 'DISPONIBLE'),
('TP-L1', 'Laboratoire Réseaux L1', 'SALLE_TP', 30, 'Bloc Informatique', 2, 1, 1, 1, 0, 'DISPONIBLE'),
('TP-L2', 'Laboratoire Systèmes L2', 'SALLE_TP', 28, 'Bloc Informatique', 2, 1, 1, 1, 0, 'DISPONIBLE'),
('LABO-IA', 'Laboratoire Intelligence Artificielle', 'LABO', 25, 'Bloc Informatique', 3, 1, 1, 1, 1, 'DISPONIBLE'),
('LABO-SEC', 'Laboratoire Cybersécurité', 'LABO', 22, 'Bloc Informatique', 3, 1, 1, 1, 0, 'DISPONIBLE'),
('SALLE-D201', 'Salle TD D201', 'SALLE_COURS', 35, 'Bloc D', 2, 1, 0, 1, 0, 'DISPONIBLE'),
('AMPHI-C', 'Amphi Al Qarawiyyin', 'AMPHI', 250, 'Campus Central', 0, 1, 1, 1, 1, 'EN_MAINTENANCE');
