package ma.ump.catalogue.service;

// ============================================================
//  COUCHE  : Service (Logique métier)
//  SERVICE : service-catalogue
//  RÔLE    : Contient toutes les règles métier liées aux cours :
//            validation, unicité du code, orchestration des appels
//            vers CoursRepository.
//
//  CONCEPT CLÉS à expliquer au prof :
//  - Séparation des responsabilités (Separation of Concerns) :
//      · Resource (REST) : HTTP, codes de retour, sérialisation JSON
//      · Service         : logique métier, validations, règles
//      · Repository      : accès base de données uniquement
//  - @Stateless EJB     : WildFly gère le cycle de vie et les transactions.
//  - @Inject            : injection de dépendance CDI (pas de new !).
//                         WildFly injecte automatiquement CoursRepository.
//  - IllegalArgumentException : exception non-checked utilisée pour
//                         signaler des données invalides (400 / 404).
// ============================================================

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import ma.ump.catalogue.entity.Cours;
import ma.ump.catalogue.repository.CoursRepository;

import java.util.List;
import java.util.Optional;

/**
 * Service métier gérant le catalogue des cours universitaires.
 *
 * <p><b>Responsabilité :</b> validation des données, contrôle des règles métier
 * (unicité du code, credits >= 0, etc.) avant toute écriture en base.</p>
 *
 * <p><b>Architecture en couches :</b>
 * <pre>
 *   HTTP Request
 *       ↓
 *   CoursResource   (REST — traduit HTTP en appels Java)
 *       ↓
 *   CoursService    (Logique métier — CETTE CLASSE)
 *       ↓
 *   CoursRepository (SQL — accès base de données)
 *       ↓
 *   catalogue_db    (MySQL 8)
 * </pre>
 * </p>
 *
 * <p><b>Transactions :</b> chaque méthode EJB {@code @Stateless} s'exécute dans
 * une transaction JTA. En cas d'exception, la transaction est annulée (rollback).</p>
 */
@Stateless
public class CoursService {

    /**
     * Injection CDI du repository — WildFly fournit l'instance automatiquement.
     * Principe : CoursService NE CRÉE PAS CoursRepository (pas de "new").
     * L'injection de dépendance (IoC) délègue la création au conteneur.
     */
    @Inject
    private CoursRepository coursRepository;

    /**
     * Retourne la liste complète des cours du catalogue.
     *
     * <p>Délégation directe au repository sans logique supplémentaire.
     * Retourne une liste vide si aucun cours en base (jamais null).</p>
     *
     * @return liste de tous les cours (peut être vide)
     */
    public List<Cours> listerTous() {
        return coursRepository.findAll();
    }

    /**
     * Recherche un cours par son identifiant technique.
     *
     * @param id identifiant du cours
     * @return {@code Optional.of(cours)} si trouvé, vide sinon
     */
    public Optional<Cours> trouverParId(Long id) {
        return coursRepository.findById(id);
    }

    /**
     * Recherche un cours par son code unique.
     *
     * @param code code du cours (ex: "INF301")
     * @return cours éventuellement trouvé
     */
    public Optional<Cours> trouverParCode(String code) {
        return coursRepository.findByCode(code);
    }

    /**
     * Crée un nouveau cours après validation complète.
     *
     * <p><b>Règles métier appliquées :</b>
     * <ol>
     *   <li>Validation des champs obligatoires (code, intitulé, credits >= 0)</li>
     *   <li>Unicité du code cours dans le catalogue (→ 400 si doublon)</li>
     *   <li>Persistance via le repository</li>
     * </ol>
     * </p>
     *
     * @param cours données du cours à créer
     * @return cours créé avec son id généré
     * @throws IllegalArgumentException si validation échoue ou code existant
     */
    public Cours creer(Cours cours) {
        validerCours(cours, null);
        // Vérification unicité : le code ne doit pas exister (id=null → pas d'exclusion)
        if (coursRepository.existsByCode(cours.getCode(), null)) {
            throw new IllegalArgumentException("Le code cours existe déjà : " + cours.getCode());
        }
        return coursRepository.create(cours);
    }

    /**
     * Met à jour un cours existant.
     *
     * <p><b>Flux :</b>
     * <ol>
     *   <li>Vérifier que le cours existe (→ 404 si absent)</li>
     *   <li>Valider les nouvelles données</li>
     *   <li>Vérifier que le nouveau code n'est pas utilisé par un autre cours</li>
     *   <li>Appliquer les modifications et sauvegarder</li>
     * </ol>
     * </p>
     *
     * @param id    identifiant du cours à modifier
     * @param cours nouvelles valeurs
     * @return cours mis à jour
     * @throws IllegalArgumentException si cours introuvable ou données invalides
     */
    public Cours mettreAJour(Long id, Cours cours) {
        // Étape 1 : charger l'entité existante (lève exception si absent → HTTP 404)
        Cours existant = coursRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cours introuvable avec l'id : " + id));

        validerCours(cours, id);

        // Étape 2 : unicité du code en excluant l'id courant (un cours peut garder son propre code)
        if (coursRepository.existsByCode(cours.getCode(), id)) {
            throw new IllegalArgumentException("Le code cours existe déjà : " + cours.getCode());
        }

        // Étape 3 : mise à jour champ par champ de l'entité gérée (managed entity)
        existant.setCode(cours.getCode());
        existant.setIntitule(cours.getIntitule());
        existant.setSyllabus(cours.getSyllabus());
        existant.setPrerequis(cours.getPrerequis());
        existant.setCredits(cours.getCredits());

        // em.merge() synchronise les modifications en base à la fin de la transaction
        return coursRepository.update(existant);
    }

    /**
     * Supprime un cours par son identifiant.
     *
     * @param id identifiant du cours à supprimer
     * @return {@code true} si supprimé, {@code false} si introuvable (→ 404 dans Resource)
     */
    public boolean supprimer(Long id) {
        return coursRepository.delete(id);
    }

    /**
     * Valide les champs obligatoires d'un cours.
     *
     * <p>Vérifie que les champs non-nullables sont présents et cohérents.
     * Lance {@link IllegalArgumentException} pour signaler l'erreur à la Resource
     * qui la convertira en réponse HTTP 400 (Bad Request).</p>
     *
     * @param cours cours à valider
     * @param id    identifiant en mise à jour (null en création — non utilisé ici)
     * @throws IllegalArgumentException si un champ obligatoire est manquant/invalide
     */
    private void validerCours(Cours cours, Long id) {
        if (cours == null) {
            throw new IllegalArgumentException("Le corps de la requête est obligatoire");
        }
        // code non null et non vide (isBlank() gère aussi les espaces seuls)
        if (cours.getCode() == null || cours.getCode().isBlank()) {
            throw new IllegalArgumentException("Le code du cours est obligatoire");
        }
        // ATTENTION : code max 20 caractères (contrainte SQL VARCHAR(20))
        // Un code trop long passera la validation Java mais échouera en base → HTTP 500
        if (cours.getIntitule() == null || cours.getIntitule().isBlank()) {
            throw new IllegalArgumentException("L'intitulé du cours est obligatoire");
        }
        if (cours.getCredits() < 0) {
            throw new IllegalArgumentException("Le nombre de crédits doit être positif ou nul");
        }
    }
}
