package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Was aus einem Ordner- oder Dateinamen über einen Film oder eine Serienfolge herausgelesen wurde.
 *
 * @param titel      Titel des Films oder der Serie so, wie er im Namen steht (keine Übersetzung)
 * @param jahr       Erscheinungsjahr, bei Serienfolgen 0 wenn unbekannt
 * @param aufloesung vertikale Auflösung in Pixeln (z.B. 1080), 0 wenn unbekannt
 * @param staffel    Staffel einer Serienfolge ({@code S01} = 1), 0 bei Filmen
 * @param folge      Folge innerhalb der Staffel ({@code E02} = 2), 0 bei Filmen
 */
public record FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge) {

    /** Unterordner im Ziel, in dem die Serien liegen. */
    static final String SERIEN = "Serien";

    public FilmInfo(String titel, int jahr, int aufloesung) {
        this(titel, jahr, aufloesung, 0, 0);
    }

    /** Serienfolge statt Film. */
    public boolean istFolge() {
        return folge > 0;
    }

    /** Kennung wie {@code S01E02}. */
    public String kennung() {
        return String.format(Locale.ROOT, "S%02dE%02d", staffel, folge);
    }

    /**
     * Name der Zieldatei ohne Endung: {@code Titel (Jahr)} bei Filmen, {@code Titel - S01E02} bei
     * Serienfolgen. Bei Filmen heißt auch der Zielordner so.
     */
    public String zielName() {
        return istFolge()
                ? Dateinamen.bereinigen(titel) + " - " + kennung()
                : Dateinamen.bereinigen(titel) + " (" + jahr + ")";
    }

    /** {@code Ziel/Titel (Jahr)} bei Filmen, {@code Ziel/Serien/Titel/Staffel 01} bei Serienfolgen. */
    public Path zielOrdner(Path ziel) {
        return istFolge()
                ? ziel.resolve(SERIEN).resolve(Dateinamen.bereinigen(titel))
                        .resolve(String.format(Locale.ROOT, "Staffel %02d", staffel))
                : ziel.resolve(zielName());
    }

    /**
     * Vergleichsschlüssel für die Doublettenprüfung: Groß-/Kleinschreibung, Akzente,
     * Umlaute und Satzzeichen spielen keine Rolle. Bei Serienfolgen zählen Titel, Staffel und
     * Folge, nicht das Jahr, weil es oft nur in manchen Namen steht.
     */
    public String schluessel() {
        return istFolge()
                ? normalisieren(titel) + "|" + kennung()
                : normalisieren(titel) + "|" + jahr;
    }

    static String normalisieren(String text) {
        String ohneUmlaute = text.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", "and");
        String ohneAkzente = Normalizer.normalize(ohneUmlaute, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ohneAkzente.replaceAll("[^a-z0-9]", "");
    }
}
