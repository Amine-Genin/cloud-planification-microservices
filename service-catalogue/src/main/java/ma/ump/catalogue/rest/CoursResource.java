package ma.ump.catalogue.rest;

// ============================================================
//  COUCHE  : REST Resource (Point d'entrée HTTP)
//  SERVICE : service-catalogue
//  PORT    : 8081 (hôte) → http://localhost:8081/api/cours
//  RÔLE    : Exposition de l'API REST CRUD du catalogue des cours.
//            Reçoit les requêtes HTTP, délègue au CoursService,
//            retourne des réponses JSON avec les codes HTTP appropriés.
//
//  CONCEPT CLÉS à expliquer au prof :
//  - @Path("/cours")     : URL de base de ce endpoint.
//  - @GET / @POST / @PUT / @DELETE : verbes HTTP (méthodes CRUD REST).
//  - @Produces(JSON)     : toutes les réponses sont en JSON.
//  - @Consumes(JSON)     : les requêtes entrantes doivent être en JSON.
//  - @PathParam          : extrait un segment de l'URL (/cours/{id} → id).
//  - Response.status()   : construit la réponse HTTP avec le bon code.
//  - @RequestScoped      : une instance par requête HTTP (CDI scope).
//
//  CODES HTTP retournés :
//  - 200 OK          : lecture réussie (GET)
//  - 201 Created     : ressource créée (POST)
//  - 204 No Content  : suppression réussie (DELETE)
//  - 400 Bad Request : données invalides ou code déjà existant
//  - 404 Not Found   : cours introuvable
//  - 500 Internal    : erreur technique inattendue
// ============================================================

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ma.ump.catalogue.entity.Cours;
import ma.ump.catalogue.service.CoursService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ressource REST JAX-RS exposant les opérations CRUD sur le catalogue des cours.
 *
 * <p><b>URL de base :</b> {@code http://localhost:8081/api/cours}</p>
 *
 * <p><b>Endpoints disponibles :</b>
 * <pre>
 *   GET    /api/cours               → liste tous les cours (HTTP 200)
 *   GET    /api/cours/{id}          → un cours par id (200 ou 404)
 *   GET    /api/cours/code/{code}   → un cours par code (200 ou 404)
 *   POST   /api/cours               → crée un cours (201 ou 400)
 *   PUT    /api/cours/{id}          → met à jour (200, 400 ou 404)
 *   DELETE /api/cours/{id}          → supprime (204 ou 404)
 * </pre>
 * </p>
 *
 * <p><b>Principe de conception :</b> cette classe ne contient AUCUNE logique métier.
 * Son rôle unique est de : (1) recevoir la requête HTTP, (2) appeler le service,
 * (3) construire la réponse HTTP avec le code de statut approprié.</p>
 *
 * <p><b>Sérialisation JSON :</b> WildFly utilise Yasson (JSON-B) pour convertir
 * automatiquement les objets Java ({@link Cours}) en JSON et vice-versa.</p>
 */
@RequestScoped
@Path("/cours")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CoursResource {

    /**
     * Service métier injecté par CDI.
     * CoursResource ne crée pas CoursService — WildFly l'injecte.
     */
    @Inject
    private CoursService coursService;

    // ============================================================
    //  LECTURE (opérations sans effet de bord)
    // ============================================================

    /**
     * Liste tous les cours du catalogue.
     *
     * <p>Exemple de requête : {@code GET http://localhost:8081/api/cours}</p>
     * <p>Exemple de réponse :
     * <pre>HTTP 200 OK
     * [
     *   { "id": 1, "code": "INF301", "intitule": "Cloud Computing", "credits": 6 },
     *   { "id": 2, "code": "INF302", "intitule": "Microservices", "credits": 6 }
     * ]</pre>
     * </p>
     *
     * @return HTTP 200 avec tableau JSON de cours (vide si aucun)
     */
    @GET
    public Response listerTous() {
        try {
            List<Cours> cours = coursService.listerTous();
            return Response.ok(cours).build();
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    /**
     * Recherche un cours par son identifiant numérique.
     *
     * <p>Exemple : {@code GET /api/cours/1}</p>
     *
     * <p>{@code @PathParam("id")} extrait le segment {@code {id}} de l'URL
     * et le convertit en {@code Long} automatiquement par JAX-RS.</p>
     *
     * @param id identifiant du cours (segment d'URL)
     * @return HTTP 200 avec le cours, ou HTTP 404 si inexistant
     */
    @GET
    @Path("/{id}")
    public Response trouverParId(@PathParam("id") Long id) {
        try {
            // map() : si présent → 200, orElseGet() : si absent → 404
            return coursService.trouverParId(id)
                    .map(cours -> Response.ok(cours).build())
                    .orElseGet(() -> erreur("Cours introuvable", Response.Status.NOT_FOUND));
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    /**
     * Recherche un cours par son code unique.
     *
     * <p>Exemple : {@code GET /api/cours/code/INF301}</p>
     *
     * <p>Utilisé par service-emploi-du-temps pour valider l'existence d'un cours
     * avant de planifier une séance.</p>
     *
     * @param code code du cours dans l'URL
     * @return HTTP 200 avec le cours, ou HTTP 404
     */
    @GET
    @Path("/code/{code}")
    public Response trouverParCode(@PathParam("code") String code) {
        try {
            return coursService.trouverParCode(code)
                    .map(cours -> Response.ok(cours).build())
                    .orElseGet(() -> erreur("Cours introuvable pour le code : " + code, Response.Status.NOT_FOUND));
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    // ============================================================
    //  ÉCRITURE (opérations avec effet de bord sur la base)
    // ============================================================

    /**
     * Crée un nouveau cours dans le catalogue.
     *
     * <p>Exemple de requête :
     * <pre>POST /api/cours
     * Content-Type: application/json
     * {
     *   "code": "INF311",
     *   "intitule": "DevOps",
     *   "syllabus": "CI/CD, Docker, Kubernetes",
     *   "prerequis": "INF301",
     *   "credits": 5
     * }</pre>
     * </p>
     *
     * <p>JAX-RS désérialise automatiquement le JSON entrant en objet {@link Cours}.</p>
     *
     * @param cours corps JSON de la requête désérialisé en objet Cours
     * @return HTTP 201 Created avec le cours créé (incluant l'id généré),
     *         ou HTTP 400 Bad Request si données invalides/code dupliqué
     */
    @POST
    public Response creer(Cours cours) {
        try {
            Cours cree = coursService.creer(cours);
            // HTTP 201 Created = convention REST pour une ressource nouvellement créée
            return Response.status(Response.Status.CREATED).entity(cree).build();
        } catch (IllegalArgumentException ex) {
            // IllegalArgumentException vient du service → données invalides → 400
            return erreur(ex.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    /**
     * Met à jour un cours existant (remplacement complet — sémantique PUT).
     *
     * <p>Exemple : {@code PUT /api/cours/1} avec corps JSON complet du cours.</p>
     *
     * <p><b>PUT vs PATCH :</b> PUT remplace la ressource entière.
     * PATCH modifie partiellement (utilisé pour la disponibilité des locaux).</p>
     *
     * @param id    identifiant du cours à modifier
     * @param cours nouvelles valeurs complètes
     * @return HTTP 200 avec le cours mis à jour, 400 ou 404
     */
    @PUT
    @Path("/{id}")
    public Response mettreAJour(@PathParam("id") Long id, Cours cours) {
        try {
            Cours misAJour = coursService.mettreAJour(id, cours);
            return Response.ok(misAJour).build();
        } catch (IllegalArgumentException ex) {
            // "introuvable" dans le message → 404, sinon → 400
            if (ex.getMessage() != null && ex.getMessage().contains("introuvable")) {
                return erreur(ex.getMessage(), Response.Status.NOT_FOUND);
            }
            return erreur(ex.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    /**
     * Supprime un cours par son identifiant.
     *
     * <p>Exemple : {@code DELETE /api/cours/1}</p>
     *
     * <p><b>HTTP 204 No Content</b> = suppression réussie, corps de réponse vide
     * (convention REST : pas de contenu à retourner après suppression).</p>
     *
     * @param id identifiant du cours à supprimer
     * @return HTTP 204 si supprimé, HTTP 404 si introuvable
     */
    @DELETE
    @Path("/{id}")
    public Response supprimer(@PathParam("id") Long id) {
        try {
            if (coursService.supprimer(id)) {
                return Response.noContent().build(); // HTTP 204
            }
            return erreur("Cours introuvable avec l'id : " + id, Response.Status.NOT_FOUND);
        } catch (Exception ex) {
            return erreurInterne(ex);
        }
    }

    // ============================================================
    //  UTILITAIRES : construction des réponses d'erreur JSON
    // ============================================================

    /**
     * Construit une réponse d'erreur JSON standardisée avec statut HTTP.
     *
     * <p>Format de la réponse :
     * <pre>{ "erreur": "message ici", "statut": 400 }</pre>
     * </p>
     *
     * <p>Ce format homogène permet aux clients (Postman, test.sh, emploi-du-temps)
     * d'identifier facilement la nature de l'erreur.</p>
     *
     * @param message message d'erreur lisible
     * @param status  code de statut HTTP (400, 404, 409...)
     * @return réponse HTTP avec corps JSON d'erreur
     */
    private Response erreur(String message, Response.Status status) {
        Map<String, Object> body = new HashMap<>();
        body.put("erreur", message);
        body.put("statut", status.getStatusCode());
        return Response.status(status).entity(body).build();
    }

    /**
     * Construit une réponse d'erreur interne HTTP 500.
     *
     * <p>Utilisée pour les exceptions non anticipées (erreur BDD, NPE...).
     * Le détail technique est inclus pour faciliter le débogage.</p>
     *
     * @param ex exception inattendue capturée
     * @return HTTP 500 avec message d'erreur JSON
     */
    private Response erreurInterne(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("erreur", "Erreur interne du serveur");
        body.put("detail", ex.getMessage());
        body.put("statut", Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
    }
}
