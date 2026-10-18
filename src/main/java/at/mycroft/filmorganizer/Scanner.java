package at.mycroft.filmorganizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Durchsucht einen Quellordner rekursiv nach Filmen.
 */
public final class Scanner {

    /** Kleine Dateien mit "sample" im Namen sind Vorschau-Schnipsel, keine Filme. */
    private static final long SAMPLE_GRENZE = 300L * 1024 * 1024;

    private static final Logger LOG = Log.fuer(Scanner.class);

    private Scanner() {
    }

    /**
     * Findet alle Filme unter {@code quelle}. Der Zielordner wird übersprungen, auch wenn er
     * innerhalb der Quelle liegt, damit einsortierte Filme nicht erneut auftauchen.
     */
    public static List<Film> scannen(Path quelle, Path ziel) throws IOException {
        Path quelleAbs = quelle.toAbsolutePath().normalize();
        Path zielAbs = ziel.toAbsolutePath().normalize();
        List<Path> videos = new ArrayList<>();
        Files.walkFileTree(quelleAbs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path ordner, BasicFileAttributes attribute) {
                return ordner.startsWith(zielAbs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) {
                if (attribute.isRegularFile() && Dateinamen.istVideo(name(datei)) && !istSample(datei)) {
                    videos.add(datei);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path datei, IOException fehler) {
                // Z.B. "System Volume Information" auf Laufwerkswurzeln: überspringen statt abbrechen.
                LOG.warning(() -> "Übersprungen, kein Zugriff: " + datei + " (" + Log.beschreibung(fehler) + ")");
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(videos);
        List<Film> filme = new ArrayList<>();
        for (Path video : videos) {
            filme.add(new Film(video, Files.size(video), erkennen(video, quelleAbs), begleitdateien(video)));
        }
        LOG.info(() -> filme.size() + " Videodateien in " + quelleAbs + " gefunden, davon "
                + filme.stream().filter(Film::erkannt).count() + " mit erkanntem Titel und Jahr");
        return filme;
    }

    /**
     * Der Ordnername hat Vorrang vor dem Namen der Videodatei, weil diese oft nur ein Kürzel
     * trägt ({@code lhd-kater-1080p.mkv}). Gesucht wird vom nächsten Ordner nach oben bis zur Quelle.
     */
    static FilmInfo erkennen(Path video, Path quelle) {
        FilmInfo folge = folgeErkennen(video, quelle);
        if (folge != null) {
            return folge;
        }
        for (Path ordner = video.getParent(); ordner != null && ordner.startsWith(quelle); ordner = ordner.getParent()) {
            if (ordner.getFileName() == null) {
                break; // Laufwerkswurzel wie F:\ hat keinen Namen
            }
            Optional<FilmInfo> info = NamensErkennung.erkennen(ordner.getFileName().toString());
            if (info.isPresent()) {
                return mitAufloesungAusDatei(info.get(), video);
            }
        }
        FilmInfo ausDatei = NamensErkennung.erkennen(Dateinamen.ohneEndung(name(video))).orElse(null);
        if (ausDatei == null) {
            LOG.fine(() -> "Titel und Jahr nicht erkannt: " + video);
        }
        return ausDatei;
    }

    /**
     * Serienfolge, wenn der Datei- oder ein Ordnername eine Kennung wie {@code S01E02} trägt, sonst
     * {@code null}. Staffel und Folge kommen zuerst aus dem Dateinamen, weil in einem Staffelordner
     * viele Folgen liegen. Der Serientitel kommt bevorzugt aus einem Ordner mit Kennung
     * ({@code Breaking.Bad.S01.German.1080p}), sonst aus dem Dateinamen, sonst aus dem Ordner über
     * einem reinen Staffelordner ({@code Breaking Bad/Staffel 1/s01e02.mkv}).
     */
    static FilmInfo folgeErkennen(Path video, Path quelle) {
        FilmInfo nummer = NamensErkennung.erkennenSerie(Dateinamen.ohneEndung(name(video)))
                .filter(FilmInfo::istFolge).orElse(null);
        List<FilmInfo> ausOrdnern = new ArrayList<>();
        FilmInfo ersatz = null;
        for (Path ordner = video.getParent(); ordner != null && ordner.startsWith(quelle); ordner = ordner.getParent()) {
            if (ordner.getFileName() == null) {
                break;
            }
            String ordnerName = ordner.getFileName().toString();
            Optional<FilmInfo> serie = NamensErkennung.erkennenSerie(ordnerName);
            if (serie.isPresent()) {
                if (nummer == null && serie.get().istFolge()) {
                    nummer = serie.get();
                }
                ausOrdnern.add(serie.get());
            } else if (ersatz == null && !ordner.equals(quelle)
                    && ausOrdnern.stream().allMatch(s -> s.titel().isEmpty())) {
                ersatz = NamensErkennung.serienTitel(ordnerName);
            }
        }
        if (nummer == null) {
            return null;
        }
        List<FilmInfo> kandidaten = new ArrayList<>(ausOrdnern);
        kandidaten.add(nummer);
        if (ersatz != null) {
            kandidaten.add(ersatz);
        }
        FilmInfo serie = kandidaten.stream().filter(s -> !s.titel().isEmpty()).findFirst().orElse(null);
        if (serie == null) {
            return null;
        }
        int aufloesung = nummer.aufloesung() != 0 ? nummer.aufloesung() : serie.aufloesung();
        return new FilmInfo(serie.titel(), serie.jahr(), aufloesung, nummer.staffel(), nummer.folge());
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

    private static String name(Path datei) {
        return datei.getFileName() == null ? "" : datei.getFileName().toString();
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
