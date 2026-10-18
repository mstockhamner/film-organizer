package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.util.List;

/**
 * Was mit einem gefundenen Film geschehen soll.
 *
 * @param art             Art der Aktion
 * @param film            der Film aus der Quelle
 * @param verschiebungen  geplante Verschiebungen: bei {@link Art#EINSORTIEREN} in die Sammlung, bei einer
 *                        {@link Art#DOUBLETTE} in die Quarantäne, bei {@link Art#ERSETZEN} zuerst die alte
 *                        Fassung in die Quarantäne und dann die neue an ihren Platz, sonst leer
 * @param hinweis         Erklärung für die Vorschau, z.B. womit eine Doublette übereinstimmt
 * @param entscheidung    was bei einer Doublette oder anderen Fassung geschieht
 * @param vorschlag       was das Programm vorschlägt, solange nichts anderes gewählt ist
 * @param auswahl         die Entscheidungen, die für diesen Film möglich sind; leer, wenn es nichts zu entscheiden gibt
 */
public record Aktion(Art art, Film film, List<Verschiebung> verschiebungen, String hinweis,
        Entscheidung entscheidung, Entscheidung vorschlag, List<Entscheidung> auswahl) {

    public Aktion(Art art, Film film, List<Verschiebung> verschiebungen, String hinweis) {
        this(art, film, verschiebungen, hinweis, Entscheidung.OFFEN, Entscheidung.OFFEN, List.of());
    }

    /** Dieselbe Aktion mit Entscheidung, Vorschlag und Auswahl. */
    Aktion mit(Entscheidung gewaehlt, Entscheidung vorgeschlagen, List<Entscheidung> moeglich) {
        return new Aktion(art, film, verschiebungen, hinweis, gewaehlt, vorgeschlagen, moeglich);
    }

    public enum Art {
        /** Wird in den Zielordner verschoben. */
        EINSORTIEREN,
        /** Ist schon vorhanden; kommt je nach Entscheidung in die Quarantäne oder bleibt liegen. */
        DOUBLETTE,
        /** Bessere Fassung: die vorhandene kommt in die Quarantäne, die neue an ihren Platz. */
        ERSETZEN,
        /** Film aus der Sammlung, der in den Ordner seiner Filmreihe verschoben wird. */
        UMSORTIEREN,
        /**
         * Andere Fassung eines vorhandenen Films (z.B. Extended neben der Kinofassung). Bleibt liegen,
         * bis in der Vorschau entschieden ist, ob beide behalten werden.
         */
        FASSUNG,
        /** Titel oder Jahr nicht erkannt, bleibt liegen. */
        UNERKANNT,
        /** Zielpfad ist schon belegt, bleibt liegen. */
        KONFLIKT,
        /** In der Vorschau abgewählt, bleibt liegen. */
        AUSGELASSEN
    }

    /** Was mit einer Doublette oder einer anderen Fassung geschehen soll. */
    public enum Entscheidung {
        /** Noch nicht entschieden, bleibt liegen. */
        OFFEN("bitte wählen"),
        /** Wird zusätzlich in den Ordner des Films einsortiert, z.B. als {@code Titel (Jahr) - Extended.mkv}. */
        BEIDE_BEHALTEN("Beide behalten"),
        /** Kommt in die Quarantäne. */
        QUARANTAENE("In Quarantäne"),
        /** Ersetzt die vorhandene Fassung, die in die Quarantäne kommt. */
        ERSETZEN("Ersetzen"),
        /** Bleibt in der Quelle liegen. */
        LIEGEN_LASSEN("Liegen lassen");

        private final String text;

        Entscheidung(String text) {
            this.text = text;
        }

        /** Bezeichnung für Vorschau, Kommandozeile und Bericht. */
        public String text() {
            return text;
        }
    }

    /** Doublette, die beim Ausführen nach {@code Ziel/_Doubletten} verschoben wird. */
    public boolean inQuarantaene() {
        return art == Art.DOUBLETTE && !verschiebungen.isEmpty();
    }

    /** Andere Fassung, über die noch nicht entschieden ist. */
    public boolean offen() {
        return art == Art.FASSUNG && entscheidung == Entscheidung.OFFEN;
    }

    /** Eine einzelne Datei, die verschoben wird. */
    public record Verschiebung(Path von, Path nach) {
    }
}
