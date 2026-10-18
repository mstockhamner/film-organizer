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
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Durchsucht einen Quellordner rekursiv nach Filmen.
 */
public final class Scanner {

    /** Kleine Dateien mit "sample" im Namen sind Vorschau-Schnipsel, keine Filme. */
    private static final long SAMPLE_GRENZE = 300L * 1024 * 1024;

    private static final Logger LOG = Log.fuer(Scanner.class);

    /**
     * Ordner, die nicht zur Sammlung gehören: der Papierkorb einer Synology-Freigabe ({@code #recycle})
     * und die Vorschaubilder und Metadaten, die die Synology zu jeder Datei anlegt ({@code @eaDir}).
     */
    private static final Set<String> SYSTEMORDNER = Set.of("#recycle", "@eadir");

    private Scanner() {
    }

    /**
     * Findet alle Filme unter {@code quelle}. Liegt der Zielordner innerhalb der Quelle, wird er mit
     * allem darin (Sammlung, Quarantäne {@code _Doubletten}, Verwaltung {@code .filmorganizer})
     * übersprungen, damit einsortierte Filme nicht erneut auftauchen.
     */
    public static List<Film> scannen(Path quelle, Path ziel) throws IOException {
        Path quelleAbs = quelle.toAbsolutePath().normalize();
        Zielordner zielordner = new Zielordner(ziel);
        List<Path> videos = new ArrayList<>();
        Files.walkFileTree(quelleAbs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path ordner, BasicFileAttributes attribute) {
                if (zielordner.istZiel(ordner)) {
                    LOG.info(() -> "Zielordner beim Durchsuchen übersprungen: " + ordner);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!ordner.equals(quelleAbs) && istSystemordner(ordner)) {
                    LOG.info(() -> "Systemordner beim Durchsuchen übersprungen: " + ordner);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
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
     * Erkennt den Zielordner unabhängig von der Schreibweise des Pfads: relativ oder absolut, mit
     * {@code ..}, in anderer Groß-/Kleinschreibung oder über eine Verknüpfung (Symlink, Junction).
     */
    static final class Zielordner {
        private final Path absolut;
        private final Path echt;

        Zielordner(Path ziel) {
            absolut = ziel.toAbsolutePath().normalize();
            Path real;
            try {
                real = Files.exists(absolut) ? absolut.toRealPath() : null;
            } catch (IOException e) {
                real = null;
            }
            echt = real;
        }

        boolean istZiel(Path ordner) {
            Path pfad = ordner.toAbsolutePath().normalize();
            if (pfad.startsWith(absolut) || echt != null && pfad.startsWith(echt)) {
                return true;
            }
            if (echt == null) {
                return false; // Ziel existiert noch nicht, kann also nicht unter einem anderen Namen auftauchen
            }
            try {
                return pfad.toRealPath().startsWith(echt) || Files.isSameFile(pfad, echt);
            } catch (IOException e) {
                return false;
            }
        }
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
        Optional<NamensErkennung.Aufnahme> aufnahme = NamensErkennung.erkennenAufnahme(Dateinamen.ohneEndung(name(video)));
        if (aufnahme.isPresent()) {
            return aufnahmeErkennen(video, aufnahme.get());
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

    /**
     * Sat-Aufnahme: Titel und Beschreibung kommen aus den Receiver-Dateien ({@code .ts.meta}, {@code .eit}),
     * der Dateiname ist nur Ersatz, weil der Receiver Zeichen wie ":" oder "/" darin ersetzt. Das Datum im
     * Namen ist der Aufnahmetag. Das Erscheinungsjahr kommt aus dem Ordner, wenn er denselben Film mit Jahr
     * nennt ({@code The Greatest Showman (2017)/20200612 2226 - ATV HD - The Greatest Showman.ts}), sonst
     * aus der Beschreibung, z.B. "Thriller, USA 2017". Ohne Jahr bleibt es 0 und wird in der Vorschau
     * nachgetragen. Folgen einer Sendereihe (Terra X, Ninjago …) sind keine Filme, sondern Folgen mit
     * Folgentitel, abgelegt unter {@code Serien/Terra X/Terra X - Folgentitel}.
     */
    static FilmInfo aufnahmeErkennen(Path video, NamensErkennung.Aufnahme aufnahme) {
        ReceiverDaten daten = ReceiverDaten.lesen(video);
        String titel = daten.titel().isEmpty() ? aufnahme.titel() : daten.titel();
        int aufloesung = daten.aufloesung(aufnahme.sender());
        String folge = daten.folge(titel);
        if (folge != null) {
            return new FilmInfo(titel, 0, aufloesung, 0, 0, "", "", folge);
        }
        Path ordner = video.getParent();
        if (ordner != null && ordner.getFileName() != null) {
            Optional<FilmInfo> ausOrdner = NamensErkennung.erkennen(ordner.getFileName().toString());
            if (ausOrdner.isPresent()
                    && FilmInfo.normalisieren(ausOrdner.get().titel()).equals(FilmInfo.normalisieren(titel))) {
                FilmInfo info = ausOrdner.get();
                return info.aufloesung() > 0 ? info : new FilmInfo(info.titel(), info.jahr(), aufloesung);
            }
        }
        int jahr = daten.jahr(aufnahme.datum().getYear());
        if (jahr == 0) {
            LOG.fine(() -> "Sat-Aufnahme ohne Erscheinungsjahr: " + video);
        }
        return new FilmInfo(titel, jahr, aufloesung);
    }

    /** Auflösung und Fassung, die im Ordnernamen fehlen, stehen oft im Dateinamen. */
    private static FilmInfo mitAufloesungAusDatei(FilmInfo info, Path video) {
        String datei = video.getFileName().toString();
        int aufloesung = info.aufloesung() != 0 ? info.aufloesung()
                : NamensErkennung.aufloesung(datei.replace('.', ' '));
        String fassung = !info.fassung().isEmpty() ? info.fassung() : NamensErkennung.fassung(Dateinamen.ohneEndung(datei));
        return new FilmInfo(info.titel(), info.jahr(), aufloesung, 0, 0, fassung);
    }

    /**
     * Cover, Untertitel und .nfo im Ordner der Videodatei. Liegen mehrere Videos im selben
     * Ordner, ist die Zuordnung unklar, dann werden diese nicht mitgenommen. Dateien eines Sat-Receivers
     * gehören über ihren Namen eindeutig zur Aufnahme ({@code film.ts.meta}, {@code .ap}, {@code .sc},
     * {@code .cuts}, {@code film.eit}) und werden immer mitgenommen.
     */
    static List<Path> begleitdateien(Path video) throws IOException {
        Path ordner = video.getParent();
        try (Stream<Path> inhalt = Files.list(ordner)) {
            List<Path> dateien = inhalt.filter(Files::isRegularFile).sorted().toList();
            List<Path> receiver = dateien.stream().filter(p -> Dateinamen.istReceiverDatei(name(p), name(video))).toList();
            long anzahlVideos = dateien.stream()
                    .filter(p -> Dateinamen.istVideo(p.getFileName().toString()))
                    .filter(p -> !istSample(p))
                    .count();
            if (anzahlVideos != 1) {
                return receiver;
            }
            List<Path> begleit = new ArrayList<>(dateien.stream()
                    .filter(p -> {
                        String endung = Dateinamen.endung(p.getFileName().toString());
                        return Dateinamen.BILD.contains(endung) || Dateinamen.UNTERTITEL.contains(endung)
                                || Dateinamen.NFO.contains(endung);
                    })
                    .toList());
            begleit.addAll(receiver);
            return begleit;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    static boolean istSystemordner(Path ordner) {
        return SYSTEMORDNER.contains(name(ordner).toLowerCase(Locale.ROOT));
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
