package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.util.List;

/**
 * Was mit einem gefundenen Film geschehen soll.
 *
 * @param art             Art der Aktion
 * @param film            der Film aus der Quelle
 * @param verschiebungen  geplante Verschiebungen, nur bei {@link Art#EINSORTIEREN} nicht leer
 * @param hinweis         Erklärung für die Vorschau, z.B. womit eine Doublette übereinstimmt
 */
public record Aktion(Art art, Film film, List<Verschiebung> verschiebungen, String hinweis) {

    public enum Art {
        /** Wird in den Zielordner verschoben. */
        EINSORTIEREN,
        /** Ist schon vorhanden und bleibt liegen. */
        DOUBLETTE,
        /** Titel oder Jahr nicht erkannt, bleibt liegen. */
        UNERKANNT,
        /** Zielpfad ist schon belegt, bleibt liegen. */
        KONFLIKT
    }

    /** Eine einzelne Datei, die verschoben wird. */
    public record Verschiebung(Path von, Path nach) {
    }
}
