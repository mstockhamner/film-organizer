package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.util.List;

/**
 * Was mit einem gefundenen Film geschehen soll.
 *
 * @param art             Art der Aktion
 * @param film            der Film aus der Quelle
 * @param verschiebungen  geplante Verschiebungen: bei {@link Art#EINSORTIEREN} in die Sammlung, bei einer
 *                        {@link Art#DOUBLETTE} in die Quarantäne, sonst leer
 * @param hinweis         Erklärung für die Vorschau, z.B. womit eine Doublette übereinstimmt
 */
public record Aktion(Art art, Film film, List<Verschiebung> verschiebungen, String hinweis) {

    public enum Art {
        /** Wird in den Zielordner verschoben. */
        EINSORTIEREN,
        /** Ist schon vorhanden; kommt in die Quarantäne, wenn sie identisch oder schlechter ist, sonst bleibt sie liegen. */
        DOUBLETTE,
        /** Titel oder Jahr nicht erkannt, bleibt liegen. */
        UNERKANNT,
        /** Zielpfad ist schon belegt, bleibt liegen. */
        KONFLIKT,
        /** In der Vorschau abgewählt, bleibt liegen. */
        AUSGELASSEN
    }

    /** Doublette, die beim Ausführen nach {@code Ziel/_Doubletten} verschoben wird. */
    public boolean inQuarantaene() {
        return art == Art.DOUBLETTE && !verschiebungen.isEmpty();
    }

    /** Eine einzelne Datei, die verschoben wird. */
    public record Verschiebung(Path von, Path nach) {
    }
}
