package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.util.List;

/**
 * Ein in einem Quellordner gefundener Film.
 *
 * @param video          die Videodatei
 * @param groesse        Größe der Videodatei in Byte
 * @param info           erkannter Titel und Jahr, {@code null} wenn nicht erkannt
 * @param begleitdateien Cover, Untertitel und .nfo, die zum Film gehören
 */
public record Film(Path video, long groesse, FilmInfo info, List<Path> begleitdateien) {

    public boolean erkannt() {
        return info != null;
    }
}
