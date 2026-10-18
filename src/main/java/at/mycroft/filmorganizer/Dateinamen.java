package at.mycroft.filmorganizer;

import java.util.Locale;
import java.util.Set;

/**
 * Hilfsfunktionen rund um Datei- und Ordnernamen.
 */
public final class Dateinamen {

    static final Set<String> VIDEO = Set.of("mkv", "mp4", "avi", "m4v", "ts", "m2ts", "mov", "wmv", "mpg", "mpeg");
    static final Set<String> BILD = Set.of("jpg", "jpeg", "png", "webp", "bmp");
    static final Set<String> UNTERTITEL = Set.of("srt", "sub", "idx", "ass", "ssa");
    static final Set<String> NFO = Set.of("nfo");

    private Dateinamen() {
    }

    /** Ersetzt Zeichen, die Windows in Datei- und Ordnernamen nicht erlaubt. */
    public static String bereinigen(String name) {
        String ergebnis = name
                .replace(":", " -")
                .replaceAll("[\\\\/*?\"<>|]", " ")
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("\\s+", " ")
                .trim();
        // Windows erlaubt keine Namen, die mit Punkt oder Leerzeichen enden.
        while (ergebnis.endsWith(".")) {
            ergebnis = ergebnis.substring(0, ergebnis.length() - 1).trim();
        }
        return ergebnis;
    }

    /** Endung in Kleinbuchstaben ohne Punkt, leer wenn keine vorhanden. */
    public static String endung(String dateiname) {
        int punkt = dateiname.lastIndexOf('.');
        return punkt < 0 ? "" : dateiname.substring(punkt + 1).toLowerCase(Locale.ROOT);
    }

    /** Dateiname ohne Endung. */
    public static String ohneEndung(String dateiname) {
        int punkt = dateiname.lastIndexOf('.');
        return punkt < 0 ? dateiname : dateiname.substring(0, punkt);
    }

    static boolean istVideo(String dateiname) {
        return VIDEO.contains(endung(dateiname));
    }
}
