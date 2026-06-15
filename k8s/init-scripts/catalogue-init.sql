-- Données initiales — Service Catalogue Cours (UMP Oujda)
-- Exécuté automatiquement au premier démarrage de catalogue-db

USE catalogue_db;

CREATE TABLE IF NOT EXISTS cours (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL,
    intitule VARCHAR(255) NOT NULL,
    syllabus TEXT,
    prerequis VARCHAR(500),
    credits INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cours_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO cours (code, intitule, syllabus, prerequis, credits) VALUES
('INF301', 'Cloud Computing', 'Introduction au cloud public/privé, conteneurisation, orchestration Kubernetes et services managés.', 'INF201, INF202', 6),
('INF302', 'Architecture des Microservices', 'Patterns microservices, API REST, messagerie, résilience et observabilité.', 'INF301', 6),
('INF303', 'Sécurité des Systèmes d''Information', 'Cryptographie, authentification, OWASP Top 10 et durcissement des applications.', 'INF202', 5),
('INF304', 'Big Data et Analytics', 'Hadoop, Spark, entrepôts de données et pipelines ETL pour l''analyse massive.', 'INF201', 6),
('INF305', 'Intelligence Artificielle', 'Apprentissage supervisé/non supervisé, réseaux de neurones et MLOps.', 'INF201, MAT201', 6),
('INF306', 'Développement Mobile', 'Android/iOS, Flutter, cycle de vie des applications et publication sur stores.', 'INF202', 5),
('INF307', 'Réseaux Avancés', 'Routage dynamique, VLAN, QoS, SDN et sécurité périmétrique.', 'INF101', 5),
('INF308', 'Génie Logiciel', 'UML, méthodes agiles, tests automatisés et intégration continue.', 'INF202', 5),
('INF309', 'Base de Données Avancées', 'Optimisation SQL, indexation, réplication, NoSQL et modélisation distribuée.', 'INF201', 6),
('INF310', 'Projet de Fin d''Études', 'Encadrement du PFE : cahier des charges, revues techniques et soutenance.', 'INF301, INF302', 8);
