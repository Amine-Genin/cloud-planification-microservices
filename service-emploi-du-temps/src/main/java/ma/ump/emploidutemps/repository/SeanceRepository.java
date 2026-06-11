package ma.ump.emploidutemps.repository;

// ============================================================
//  COUCHE  : Repository (Accès aux données)
//  SERVICE : service-emploi-du-temps
//  RÔLE    : Opérations SQL/JPQL sur la table "seances" de emploi_db.
//            Inclut la logique de détection de conflit horaire.
//
//  MÉTHODE CLÉ : existeConflit()
//  ─────────────────────────────
//  Recherche des séances existantes pour la MÊME salle le MÊME jour,
//  puis teste le chevauchement horaire via HeureUtil.seChevauchent().
//
//  POURQUOI en Java et pas en SQL pur ?
//  Parce que les heures sont stockées en VARCHAR(5) "HH:mm".
//  Une comparaison de type "08:30" < "10:00" fonctionne en tri
//  alphabétique pour les heures de même longueur, mais la logique
//  de chevauchement est plus claire avec HeureUtil en Java.
//  En production on utiliserait des colonnes TIME et une requête SQL
//  avec OVERLAPS ou comparaison directe.
// ============================================================

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import ma.ump.emploidutemps.entity.JourSemaine;
import ma.ump.emploidutemps.entity.Seance;
import ma.ump.emploidutemps.util.HeureUtil;

import java.util.List;
import java.util.Optional;

/**
 * Couche d'accès aux données pour l'entité {@link Seance}.
 *
 * <p><b>Base de données :</b> table {@code seances} dans {@code emploi_db}.</p>
 *
 * <p><b>Fonctionnalité clé :</b> la méthode {@link #existeConflit} est le cœur
 * de la règle métier anti-doublon — elle empêche deux séances d'occuper la même
 * salle au même créneau horaire le même jour.</p>
 */
@Stateless
public class SeanceRepository {

    /**
     * EntityManager pointant vers l'unité de persistance "emploiPU"
     * (datasource EmploiDS → base emploi_db).
     */
    @PersistenceContext(unitName = "emploiPU")
    private EntityManager em;

    /**
     * Retourne toutes les séances planifiées, triées par jour puis heure de début.
     *
     * <p>JPQL : {@code SELECT s FROM Seance s ORDER BY s.jour, s.heureDebut}
     * → le tri par jour est alphabétique sur l'enum (JEUDI, LUNDI, MARDI...).
     * Pour un tri chronologique correct il faudrait un CASE WHEN ou une colonne ordinale.</p>
     *
     * @return liste de toutes les séances (peut être vide)
     */
    public List<Seance> findAll() {
        return em.createQuery("SELECT s FROM Seance s ORDER BY s.jour, s.heureDebut", Seance.class)
                .getResultList();
    }

    /**
     * Recherche une séance par identifiant.
     *
     * @param id clé primaire de la séance
     * @return séance trouvée ou vide
     */
    public Optional<Seance> findById(Long id) {
        return Optional.ofNullable(em.find(Seance.class, id));
    }

    /**
     * Retourne toutes les séances d'un jour donné, triées par heure de début.
     *
     * <p>Utilisé par l'endpoint {@code GET /api/emploi-du-temps/jour/{jour}}
     * pour afficher le programme d'une journée.</p>
     *
     * @param jour jour de la semaine (enum JourSemaine)
     * @return séances du jour, triées
     */
    public List<Seance> findByJour(JourSemaine jour) {
        TypedQuery<Seance> query = em.createQuery(
                "SELECT s FROM Seance s WHERE s.jour = :jour ORDER BY s.heureDebut", Seance.class);
        query.setParameter("jour", jour);
        return query.getResultList();
    }

    /**
     * Persiste une nouvelle séance en base de données.
     *
     * @param seance séance à insérer (id doit être null)
     * @return séance avec l'id généré par MySQL
     */
    public Seance create(Seance seance) {
        em.persist(seance);
        return seance;
    }

    /**
     * Supprime une séance de la base de données.
     *
     * <p>Vérifie si l'entité est gérée par le contexte de persistance ({@code em.contains()}).
     * Si non (entité détachée), la recharge avec {@code em.merge()} avant suppression.</p>
     *
     * @param seance séance à supprimer (doit avoir un id valide)
     */
    public void delete(Seance seance) {
        // merge() si l'entité est détachée (hors contexte de persistance courant)
        Seance managed = em.contains(seance) ? seance : em.merge(seance);
        em.remove(managed);
    }

    /**
     * Détecte un conflit horaire pour une salle et un jour donnés.
     *
     * <p><b>Algorithme :</b>
     * <ol>
     *   <li>Charger toutes les séances existantes pour {@code (localId, jour)}</li>
     *   <li>Pour chaque séance existante, tester le chevauchement avec
     *       {@link HeureUtil#seChevauchent(String, String, String, String)}</li>
     *   <li>Retourner {@code true} si AU MOINS UN chevauchement est détecté</li>
     * </ol>
     * </p>
     *
     * <p><b>Exemple :</b>
     * <pre>
     *   Séance existante : LUNDI, salle 5, 08:00-10:00
     *   Nouvelle séance  : LUNDI, salle 5, 09:00-11:00
     *   → chevauchement détecté → retourne true → HTTP 409 Conflict
     * </pre>
     * </p>
     *
     * <p><b>excludeId :</b> paramètre pour la mise à jour — exclut la séance
     * en cours de modification pour ne pas détecter de conflit avec elle-même.
     * {@code null} en création (pas d'exclusion).</p>
     *
     * @param localId     identifiant de la salle à vérifier
     * @param jour        jour de la semaine
     * @param heureDebut  heure de début de la nouvelle séance (HH:mm)
     * @param heureFin    heure de fin de la nouvelle séance (HH:mm)
     * @param excludeId   id de séance à exclure (null en création)
     * @return {@code true} si un conflit existe (→ HTTP 409 dans le service)
     */
    public boolean existeConflit(Long localId, JourSemaine jour,
                                 String heureDebut, String heureFin, Long excludeId) {
        // Requête JPQL : toutes les séances de la même salle le même jour
        // (:excludeId IS NULL OR s.id <> :excludeId) → exclut la séance en modification
        TypedQuery<Seance> query = em.createQuery(
                "SELECT s FROM Seance s WHERE s.localId = :localId AND s.jour = :jour "
                        + "AND (:excludeId IS NULL OR s.id <> :excludeId)",
                Seance.class);
        query.setParameter("localId", localId);
        query.setParameter("jour", jour);
        query.setParameter("excludeId", excludeId);

        List<Seance> seancesExistantes = query.getResultList();

        // Pour chaque séance existante, test de chevauchement via HeureUtil
        for (Seance existante : seancesExistantes) {
            if (HeureUtil.seChevauchent(
                    heureDebut, heureFin,
                    existante.getHeureDebut(), existante.getHeureFin())) {
                return true; // conflit détecté → arrêt immédiat
            }
        }
        return false; // aucun chevauchement → planification possible
    }
}
