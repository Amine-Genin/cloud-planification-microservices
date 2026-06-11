package ma.ump.emploidutemps.exception;

// ============================================================
//  COUCHE  : Exception métier personnalisée
//  SERVICE : service-emploi-du-temps
//  RÔLE    : Exception qui transporte à la fois un message d'erreur
//            et le code HTTP correspondant, de la couche service
//            jusqu'au REST resource.
//
//  CONCEPT CLÉS à expliquer au prof :
//
//  1. POURQUOI UNE EXCEPTION PERSONNALISÉE ?
//     Sans elle, on devrait tester le message de l'exception dans
//     le Resource pour décider du code HTTP → couplage fort.
//     Avec elle : le service décide du code HTTP → Resource lit
//     juste ex.getStatutHttp() → découplage propre.
//
//  2. @ApplicationException(rollback = true) :
//     Annotation EJB Jakarta. Indique au conteneur WildFly que cette
//     exception :
//     a) NE DOIT PAS être wrappée dans EJBException (contrairement
//        aux RuntimeException normales en EJB)
//     b) Doit déclencher un rollback de la transaction JTA courante.
//     Sans cette annotation, WildFly emballe l'exception dans
//     EJBException, ce qui complique la récupération dans le Resource.
//
//  3. RuntimeException (non-checked) :
//     Pas besoin de la déclarer avec "throws" dans les signatures.
//     Se propage naturellement jusqu'au Resource qui la capture.
//
//  4. CODES HTTP utilisés :
//     400 Bad Request → données invalides (cours absent, salle indispo)
//     404 Not Found   → ressource inexistante (séance non trouvée)
//     409 Conflict    → conflit horaire (même salle, même créneau)
//     500 Internal    → erreur technique (BDD, réseau...)
// ============================================================

import jakarta.ejb.ApplicationException;

/**
 * Exception métier du service emploi-du-temps, portant un code HTTP associé.
 *
 * <p><b>Propagation :</b> lancée dans {@link ma.ump.emploidutemps.service.EmploiDuTempsService}
 * et les clients HTTP, capturée dans
 * {@link ma.ump.emploidutemps.rest.EmploiDuTempsResource} pour construire
 * la réponse HTTP avec le bon code de statut.</p>
 *
 * <p><b>Exemple d'utilisation :</b>
 * <pre>
 *   // Dans le service :
 *   throw new EmploiDuTempsException("Conflit horaire détecté", 409);
 *
 *   // Dans le resource :
 *   catch (EmploiDuTempsException ex) {
 *       return Response.status(ex.getStatutHttp())
 *                      .entity(Map.of("erreur", ex.getMessage()))
 *                      .build();
 *   }
 * </pre>
 * </p>
 *
 * <p><b>Note {@code @ApplicationException} :</b> sans cette annotation,
 * WildFly emballe automatiquement toute RuntimeException dans une
 * {@code EJBException} au passage de la frontière EJB → JAX-RS.
 * Avec l'annotation, l'exception est propagée telle quelle.</p>
 */
@ApplicationException(rollback = true)
public class EmploiDuTempsException extends RuntimeException {

    /**
     * Code HTTP associé à l'erreur (400, 404, 409, 500...).
     * Utilisé par le REST Resource pour construire la réponse HTTP.
     */
    private final int statutHttp;

    /**
     * Crée une exception métier avec son message et son code HTTP.
     *
     * @param message    message d'erreur lisible (inclus dans le JSON de réponse)
     * @param statutHttp code de statut HTTP (400 = invalide, 404 = absent,
     *                   409 = conflit, 500 = erreur technique)
     */
    public EmploiDuTempsException(String message, int statutHttp) {
        super(message);
        this.statutHttp = statutHttp;
    }

    /**
     * Retourne le code HTTP associé à cette erreur métier.
     *
     * <p>Utilisé dans {@link ma.ump.emploidutemps.rest.EmploiDuTempsResource} :
     * {@code Response.Status.fromStatusCode(ex.getStatutHttp())}</p>
     *
     * @return code HTTP (ex: 400, 404, 409, 500)
     */
    public int getStatutHttp() {
        return statutHttp;
    }
}
