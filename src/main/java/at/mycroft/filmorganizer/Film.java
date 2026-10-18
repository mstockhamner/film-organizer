package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.util.List;

/**
 * Ein in einem Quellordner gefundener Film.
 *
 * @param video          die Videodatei
 * @param groesse        Größe der Videodatei in Byte
 * @param info           erkannter Titel und Jahr, {@code null} wenn nichts erkannt wurde; bei einem Film
 *                       ohne erkennbares Jahr (z.B. einer Sat-Aufnahme) ist das Jahr 0
 * @param begleitdateien Cover, Untertitel und .nfo, die zum Film gehören
 * @param notiz          Erklärung, warum ein Film nicht erkannt wurde (z.B. Folge einer Sendereihe), sonst leer
 */
public record Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien, String notiz) {

    public Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien) {
        this(video, groesse, info, begleitdateien, "");
    }

    /** Vollständig erkannt: Titel und Jahr, bei Serienfolgen Titel, Staffel und Folge. */
    public boolean erkannt() {
        return info != null && (info.istFolge() || info.jahr() > 0);
    }

    /** Wenigstens ein Titel ist bekannt, auch wenn das Jahr noch fehlt. */
    public boolean mitTitel() {
        return info != null && !info.titel().isEmpty();
    }
}
