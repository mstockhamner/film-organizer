package at.mycroft.filmorganizer;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Was aus einem Ordner- oder Dateinamen über einen Film herausgelesen wurde.
 *
 * @param titel      Titel so, wie er im Namen steht (keine Übersetzung)
 * @param jahr       Erscheinungsjahr
 * @param aufloesung vertikale Auflösung in Pixeln (z.B. 1080), 0 wenn unbekannt
 */
public record FilmInfo(String titel, int jahr, int aufloesung) {

    /** Name von Zielordner und Zieldatei nach dem Schema {@code Titel (Jahr)}. */
    public String zielName() {
        return Dateinamen.bereinigen(titel) + " (" + jahr + ")";
    }

    /**
     * Vergleichsschlüssel für die Doublettenprüfung: Groß-/Kleinschreibung, Akzente,
     * Umlaute und Satzzeichen spielen keine Rolle.
     */
    public String schluessel() {
        return normalisieren(titel) + "|" + jahr;
    }

    static String normalisieren(String text) {
        String ohneUmlaute = text.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", "and");
        String ohneAkzente = Normalizer.normalize(ohneUmlaute, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ohneAkzente.replaceAll("[^a-z0-9]", "");
    }
}
