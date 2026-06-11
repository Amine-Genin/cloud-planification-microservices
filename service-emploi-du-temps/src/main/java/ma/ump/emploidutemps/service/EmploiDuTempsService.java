package ma.ump.emploidutemps.service;

// ============================================================
//  COUCHE  : Service (Logique métier — ORCHESTRATEUR)
//  SERVICE : service-emploi-du-temps
//  PORT    : 8083 → http://localhost:8083/api/emploi-du-temps
//  RÔLE    : Service le plus complexe du projet. Orchestre les appels
//            vers les deux autres microservices (catalogue + locaux)
//            pour planifier des séances sans conflit horaire.
//
//  CONCEPT CLÉS à expliquer au prof :
//
//  1. PATTERN ORCHESTRATEUR (vs. chorégraphie) :
//     Ce service dirige activement le flux — il appelle catalogue ET locaux
//     dans un ordre précis, comme un chef d'orchestre.
//
//  2. COUPLAGE UNIDIRECTIONNEL :
//     emploi → catalogue  (OUI)
//     emploi → locaux     (OUI)
//     catalogue → emploi  (NON — catalogue ignore emploi)
//     locaux → emploi     (NON — locaux ignore emploi)
//
//  3. FLUX planifierSeance() (5 étapes atomiques) :
//     1) Vérifier cours existe  → CatalogueClient → GET /api/cours/{id}
//     2) Vérifier salle DISPO   → LocalClient     → GET /api/locaux/disponibles
//     3) Détecter conflit SQL   → SeanceRepository → requête JPQL locale
//     4) Réserver salle (OCCUPE)→ LocalClient     → PATCH /api/locaux/{id}/disponibilite
//     5) Persister séance       → SeanceRepository → INSERT en base
//
//  4. ROLLBACK COMPENSATOIRE :
//     Si l'étape 5 échoue, on libère la salle (étape 4 annulée manuellement).
//     Pas de transaction distribuée (2PC) ici — compromis pragmatique.
//
//  5. EmploiDuTempsException :
//     Porte un code HTTP — permet au REST Resource de retourner
//     400, 404 ou 409 selon la nature de l'erreur.
// ============================================================

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import ma.ump.emploidutemps.client.CatalogueClient;
import ma.ump.emploidutemps.client.LocalClient;
import ma.ump.emploidutemps.entity.JourSemaine;
import ma.ump.emploidutemps.entity.Seance;
import ma.ump.emploidutemps.exception.EmploiDuTempsException;
import ma.ump.emploidutemps.repository.SeanceRepository;
import ma.ump.emploidutemps.util.HeureUtil;

import java.util.List;
import java.util.Optional;

/**
 * Service métier central de planification de l'emploi du temps universitaire.
 *
 * <p><b>Rôle :</b> orchestrer la planification des séances en coordonnant les
 * microservices catalogue et locaux, et en détectant les conflits horaires.</p>
 *
 * <p><b>Flux complet de planification :</b>
 * <pre>
 *   Client HTTP (Postman / curl / test.sh)
 *       │ POST /api/emploi-du-temps
 *       ↓
 *   EmploiDuTempsResource.planifier()
 *       ↓
 *   EmploiDuTempsService.planifierSeance()   ← CETTE CLASSE
 *       ├─ [1] CatalogueClient → GET http://service-catalogue:8080/api/cours/{id}
 *       ├─ [2] LocalClient → GET http://service-locaux:8080/api/locaux/disponibles
 *       ├─ [3] SeanceRepository.existeConflit() → requête JPQL locale
 *       ├─ [4] LocalClient → PATCH http://service-locaux:8080/api/locaux/{id}/disponibilite?valeur=OCCUPE
 *       └─ [5] SeanceRepository.create() → INSERT INTO seances
 * </pre>
 * </p>
 */
@Stateless
public class EmploiDuTempsService {

    // Constantes pour les codes HTTP — évitent les "magic numbers" dans le code
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409; // 409 = conflit de ressource (RFC 7231)

    /** Accès en base aux séances planifiées (emploi_db) */
    @Inject
    private SeanceRepository seanceRepository;

    /**
     * Client HTTP vers service-catalogue.
     * Injecté par CDI — scopé @ApplicationScoped (une seule instance partagée).
     */
    @Inject
    private CatalogueClient catalogueClient;

    /**
     * Client HTTP vers service-locaux.
     * Injecté par CDI — scopé @ApplicationScoped.
     */
    @Inject
    private LocalClient localClient;

    /**
     * Retourne toutes les séances planifiées triées par jour et heure.
     *
     * @return liste de toutes les séances (peut être vide)
     */
    public List<Seance> listerToutes() {
        return seanceRepository.findAll();
    }

    /**
     * Recherche une séance par son identifiant.
     *
     * @param id identifiant de la séance
     * @return séance éventuellement trouvée
     */
    public Optional<Seance> trouverParId(Long id) {
        return seanceRepository.findById(id);
    }

    /**
     * Retourne toutes les séances prévues un jour de semaine donné.
     *
     * @param jour jour de la semaine (enum JourSemaine)
     * @return séances du jour triées par heure de début
     * @throws EmploiDuTempsException si jour null (HTTP 400)
     */
    public List<Seance> listerParJour(JourSemaine jour) {
        if (jour == null) {
            throw new EmploiDuTempsException("Le jour est obligatoire", HTTP_BAD_REQUEST);
        }
        return seanceRepository.findByJour(jour);
    }

    /**
     * Planifie une nouvelle séance — cœur métier du projet.
     *
     * <p><b>Algorithme en 5 étapes :</b></p>
     *
     * <p><b>Étape 1 — Validation locale :</b> vérifie que tous les champs
     * obligatoires sont présents et cohérents (heureDebut < heureFin, etc.).</p>
     *
     * <p><b>Étape 2 — Vérification cours :</b> appel HTTP GET vers service-catalogue
     * pour confirmer que le cours (coursId) existe. Si 404 → HTTP 400 avec message.</p>
     *
     * <p><b>Étape 3 — Vérification salle disponible :</b> appel HTTP GET vers
     * service-locaux pour lister les locaux DISPONIBLES. Si la salle demandée
     * n'est pas dans cette liste → HTTP 400.</p>
     *
     * <p><b>Étape 4 — Détection de conflit horaire :</b> requête JPQL locale dans
     * emploi_db. Cherche une séance existante pour la même salle, le même jour,
     * avec des créneaux qui se chevauchent ({@link HeureUtil#seChevauchent}).
     * Si conflit → HTTP 409 Conflict.</p>
     *
     * <p><b>Étape 5 — Réservation salle :</b> PATCH vers service-locaux pour
     * passer la salle de DISPONIBLE à OCCUPE.</p>
     *
     * <p><b>Étape 6 — Persistance :</b> INSERT de la séance en base emploi_db.
     * En cas d'échec, on tente de libérer la salle (compensation manuelle).</p>
     *
     * @param seance données de la séance à planifier
     * @return séance créée avec son id
     * @throws EmploiDuTempsException avec code HTTP approprié selon l'erreur
     */
    public Seance planifierSeance(Seance seance) {
        // Étape 1 : validation locale (champs obligatoires, format heures)
        validerSeance(seance);

        // Étape 2 : vérifier que le cours existe dans service-catalogue
        // → appel HTTP GET http://service-catalogue:8080/api/cours/{coursId}
        // → lève EmploiDuTempsException(400) si cours absent
        catalogueClient.verifierCoursExiste(seance.getCoursId());

        // Étape 3 : vérifier que la salle est disponible dans service-locaux
        // → appel HTTP GET http://service-locaux:8080/api/locaux/disponibles
        // → cherche localId dans la liste retournée
        // → lève EmploiDuTempsException(400) si salle non disponible
        localClient.verifierSalleDisponible(seance.getLocalId());

        // Étape 4 : détecter un conflit horaire en base locale (emploi_db)
        // → requête JPQL : même salle + même jour + chevauchement d'horaires
        // → lève EmploiDuTempsException(409) si conflit détecté
        if (seanceRepository.existeConflit(
                seance.getLocalId(),
                seance.getJour(),
                seance.getHeureDebut(),
                seance.getHeureFin(),
                null)) { // null = pas d'exclusion (c'est une création)
            throw new EmploiDuTempsException(
                    "Conflit de planification : la salle est déjà occupée sur ce créneau horaire",
                    HTTP_CONFLICT); // HTTP 409 Conflict
        }

        // Étape 5 : réserver la salle → PATCH disponibilite=OCCUPE
        // → appel HTTP PATCH http://service-locaux:8080/api/locaux/{localId}/disponibilite?valeur=OCCUPE
        localClient.reserverSalle(seance.getLocalId());

        // Étape 6 : persister la séance en base emploi_db
        try {
            return seanceRepository.create(seance);
        } catch (RuntimeException ex) {
            // Compensation manuelle : si l'INSERT échoue, on libère la salle
            // (équivalent d'un rollback distribué simplifié)
            try {
                localClient.libererSalle(seance.getLocalId());
            } catch (Exception rollbackEx) {
                // Échec de la compensation — état potentiellement incohérent
                // Solution robuste : utiliser une queue de messages (Kafka, etc.)
            }
            throw new EmploiDuTempsException(
                    "Erreur lors de la persistance de la séance : " + ex.getMessage(), 500);
        }
    }

    /**
     * Supprime une séance planifiée et libère la salle correspondante.
     *
     * <p><b>Flux :</b>
     * <ol>
     *   <li>Chercher la séance en base (→ 404 si absente)</li>
     *   <li>Libérer la salle : PATCH disponibilite=DISPONIBLE</li>
     *   <li>Supprimer la séance de emploi_db</li>
     * </ol>
     * </p>
     *
     * <p><b>Pourquoi libérer la salle d'abord ?</b> Si la suppression BDD échoue,
     * la salle reste OCCUPE mais on peut retrouver la séance.
     * Si on supprimait d'abord et que le PATCH échouait, la salle resterait
     * OCCUPE indéfiniment sans séance associée.</p>
     *
     * @param id identifiant de la séance à supprimer
     * @throws EmploiDuTempsException HTTP 404 si séance introuvable
     */
    public void supprimerSeance(Long id) {
        // Étape 1 : trouver la séance (pour récupérer localId avant suppression)
        Seance seance = seanceRepository.findById(id)
                .orElseThrow(() -> new EmploiDuTempsException(
                        "Séance introuvable avec l'id : " + id, HTTP_NOT_FOUND));

        // Étape 2 : libérer la salle → PATCH disponibilite=DISPONIBLE
        localClient.libererSalle(seance.getLocalId());

        // Étape 3 : supprimer la séance en base
        seanceRepository.delete(seance);
    }

    /**
     * Valide tous les champs obligatoires d'une séance avant planification.
     *
     * <p>Vérifications effectuées :
     * <ul>
     *   <li>coursId, localId, jour non null</li>
     *   <li>heureDebut et heureFin non vides</li>
     *   <li>heureDebut &lt; heureFin (via {@link HeureUtil#intervalleValide})</li>
     *   <li>semestre et type non null</li>
     * </ul>
     * </p>
     *
     * @param seance séance à valider
     * @throws EmploiDuTempsException HTTP 400 si un champ est invalide
     */
    private void validerSeance(Seance seance) {
        if (seance == null) {
            throw new EmploiDuTempsException("Le corps de la requête est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getCoursId() == null) {
            throw new EmploiDuTempsException("L'identifiant du cours est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getLocalId() == null) {
            throw new EmploiDuTempsException("L'identifiant du local est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getJour() == null) {
            throw new EmploiDuTempsException("Le jour de la séance est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getHeureDebut() == null || seance.getHeureDebut().isBlank()) {
            throw new EmploiDuTempsException("L'heure de début est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getHeureFin() == null || seance.getHeureFin().isBlank()) {
            throw new EmploiDuTempsException("L'heure de fin est obligatoire", HTTP_BAD_REQUEST);
        }
        // HeureUtil.intervalleValide() vérifie le format HH:mm ET que début < fin
        if (!HeureUtil.intervalleValide(seance.getHeureDebut(), seance.getHeureFin())) {
            throw new EmploiDuTempsException(
                    "L'heure de fin doit être postérieure à l'heure de début", HTTP_BAD_REQUEST);
        }
        if (seance.getSemestre() == null || seance.getSemestre().isBlank()) {
            throw new EmploiDuTempsException("Le semestre est obligatoire", HTTP_BAD_REQUEST);
        }
        if (seance.getType() == null) {
            throw new EmploiDuTempsException("Le type de séance est obligatoire", HTTP_BAD_REQUEST);
        }
    }
}
