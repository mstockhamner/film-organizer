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
 */
public record Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien) {

    /**
     * Erkannt: ein Titel ist bekannt (bei Serienfolgen dazu Staffel und Folge). Das Jahr ist keine Pflicht;
     * fehlt es, fehlt es auch im Ordner- und Dateinamen.
     */
    public boolean erkannt() {
        return info != null && !info.titel().isEmpty();
    }
}
