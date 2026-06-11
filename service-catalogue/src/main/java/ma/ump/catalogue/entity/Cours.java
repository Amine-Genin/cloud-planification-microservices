package ma.ump.catalogue.entity;

// ============================================================
//  COUCHE  : Entity (Modèle de données / Persistance)
//  SERVICE : service-catalogue
//  RÔLE    : Représente un cours dans la base de données MySQL
//            via JPA (Java Persistence API).
//
//  CONCEPT CLÉS à expliquer au prof :
//  - @Entity     : dit à JPA que cette classe est une TABLE en base.
//  - @Table      : nomme explicitement la table SQL ("cours").
//  - @Id         : clé primaire — identifiant unique de la ligne.
//  - @GeneratedValue(IDENTITY) : auto-incrément MySQL (1, 2, 3...).
//  - @Column     : mappage champ Java ↔ colonne SQL avec contraintes.
//  - @Lob        : pour les grands textes (syllabus peut être long).
//  - Serializable : requis car JPA peut mettre l'entité en cache distribué.
//  - UniqueConstraint : contrainte d'unicité au niveau SQL (uk_cours_code).
// ============================================================

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.io.Serializable;

/**
 * Entité JPA représentant un cours du catalogue universitaire UMP Oujda.
 *
 * <p><b>Table SQL correspondante :</b> {@code cours} dans la base {@code catalogue_db}.</p>
 *
 * <p><b>Colonnes :</b>
 * <ul>
 *   <li>{@code id}       — clé primaire auto-incrémentée (BIGINT)</li>
 *   <li>{@code code}     — code unique du cours, max 20 caractères (ex: INF301)</li>
 *   <li>{@code intitule} — nom du cours, max 255 caractères</li>
 *   <li>{@code syllabus} — contenu détaillé (TEXT illimité)</li>
 *   <li>{@code prerequis}— prérequis éventuels (VARCHAR 500)</li>
 *   <li>{@code credits}  — nombre de crédits ECTS (entier)</li>
 * </ul>
 * </p>
 *
 * <p><b>Contrainte d'intégrité :</b> le code cours est UNIQUE en base
 * (contrainte SQL {@code uk_cours_code}). Une tentative d'insertion d'un code
 * déjà existant lève une {@link jakarta.persistence.PersistenceException}.</p>
 *
 * <p><b>Principe microservice :</b> cette entité appartient UNIQUEMENT au
 * service-catalogue. Les autres services (emploi-du-temps) n'accèdent jamais
 * directement à cette table ; ils passent par l'API REST HTTP.</p>
 */
@Entity
@Table(name = "cours", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cours_code", columnNames = "code")
})
public class Cours implements Serializable {

    // serialVersionUID : requis pour la sérialisation Java (transfert réseau / cache JPA)
    private static final long serialVersionUID = 1L;

    /**
     * Identifiant technique généré automatiquement par MySQL (AUTO_INCREMENT).
     * Jamais fourni lors d'une création (POST), seulement retourné dans la réponse.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Code unique du cours (ex: "INF301", "MAT201").
     * Limité à 20 caractères par la contrainte SQL.
     * ATTENTION : dépasser 20 caractères déclenche une DataException → HTTP 500.
     */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /**
     * Intitulé complet du cours (ex: "Cloud Computing").
     * Champ obligatoire validé en service avant persistence.
     */
    @Column(nullable = false, length = 255)
    private String intitule;

    /**
     * Syllabus détaillé du cours (contenu pédagogique, objectifs, plan...).
     * Stocké en TEXT MySQL — aucune limite de taille.
     * @Lob indique à JPA de traiter ce champ comme un Large OBject.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String syllabus;

    /**
     * Prérequis du cours (codes d'autres cours nécessaires).
     * Exemple : "INF201, INF202". Champ optionnel.
     */
    @Column(length = 500)
    private String prerequis;

    /**
     * Nombre de crédits ECTS du cours (European Credit Transfer System).
     * Valeur typique entre 1 et 10. Validé >= 0 dans CoursService.
     */
    private int credits;

    /**
     * Constructeur sans argument OBLIGATOIRE pour JPA.
     * JPA instancie les entités via réflexion (il a besoin de ce constructeur).
     */
    public Cours() {
    }

    /**
     * Constructeur complet — pratique pour les tests unitaires et l'init.sql.
     *
     * @param code      code unique du cours (max 20 caractères)
     * @param intitule  intitulé lisible du cours
     * @param syllabus  contenu du syllabus (peut être null)
     * @param prerequis prérequis éventuels (peut être null)
     * @param credits   nombre de crédits ECTS (>= 0)
     */
    public Cours(String code, String intitule, String syllabus, String prerequis, int credits) {
        this.code = code;
        this.intitule = intitule;
        this.syllabus = syllabus;
        this.prerequis = prerequis;
        this.credits = credits;
    }

    // ============================================================
    //  GETTERS / SETTERS
    //  Requis par JPA (Hibernate) et par le sérialiseur JSON (Jackson/Yasson)
    //  pour convertir l'entité en JSON dans la réponse REST.
    // ============================================================

    /** @return identifiant technique auto-incrémenté */
    public Long getId() { return id; }

    /** @param id identifiant technique (jamais positionné manuellement) */
    public void setId(Long id) { this.id = id; }

    /** @return code unique du cours (ex: "INF301") */
    public String getCode() { return code; }

    /** @param code code du cours (max 20 caractères) */
    public void setCode(String code) { this.code = code; }

    /** @return intitulé complet du cours */
    public String getIntitule() { return intitule; }

    /** @param intitule intitulé du cours */
    public void setIntitule(String intitule) { this.intitule = intitule; }

    /** @return contenu du syllabus (peut être null) */
    public String getSyllabus() { return syllabus; }

    /** @param syllabus contenu du syllabus */
    public void setSyllabus(String syllabus) { this.syllabus = syllabus; }

    /** @return prérequis du cours (peut être null) */
    public String getPrerequis() { return prerequis; }

    /** @param prerequis prérequis du cours */
    public void setPrerequis(String prerequis) { this.prerequis = prerequis; }

    /** @return nombre de crédits ECTS */
    public int getCredits() { return credits; }

    /** @param credits nombre de crédits ECTS (>= 0) */
    public void setCredits(int credits) { this.credits = credits; }
}
