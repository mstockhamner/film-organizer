package at.mycroft.filmorganizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Durchsucht einen Quellordner rekursiv nach Filmen.
 */
public final class Scanner {

    /** Kleine Dateien mit "sample" im Namen sind Vorschau-Schnipsel, keine Filme. */
    private static final long SAMPLE_GRENZE = 300L * 1024 * 1024;

    private Scanner() {
    }

    /**
     * Findet alle Filme unter {@code quelle}. Der Zielordner wird übersprungen, auch wenn er
     * innerhalb der Quelle liegt, damit einsortierte Filme nicht erneut auftauchen.
     */
    public static List<Film> scannen(Path quelle, Path ziel) throws IOException {
        Path quelleAbs = quelle.toAbsolutePath().normalize();
        Path zielAbs = ziel.toAbsolutePath().normalize();
        List<Path> videos;
        try (Stream<Path> dateien = Files.walk(quelleAbs)) {
            videos = dateien
                    .filter(p -> !p.startsWith(zielAbs))
                    .filter(Files::isRegularFile)
                    .filter(p -> Dateinamen.istVideo(p.getFileName().toString()))
                    .filter(p -> !istSample(p))
                    .sorted()
                    .toList();
        }
        List<Film> filme = new ArrayList<>();
        for (Path video : videos) {
            filme.add(new Film(video, Files.size(video), erkennen(video, quelleAbs), begleitdateien(video)));
        }
        return filme;
    }

    /**
     * Der Ordnername hat Vorrang vor dem Namen der Videodatei, weil diese oft nur ein Kürzel
     * trägt ({@code lhd-kater-1080p.mkv}). Gesucht wird vom nächsten Ordner nach oben bis zur Quelle.
     */
    static FilmInfo erkennen(Path video, Path quelle) {
        for (Path ordner = video.getParent(); ordner != null && ordner.startsWith(quelle); ordner = ordner.getParent()) {
            Optional<FilmInfo> info = NamensErkennung.erkennen(ordner.getFileName().toString());
            if (info.isPresent()) {
                return mitAufloesungAusDatei(info.get(), video);
            }
        }
        return NamensErkennung.erkennen(Dateinamen.ohneEndung(video.getFileName().toString())).orElse(null);
    }

    private static FilmInfo mitAufloesungAusDatei(FilmInfo info, Path video) {
        if (info.aufloesung() != 0) {
            return info;
        }
        int ausDatei = NamensErkennung.aufloesung(video.getFileName().toString().replace('.', ' '));
        return new FilmInfo(info.titel(), info.jahr(), ausDatei);
    }

    /**
     * Cover, Untertitel und .nfo im Ordner der Videodatei. Liegen mehrere Videos im selben
     * Ordner, ist die Zuordnung unklar, dann werden keine Begleitdateien mitgenommen.
     */
    static List<Path> begleitdateien(Path video) throws IOException {
        Path ordner = video.getParent();
        try (Stream<Path> inhalt = Files.list(ordner)) {
            List<Path> dateien = inhalt.filter(Files::isRegularFile).sorted().toList();
            long anzahlVideos = dateien.stream()
                    .filter(p -> Dateinamen.istVideo(p.getFileName().toString()))
                    .filter(p -> !istSample(p))
                    .count();
            if (anzahlVideos != 1) {
                return List.of();
            }
            return dateien.stream()
                    .filter(p -> {
                        String endung = Dateinamen.endung(p.getFileName().toString());
                        return Dateinamen.BILD.contains(endung) || Dateinamen.UNTERTITEL.contains(endung)
                                || Dateinamen.NFO.contains(endung);
                    })
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static boolean istSample(Path datei) {
        if (!datei.getFileName().toString().toLowerCase(Locale.ROOT).contains("sample")) {
            return false;
        }
        try {
            return Files.size(datei) < SAMPLE_GRENZE;
        } catch (IOException e) {
            return false;
        }
    }
}
