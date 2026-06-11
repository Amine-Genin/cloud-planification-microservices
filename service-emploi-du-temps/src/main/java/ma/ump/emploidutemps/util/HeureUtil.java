package ma.ump.emploidutemps.util;

// ============================================================
//  COUCHE  : Utilitaire (classe helper)
//  SERVICE : service-emploi-du-temps
//  RÔLE    : Fournir les opérations de comparaison et validation
//            des créneaux horaires au format HH:mm.
//
//  CONCEPT CLÉS à expliquer au prof :
//
//  1. ALGORITHME DE DÉTECTION DE CHEVAUCHEMENT :
//     Deux intervalles [d1, f1] et [d2, f2] se chevauchent si et
//     seulement si : d1 < f2  ET  d2 < f1
//     (cas inverse : pas de chevauchement si f1 <= d2 OU f2 <= d1)
//
//     Exemple concret :
//       Séance A : 08:00 → 10:00   (d1=480, f1=600)
//       Séance B : 09:00 → 11:00   (d2=540, f2=660)
//       480 < 660  ET  540 < 600  → CONFLIT ✓
//
//       Séance A : 08:00 → 10:00   (d1=480, f1=600)
//       Séance C : 10:00 → 12:00   (d2=600, f2=720)
//       480 < 720 ET 600 < 600 → FAUX → PAS de conflit ✓
//       (créneaux consécutifs = pas de chevauchement)
//
//  2. CLASSE UTILITAIRE (final + constructeur privé) :
//     Empêche l'instanciation et l'héritage.
//     Toutes les méthodes sont static → pas besoin d'un objet.
//
//  3. REGEX VALIDATION :
//     "^([01]\\d|2[0-3]):[0-5]\\d$"
//     → [01]\\d : 00-19  (00:00 à 19:59)
//     → 2[0-3]  : 20-23  (20:00 à 23:59)
//     → :[0-5]\\d : minutes 00-59
// ============================================================

/**
 * Utilitaire de manipulation et comparaison des créneaux horaires au format {@code HH:mm}.
 *
 * <p><b>Utilisé par :</b>
 * <ul>
 *   <li>{@link ma.ump.emploidutemps.service.EmploiDuTempsService#planifierSeance} — validation des heures</li>
 *   <li>{@link ma.ump.emploidutemps.repository.SeanceRepository#existeConflit} — détection de chevauchement</li>
 * </ul>
 * </p>
 *
 * <p><b>Principe de détection du chevauchement :</b><br>
 * Deux créneaux {@code [d1, f1]} et {@code [d2, f2]} se chevauchent si et seulement si :
 * {@code d1 < f2 ET d2 < f1}
 * (formule de détection d'intersection d'intervalles)</p>
 */
public final class HeureUtil {

    /** Constructeur privé : classe utilitaire statique — ne pas instancier. */
    private HeureUtil() {
    }

    /**
     * Convertit une heure au format {@code HH:mm} en nombre de minutes depuis minuit.
     *
     * <p>Exemples :
     * <ul>
     *   <li>{@code "08:00"} → 480 minutes</li>
     *   <li>{@code "10:30"} → 630 minutes</li>
     *   <li>{@code "23:59"} → 1439 minutes</li>
     * </ul>
     * </p>
     *
     * <p>La conversion en minutes permet de comparer les heures avec de simples
     * opérations arithmétiques entières au lieu de manipuler des strings.</p>
     *
     * @param heure heure au format {@code HH:mm} (regex : {@code ^([01]\d|2[0-3]):[0-5]\d$})
     * @return nombre de minutes depuis minuit (0 à 1439)
     * @throws IllegalArgumentException si le format est invalide
     */
    public static int enMinutes(String heure) {
        // Validation du format via expression régulière
        // ^([01]\\d|2[0-3]):[0-5]\\d$
        //  [01]\\d = 00 à 19, 2[0-3] = 20 à 23, [0-5]\\d = 00 à 59
        if (heure == null || !heure.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new IllegalArgumentException("Format d'heure invalide (attendu HH:mm) : " + heure);
        }
        String[] parties = heure.split(":");
        int h = Integer.parseInt(parties[0]); // heures (0 à 23)
        int m = Integer.parseInt(parties[1]); // minutes (0 à 59)
        return h * 60 + m;
    }

    /**
     * Vérifie que l'intervalle horaire est valide (début strictement avant fin).
     *
     * <p>Exemple : {@code intervalleValide("08:00", "10:00")} → true<br>
     * {@code intervalleValide("10:00", "08:00")} → false (fin avant début)<br>
     * {@code intervalleValide("08:00", "08:00")} → false (durée nulle)</p>
     *
     * @param heureDebut heure de début au format HH:mm
     * @param heureFin   heure de fin au format HH:mm
     * @return {@code true} si heureDebut &lt; heureFin
     */
    public static boolean intervalleValide(String heureDebut, String heureFin) {
        return enMinutes(heureDebut) < enMinutes(heureFin);
    }

    /**
     * Détermine si deux intervalles horaires se chevauchent.
     *
     * <p><b>Algorithme :</b> {@code d1 < f2 AND d2 < f1}<br>
     * (équivalent de la négation : pas de chevauchement si f1 &lt;= d2 OU f2 &lt;= d1)</p>
     *
     * <p><b>Exemples :</b>
     * <pre>
     *   Cas 1 — CHEVAUCHEMENT :
     *     [08:00, 10:00] et [09:00, 11:00]
     *     480 < 660 ET 540 < 600 → true ✓
     *
     *   Cas 2 — CONSÉCUTIFS (pas de chevauchement) :
     *     [08:00, 10:00] et [10:00, 12:00]
     *     480 < 720 ET 600 < 600 (FAUX) → false ✓
     *
     *   Cas 3 — SÉPARÉS :
     *     [08:00, 09:00] et [10:00, 11:00]
     *     480 < 660 ET 600 < 540 (FAUX) → false ✓
     * </pre>
     * </p>
     *
     * @param debut1 début du 1er intervalle (HH:mm)
     * @param fin1   fin du 1er intervalle (HH:mm)
     * @param debut2 début du 2ème intervalle (HH:mm)
     * @param fin2   fin du 2ème intervalle (HH:mm)
     * @return {@code true} si les créneaux se chevauchent (conflit)
     */
    public static boolean seChevauchent(String debut1, String fin1, String debut2, String fin2) {
        int d1 = enMinutes(debut1);
        int f1 = enMinutes(fin1);
        int d2 = enMinutes(debut2);
        int f2 = enMinutes(fin2);
        // Formule standard d'intersection d'intervalles : d1 < f2 ET d2 < f1
        return d1 < f2 && d2 < f1;
    }
}
