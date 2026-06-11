package ma.ump.emploidutemps.client;

// ============================================================
//  COUCHE  : Client HTTP (Communication inter-services)
//  SERVICE : service-emploi-du-temps → service-locaux
//  RÔLE    : Appeler l'API REST du service-locaux pour :
//            1. Vérifier qu'une salle est disponible (GET)
//            2. Réserver une salle : passer à OCCUPE (PATCH)
//            3. Libérer une salle  : passer à DISPONIBLE (PATCH)
//
//  CONCEPT CLÉS à expliquer au prof :
//
//  1. MÉTHODE HTTP PATCH :
//     Modification PARTIELLE d'une ressource existante.
//     Contrairement à PUT (remplacement complet), PATCH ne modifie
//     qu'un seul attribut : disponibilite.
//     Exemple : PATCH /api/locaux/5/disponibilite?valeur=OCCUPE
//     → change uniquement le champ "disponibilite" du local 5
//     → les autres champs (nom, capacite...) restent inchangés.
//
//  2. URLEncoder.encode() :
//     Encode la valeur du query parameter pour l'URL.
//     "OCCUPE" → "OCCUPE" (pas de caractère spécial ici)
//     Mais "EN_MAINTENANCE" → "EN_MAINTENANCE" (underscore ok)
//     Bonne pratique systématique pour éviter les caractères interdits.
//
//  3. PATTERN RESERVE/LIBERE :
//     reserverSalle(id)  → appelle mettreAJourDisponibilite(id, "OCCUPE")
//     libererSalle(id)   → appelle mettreAJourDisponibilite(id, "DISPONIBLE")
//     Méthodes façade pour une API claire dans le service.
//
//  4. verifierSalleDisponible() :
//     Récupère la LISTE des locaux disponibles et vérifie que
//     l'id demandé y figure. Pas d'endpoint GET /locaux/{id}/status
//     disponible → on utilise l'endpoint /disponibles existant.
// ============================================================

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import ma.ump.emploidutemps.client.dto.LocalDTO;
import ma.ump.emploidutemps.exception.EmploiDuTempsException;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Client HTTP pour communiquer avec le microservice {@code service-locaux}.
 *
 * <p><b>Trois opérations :</b>
 * <ol>
 *   <li>{@link #verifierSalleDisponible(Long)} — vérifie avant planification</li>
 *   <li>{@link #reserverSalle(Long)} — marque OCCUPE après validation</li>
 *   <li>{@link #libererSalle(Long)} — marque DISPONIBLE à la suppression</li>
 * </ol>
 * </p>
 *
 * <p><b>URL cible :</b> {@code LOCAUX_BASE_URL} (défaut : {@code http://service-locaux:8080}).</p>
 *
 * <p><b>Endpoints utilisés :</b>
 * <pre>
 *   GET   /api/locaux/disponibles?capaciteMin=0   → liste des locaux disponibles
 *   PATCH /api/locaux/{id}/disponibilite?valeur=OCCUPE     → réservation
 *   PATCH /api/locaux/{id}/disponibilite?valeur=DISPONIBLE → libération
 * </pre>
 * </p>
 */
@ApplicationScoped
public class LocalClient {

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;

    /** Client HTTP Java 11 réutilisable (threadSafe). */
    private HttpClient httpClient;

    /** URL de base du service locaux (variable d'environnement Docker). */
    private String baseUrl;

    /**
     * Initialisation après injection CDI.
     * Configure le HttpClient et lit {@code LOCAUX_BASE_URL}.
     */
    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // "service-locaux" = nom DNS Docker Compose sur le réseau planification-net
        this.baseUrl = System.getenv().getOrDefault("LOCAUX_BASE_URL", "http://service-locaux:8080");
    }

    /**
     * Vérifie que la salle identifiée par {@code localId} est dans l'état DISPONIBLE.
     *
     * <p><b>Méthode :</b> récupère la liste complète des locaux disponibles via
     * {@code GET /api/locaux/disponibles} et cherche l'id demandé dans la liste.</p>
     *
     * <p>Si la salle est OCCUPE ou EN_MAINTENANCE, elle n'apparaît pas dans
     * la liste → exception HTTP 400.</p>
     *
     * @param localId identifiant du local à vérifier
     * @throws EmploiDuTempsException HTTP 400 si salle non disponible, HTTP 500 si erreur réseau
     */
    public void verifierSalleDisponible(Long localId) {
        if (localId == null) {
            throw new EmploiDuTempsException("L'identifiant du local est obligatoire", HTTP_BAD_REQUEST);
        }
        // Récupère tous les locaux DISPONIBLES (capaciteMin=0 = pas de filtre de capacité)
        List<LocalDTO> disponibles = listerLocauxDisponibles(0);

        // Vérifie que localId est présent dans la liste retournée
        boolean trouve = disponibles.stream()
                .anyMatch(l -> localId.equals(l.getId()));

        if (!trouve) {
            throw new EmploiDuTempsException(
                    "La salle avec l'id " + localId + " n'est pas disponible (DISPONIBLE)",
                    HTTP_BAD_REQUEST);
        }
    }

    /**
     * Réserve une salle en changeant sa disponibilité à {@code OCCUPE}.
     *
     * <p>Appelle : {@code PATCH /api/locaux/{localId}/disponibilite?valeur=OCCUPE}</p>
     *
     * <p>Appelé APRÈS la détection de conflit (étape 4 du flux planification).
     * La salle passe de DISPONIBLE à OCCUPE pour bloquer d'autres réservations.</p>
     *
     * @param localId identifiant de la salle à réserver
     * @throws EmploiDuTempsException si erreur HTTP ou réseau
     */
    public void reserverSalle(Long localId) {
        mettreAJourDisponibilite(localId, "OCCUPE");
    }

    /**
     * Libère une salle en changeant sa disponibilité à {@code DISPONIBLE}.
     *
     * <p>Appelle : {@code PATCH /api/locaux/{localId}/disponibilite?valeur=DISPONIBLE}</p>
     *
     * <p>Appelé lors de la SUPPRESSION d'une séance, ou en compensation
     * si la persistance de la séance échoue après réservation.</p>
     *
     * @param localId identifiant de la salle à libérer
     * @throws EmploiDuTempsException si erreur HTTP ou réseau
     */
    public void libererSalle(Long localId) {
        mettreAJourDisponibilite(localId, "DISPONIBLE");
    }

    /**
     * Liste les locaux disponibles avec une capacité minimale.
     *
     * <p>Appelle : {@code GET /api/locaux/disponibles?capaciteMin={capaciteMin}}</p>
     *
     * @param capaciteMin capacité minimale requise (0 = pas de filtre)
     * @return liste des {@link LocalDTO} disponibles
     * @throws EmploiDuTempsException si erreur HTTP ou réseau
     */
    public List<LocalDTO> listerLocauxDisponibles(int capaciteMin) {
        try {
            String url = baseUrl + "/api/locaux/disponibles?capaciteMin=" + capaciteMin;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmploiDuTempsException(
                        "Erreur lors de la récupération des locaux disponibles (HTTP "
                                + response.statusCode() + ")",
                        HTTP_INTERNAL_ERROR);
            }

            // Parsing JSON d'un tableau d'objets locaux
            return parseLocauxListe(response.body());

        } catch (EmploiDuTempsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmploiDuTempsException(
                    "Impossible de contacter le service locaux : " + ex.getMessage(),
                    HTTP_INTERNAL_ERROR);
        }
    }

    /**
     * Met à jour la disponibilité d'un local via PATCH HTTP.
     *
     * <p><b>URL appelée :</b>
     * {@code PATCH /api/locaux/{localId}/disponibilite?valeur={valeur}}</p>
     *
     * <p><b>Corps de la requête :</b> vide (noBody) — la valeur est dans le query param.
     * PATCH sans corps = sémantique REST correcte pour un changement d'état simple.</p>
     *
     * <p>{@code URLEncoder.encode()} encode la valeur pour l'URL
     * (protection contre les caractères spéciaux).</p>
     *
     * @param localId identifiant du local à modifier
     * @param valeur  nouvelle disponibilité ("OCCUPE", "DISPONIBLE", "EN_MAINTENANCE")
     * @throws EmploiDuTempsException si HTTP 404 (local absent) ou erreur réseau
     */
    private void mettreAJourDisponibilite(Long localId, String valeur) {
        if (localId == null) {
            throw new EmploiDuTempsException("L'identifiant du local est obligatoire", HTTP_BAD_REQUEST);
        }
        try {
            // Encodage URL pour sécuriser le query parameter
            String encoded = URLEncoder.encode(valeur, StandardCharsets.UTF_8);
            String url = baseUrl + "/api/locaux/" + localId + "/disponibilite?valeur=" + encoded;

            // Requête PATCH sans corps (la valeur est dans l'URL)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("PATCH", HttpRequest.BodyPublishers.noBody()) // PATCH = modification partielle
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new EmploiDuTempsException(
                        "Local introuvable avec l'id : " + localId, HTTP_BAD_REQUEST);
            }
            if (response.statusCode() != 200) {
                throw new EmploiDuTempsException(
                        "Erreur lors de la mise à jour de disponibilité (HTTP "
                                + response.statusCode() + ")",
                        HTTP_INTERNAL_ERROR);
            }

        } catch (EmploiDuTempsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmploiDuTempsException(
                    "Impossible de mettre à jour la disponibilité du local : " + ex.getMessage(),
                    HTTP_INTERNAL_ERROR);
        }
    }

    /**
     * Parse un tableau JSON de locaux en liste de {@link LocalDTO}.
     *
     * @param json corps JSON (tableau d'objets)
     * @return liste de DTO locaux
     */
    private List<LocalDTO> parseLocauxListe(String json) {
        List<LocalDTO> result = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonArray array = reader.readArray();
            for (JsonValue value : array) {
                result.add(parseLocal(value.asJsonObject()));
            }
        }
        return result;
    }

    /**
     * Parse un objet JSON unique en {@link LocalDTO}.
     * Lecture défensive : chaque champ est vérifié avant accès.
     *
     * @param obj objet JSON d'un local
     * @return DTO local rempli
     */
    private LocalDTO parseLocal(JsonObject obj) {
        LocalDTO dto = new LocalDTO();
        if (obj.containsKey("id") && !obj.isNull("id")) {
            dto.setId(obj.getJsonNumber("id").longValue());
        }
        if (obj.containsKey("code"))            dto.setCode(obj.getString("code", null));
        if (obj.containsKey("nom"))             dto.setNom(obj.getString("nom", null));
        if (obj.containsKey("type"))            dto.setType(obj.getString("type", null));
        if (obj.containsKey("capacite"))        dto.setCapacite(obj.getInt("capacite"));
        if (obj.containsKey("batiment"))        dto.setBatiment(obj.getString("batiment", null));
        if (obj.containsKey("etage"))           dto.setEtage(obj.getInt("etage"));
        if (obj.containsKey("projecteur"))      dto.setProjecteur(obj.getBoolean("projecteur"));
        if (obj.containsKey("tableauNumerique"))dto.setTableauNumerique(obj.getBoolean("tableauNumerique"));
        if (obj.containsKey("climatisation"))   dto.setClimatisation(obj.getBoolean("climatisation"));
        if (obj.containsKey("accessiblePMR"))   dto.setAccessiblePMR(obj.getBoolean("accessiblePMR"));
        if (obj.containsKey("disponibilite"))   dto.setDisponibilite(obj.getString("disponibilite", null));
        return dto;
    }
}
