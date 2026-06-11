package ma.ump.catalogue.repository;

// ============================================================
//  COUCHE  : Repository (Accès aux données / DAO)
//  SERVICE : service-catalogue
//  RÔLE    : Encapsule toutes les requêtes SQL/JPQL vers la
//            table "cours" via l'EntityManager JPA.
//
//  CONCEPT CLÉS à expliquer au prof :
//  - @Stateless  : EJB sans état — WildFly gère un pool d'instances.
//                  Chaque requête HTTP peut utiliser une instance
//                  différente, donc PAS de champ d'état entre appels.
//  - @PersistenceContext : injection de l'EntityManager par le conteneur.
//                  C'est JPA qui ouvre/ferme les connexions MySQL.
//  - EntityManager : interface JPA centrale pour CRUD (persist, merge,
//                    remove, find, createQuery...).
//  - JPQL (Java Persistence Query Language) : SQL orienté objet —
//                  on écrit "FROM Cours c" au lieu de "FROM cours c".
//  - TypedQuery  : requête paramétrée typée — évite les injections SQL
//                  et le cast manuel.
//  - Optional    : wrapper Java 8 évitant les NullPointerException.
// ============================================================

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import ma.ump.catalogue.entity.Cours;

import java.util.List;
import java.util.Optional;

/**
 * Couche d'accès aux données (Repository / DAO) pour l'entité {@link Cours}.
 *
 * <p><b>Responsabilité unique :</b> toutes les opérations de lecture/écriture
 * vers la table {@code cours} de {@code catalogue_db} passent par cette classe.
 * Aucune logique métier ici — c'est le rôle de {@link ma.ump.catalogue.service.CoursService}.</p>
 *
 * <p><b>Gestion des transactions :</b> les EJB {@code @Stateless} participent
 * automatiquement aux transactions JTA (Java Transaction API) gérées par WildFly.
 * Un {@code em.persist()} est committé au retour de la méthode EJB.</p>
 *
 * <p><b>Unité de persistance :</b> {@code cataloguePU} définie dans
 * {@code META-INF/persistence.xml} — pointe vers le datasource MySQL
 * {@code java:jboss/datasources/CatalogueDS}.</p>
 */
@Stateless
public class CoursRepository {

    /**
     * EntityManager injecté par le conteneur WildFly.
     * Gère le contexte de persistance (1er niveau de cache, transactions JTA).
     * "cataloguePU" = Persistence Unit définie dans persistence.xml.
     */
    @PersistenceContext(unitName = "cataloguePU")
    private EntityManager em;

    /**
     * Retourne TOUS les cours triés alphabétiquement par code.
     *
     * <p>JPQL utilisé : {@code SELECT c FROM Cours c ORDER BY c.code}
     * → traduit par Hibernate en : {@code SELECT * FROM cours ORDER BY code}</p>
     *
     * @return liste complète (vide si aucun cours)
     */
    public List<Cours> findAll() {
        return em.createQuery("SELECT c FROM Cours c ORDER BY c.code", Cours.class)
                .getResultList();
    }

    /**
     * Recherche un cours par sa clé primaire (id).
     *
     * <p>{@code em.find()} utilise le cache de 1er niveau (contexte de persistance)
     * avant d'aller en base. Retourne {@code null} si introuvable,
     * encapsulé dans un {@link Optional} pour éviter les NPE.</p>
     *
     * @param id identifiant technique du cours (clé primaire)
     * @return {@code Optional.of(cours)} si trouvé, {@code Optional.empty()} sinon
     */
    public Optional<Cours> findById(Long id) {
        return Optional.ofNullable(em.find(Cours.class, id));
    }

    /**
     * Recherche un cours par son code unique (ex: "INF301").
     *
     * <p>Utilise {@code getSingleResult()} qui lance {@link NoResultException}
     * si aucun résultat — capturée ici pour retourner {@code Optional.empty()}.</p>
     *
     * @param code code du cours (sensible à la casse)
     * @return cours trouvé ou vide
     */
    public Optional<Cours> findByCode(String code) {
        try {
            TypedQuery<Cours> query = em.createQuery(
                    "SELECT c FROM Cours c WHERE c.code = :code", Cours.class);
            // :code = paramètre nommé — protège contre l'injection SQL
            query.setParameter("code", code);
            return Optional.of(query.getSingleResult());
        } catch (NoResultException ex) {
            // NoResultException est normale ici (cours absent) → on retourne vide
            return Optional.empty();
        }
    }

    /**
     * Persiste un nouveau cours en base de données.
     *
     * <p>{@code em.persist(cours)} insère une nouvelle ligne dans la table.
     * Après le commit JTA, le champ {@code id} est automatiquement rempli
     * par MySQL (AUTO_INCREMENT) et visible sur l'objet retourné.</p>
     *
     * @param cours cours à insérer (id doit être null)
     * @return cours persisté avec l'id généré
     */
    public Cours create(Cours cours) {
        em.persist(cours);
        // Après persist + flush implicite, cours.getId() contient l'id généré
        return cours;
    }

    /**
     * Met à jour un cours existant (UPDATE SQL).
     *
     * <p>{@code em.merge(cours)} : si l'entité est détachée (hors contexte de
     * persistance), merge la recharge et synchronise les modifications.</p>
     *
     * @param cours cours avec les nouvelles valeurs (id obligatoire)
     * @return cours fusionné et géré par le contexte de persistance
     */
    public Cours update(Cours cours) {
        return em.merge(cours);
    }

    /**
     * Supprime un cours par son identifiant (DELETE SQL).
     *
     * <p>Pattern : find → remove. Si le cours n'existe pas, retourne false
     * sans lever d'exception (laisse la couche service décider de la réponse HTTP).</p>
     *
     * @param id identifiant du cours à supprimer
     * @return {@code true} si supprimé, {@code false} si introuvable
     */
    public boolean delete(Long id) {
        Cours cours = em.find(Cours.class, id);
        if (cours == null) {
            return false; // cours déjà absent → le service retournera HTTP 404
        }
        em.remove(cours);
        return true;
    }

    /**
     * Vérifie l'existence d'un code cours en excluant optionnellement un id.
     *
     * <p>Utilisé pour deux cas :
     * <ul>
     *   <li>Création ({@code id = null}) : le code ne doit pas exister du tout.</li>
     *   <li>Mise à jour ({@code id != null}) : le code peut appartenir AU MÊME cours
     *       mais pas à un autre.</li>
     * </ul>
     * La clause {@code (:id IS NULL OR c.id <> :id)} gère les deux cas en JPQL.</p>
     *
     * @param code code à vérifier
     * @param id   identifiant à exclure (null pour vérification en création)
     * @return {@code true} si le code est déjà utilisé par un autre cours
     */
    public boolean existsByCode(String code, Long id) {
        TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(c) FROM Cours c WHERE c.code = :code AND (:id IS NULL OR c.id <> :id)",
                Long.class);
        query.setParameter("code", code);
        query.setParameter("id", id);
        return query.getSingleResult() > 0;
    }
}
