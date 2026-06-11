package ma.ump.emploidutemps.client;

// ============================================================
//  COUCHE  : Client HTTP (Communication inter-services)
//  SERVICE : service-emploi-du-temps → service-catalogue
//  RÔLE    : Appeler l'API REST du service-catalogue pour vérifier
//            qu'un cours existe avant de planifier une séance.
//
//  CONCEPT CLÉS à expliquer au prof :
//
//  1. COMMUNICATION INTER-SERVICES (pattern Client HTTP) :
//     service-emploi ne peut pas accéder directement à la base
//     catalogue_db — il doit OBLIGATOIREMENT passer par l'API REST.
//     C'est le principe "database per service" des microservices.
//
//  2. java.net.http.HttpClient (Java 11+) :
//     Client HTTP natif Java, sans dépendance externe.
//     Remplace Apache HttpClient ou RestTemplate (Spring).
//     Avantages : léger, modern, supporte HTTP/2.
//
//  3. @ApplicationScoped :
//     Une SEULE instance partagée par tous les threads pendant toute
//     la durée de vie de l'application (vs @RequestScoped = une
//     instance par requête HTTP).
//     Le HttpClient est threadSafe → OK pour @ApplicationScoped.
//
//  4. @PostConstruct :
//     Méthode appelée automatiquement après l'injection de toutes
//     les dépendances. Idéal pour l'initialisation (HttpClient, URL).
//
//  5. URL via variable d'environnement :
//     CATALOGUE_BASE_URL = "http://service-catalogue:8080"
//     Dans Docker Compose, "service-catalogue" est le nom DNS du
//     conteneur sur le réseau planification-net.
//     En dehors de Docker : "http://localhost:8081" (optionnel).
//
//  6. PARSING JSON MANUEL (Jakarta JSON-P) :
//     Jakarta EE 10 inclut JSON-P (Json.createReader).
//     On lit les champs manuellement car on n'a pas accès à la
//     classe Cours du service-catalogue (services découplés).
//     → On utilise un DTO local (CoursDTO) pour transporter les données.
// ============================================================

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import ma.ump.emploidutemps.client.dto.CoursDTO;
import ma.ump.emploidutemps.exception.EmploiDuTempsException;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Client HTTP pour communiquer avec le microservice {@code service-catalogue}.
 *
 * <p><b>Rôle :</b> vérifier l'existence d'un cours avant de planifier une séance.
 * Ce client est la seule interface entre {@code service-emploi-du-temps} et
 * {@code service-catalogue}.</p>
 *
 * <p><b>URL cible :</b> configurée via la variable d'environnement
 * {@code CATALOGUE_BASE_URL} (valeur par défaut : {@code http://service-catalogue:8080}).
 * Dans Docker Compose, {@code service-catalogue} est résolu par le DNS interne
 * du réseau {@code planification-net}.</p>
 *
 * <p><b>Endpoint utilisé :</b>
 * <pre>GET http://service-catalogue:8080/api/cours/{id}</pre>
 * Retourne HTTP 200 + JSON du cours si trouvé, HTTP 404 sinon.
 * </p>
 */
@ApplicationScoped
public class CatalogueClient {

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;

    /** Client HTTP Java 11 — réutilisé pour toutes les requêtes (threadSafe). */
    private HttpClient httpClient;

    /** URL de base du service catalogue (ex: "http://service-catalogue:8080"). */
    private String baseUrl;

    /**
     * Initialisation après injection CDI.
     * Crée le HttpClient et lit la variable d'environnement {@code CATALOGUE_BASE_URL}.
     *
     * <p><b>Timeout de connexion :</b> 10 secondes — si le service catalogue
     * ne répond pas dans ce délai, une exception est levée.</p>
     */
    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)) // timeout connexion TCP
                .build();
        // Lecture de la variable d'environnement Docker (définie dans compose.yaml)
        // Valeur par défaut : nom DNS Docker Compose du conteneur catalogue
        this.baseUrl = System.getenv().getOrDefault("CATALOGUE_BASE_URL", "http://service-catalogue:8080");
    }

    /**
     * Vérifie qu'un cours existe dans le service-catalogue.
     *
     * <p><b>Appel effectué :</b>
     * {@code GET {CATALOGUE_BASE_URL}/api/cours/{coursId}}</p>
     *
     * <p><b>Comportement :</b>
     * <ul>
     *   <li>HTTP 200 → cours trouvé, retourne son DTO</li>
     *   <li>HTTP 404 → cours absent → {@link EmploiDuTempsException} HTTP 400</li>
     *   <li>Autre code ou exception réseau → {@link EmploiDuTempsException} HTTP 500</li>
     * </ul>
     * </p>
     *
     * @param coursId identifiant du cours à vérifier
     * @return DTO du cours trouvé
     * @throws EmploiDuTempsException si cours absent (400) ou erreur technique (500)
     */
    public CoursDTO verifierCoursExiste(Long coursId) {
        if (coursId == null) {
            throw new EmploiDuTempsException("L'identifiant du cours est obligatoire", HTTP_BAD_REQUEST);
        }
        try {
            String url = baseUrl + "/api/cours/" + coursId;

            // Construction de la requête HTTP GET avec timeout de lecture de 15 secondes
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))   // timeout lecture de la réponse
                    .header("Accept", "application/json")
                    .build();

            // Envoi synchrone — bloque jusqu'à la réponse ou le timeout
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // HTTP 404 = cours absent dans le catalogue
            if (response.statusCode() == 404) {
                throw new EmploiDuTempsException(
                        "Le cours avec l'id " + coursId + " n'existe pas", HTTP_BAD_REQUEST);
            }
            // Autre code d'erreur (500, 503...) = problème technique côté catalogue
            if (response.statusCode() != 200) {
                throw new EmploiDuTempsException(
                        "Erreur lors de la vérification du cours (HTTP " + response.statusCode() + ")",
                        HTTP_INTERNAL_ERROR);
            }

            // Parsing JSON de la réponse → CoursDTO
            return parseCours(response.body());

        } catch (EmploiDuTempsException ex) {
            throw ex; // re-propagation sans modification
        } catch (Exception ex) {
            // Erreur réseau (timeout, connexion refusée, DNS non résolu...)
            throw new EmploiDuTempsException(
                    "Impossible de contacter le service catalogue : " + ex.getMessage(),
                    HTTP_INTERNAL_ERROR);
        }
    }

    /**
     * Recherche optionnelle d'un cours (ne lève pas d'exception si absent).
     *
     * @param coursId identifiant du cours
     * @return {@code Optional.of(cours)} si trouvé, vide si absent
     * @throws EmploiDuTempsException si erreur technique (500)
     */
    public Optional<CoursDTO> trouverCours(Long coursId) {
        try {
            return Optional.of(verifierCoursExiste(coursId));
        } catch (EmploiDuTempsException ex) {
            if (ex.getStatutHttp() == HTTP_BAD_REQUEST) {
                return Optional.empty(); // absent = vide, pas d'exception
            }
            throw ex; // erreur technique → re-propagation
        }
    }

    /**
     * Parse le corps JSON de la réponse du service-catalogue en {@link CoursDTO}.
     *
     * <p>On utilise Jakarta JSON-P (Json.createReader) car on n'a pas accès
     * à la classe {@code Cours} du service-catalogue. Les services sont DÉCOUPLÉS :
     * chacun a son propre modèle. Le DTO local évite de partager du code entre services.</p>
     *
     * @param json corps JSON brut de la réponse HTTP
     * @return DTO du cours avec les champs extraits
     */
    private CoursDTO parseCours(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject obj = reader.readObject();
            CoursDTO dto = new CoursDTO();
            // containsKey + isNull : robustesse si un champ est absent ou null dans le JSON
            if (obj.containsKey("id") && !obj.isNull("id")) {
                dto.setId(obj.getJsonNumber("id").longValue());
            }
            if (obj.containsKey("code")) {
                dto.setCode(obj.getString("code", null));
            }
            if (obj.containsKey("intitule")) {
                dto.setIntitule(obj.getString("intitule", null));
            }
            if (obj.containsKey("syllabus")) {
                dto.setSyllabus(obj.getString("syllabus", null));
            }
            if (obj.containsKey("prerequis")) {
                dto.setPrerequis(obj.getString("prerequis", null));
            }
            if (obj.containsKey("credits")) {
                dto.setCredits(obj.getInt("credits"));
            }
            return dto;
        }
    }
}
