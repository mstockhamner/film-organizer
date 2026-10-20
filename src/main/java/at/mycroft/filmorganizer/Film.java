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
 * @param vorlaeufig     der Titel ist nur der bereinigte Dateiname (z.B. "irgendwas" aus {@code irgendwas.mkv})
 *                       und muss noch korrekt benannt werden
 */
public record Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien, boolean vorlaeufig) {

    public Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien) {
        this(video, groesse, info, begleitdateien, false);
    }

    /** Derselbe Film mit anderer Info; ein vorläufiger Titel bleibt vorläufig. */
    public Film mitInfo(FilmInfo andereInfo) {
        return new Film(video, groesse, andereInfo, begleitdateien, vorlaeufig);
    }

    /**
     * Erkannt: ein Titel ist bekannt (bei Serienfolgen dazu Staffel und Folge). Das Jahr ist keine Pflicht;
     * fehlt es, fehlt es auch im Ordner- und Dateinamen.
     */
    public boolean erkannt() {
        return info != null && !info.titel().isEmpty();
    }
}
