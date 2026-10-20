package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Verschiebung;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Gleicht zwei schon organisierte Sammlungen auf Doubletten ab (auf Wunsch auch jede Sammlung in sich).
 * Welche Fassung bleibt, folgt denselben Regeln wie beim Einsortieren: identische Datei, dann höhere
 * Auflösung, dann größere Datei; andere Schnittfassungen sind keine Doubletten. Sammlung A ist die
 * Hauptsammlung: Bei identischen Dateien bleibt die Fassung in A. Die schlechtere Fassung kommt in die
 * Quarantäne ihrer eigenen Sammlung, zwischen den Sammlungen wird nichts kopiert. Ändert nichts.
 */
public final class Abgleich {

    /** Was bei einem Fund geschehen soll. */
    public enum Wahl {
        ERSTE_BEHALTEN("1 behalten"),
        ZWEITE_BEHALTEN("2 behalten"),
        BEIDE_BEHALTEN("Beide behalten"),
        OFFEN("bitte wählen");

        private final String text;

        Wahl(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** Art eines Fundes. */
    public enum Art {
        /** Byte für Byte dieselbe Datei. */
        IDENTISCH("Identisch"),
        /** Derselbe Film in derselben Fassung, eine ist besser. */
        DOUBLETTE("Doublette"),
        /** Derselbe Film, aber nicht eindeutig, welche Fassung besser ist. */
        UNKLAR("Unklar"),
        /** Derselbe Film in einer anderen Schnittfassung (z.B. Extended und Kino). */
        FASSUNG("Andere Fassung");

        private final String text;

        Art(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * Ein Film in einer der Sammlungen.
     *
     * @param sammlung   Wurzel der Sammlung
     * @param name       Kurzname der Sammlung für die Anzeige ("A" oder "B")
     * @param info       Titel, Jahr, Auflösung, Fassung …
     * @param video      die Videodatei
     * @param groesse    Größe der Videodatei in Byte
     */
    public record Fassung(Path sammlung, String name, FilmInfo info, Path video, long groesse) {

        /** Anzeige wie "A: Der Pate (1972)/Der Pate (1972).mkv". */
        public String anzeige() {
            return name + ": " + sammlung.relativize(video);
        }
    }

    /**
     * Ein gefundenes Paar.
     *
     * @param art       Art des Fundes
     * @param erste     Fassung 1 (bei zwei Sammlungen die aus A)
     * @param zweite    Fassung 2 (bei zwei Sammlungen die aus B)
     * @param hinweis   Erklärung mit Auflösung und Größe beider Fassungen
     * @param vorschlag was nach den Regeln behalten wird
     */
    public record Fund(Art art, Fassung erste, Fassung zweite, String hinweis, Wahl vorschlag) {

        /** Eindeutige Kennung des Paares, für den gespeicherten Arbeitsstand. */
        public String kennung() {
            return erste.video().toAbsolutePath().normalize() + "|" + zweite.video().toAbsolutePath().normalize();
        }
    }

    private final Map<Path, String> pruefsummen = new HashMap<>();

    private Abgleich() {
    }

    /**
     * Sucht Doubletten zwischen {@code a} und {@code b}. Mit {@code inSich} werden zusätzlich beide
     * Sammlungen jeweils in sich geprüft.
     */
    public static List<Fund> abgleichen(Path a, Path b, boolean inSich) throws IOException {
        List<Fassung> inA = fassungen(a, "A");
        List<Fassung> inB = fassungen(b, "B");
        Abgleich abgleich = new Abgleich();
        List<Fund> funde = new ArrayList<>(abgleich.paare(inA, inB));
        if (inSich) {
            funde.addAll(abgleich.paare(inA, inA));
            funde.addAll(abgleich.paare(inB, inB));
        }
        return funde;
    }

    /** Alle Filme und Folgen einer Sammlung mit eindeutiger Videodatei, laut Katalog und Ordnern. */
    static List<Fassung> fassungen(Path sammlung, String name) throws IOException {
        Katalog katalog = Katalog.laden(sammlung);
        List<Fassung> ergebnis = new ArrayList<>();
        for (Katalog.Eintrag e : katalog.eintraege()) {
            Path video = katalog.videoDatei(e);
            if (video == null) {
                continue;
            }
            long groesse = Files.size(video);
            FilmInfo info = e.info();
            if (info.aufloesung() == 0) {
                // Von Hand abgelegt: Auflösung aus dem Dateinamen, falls sie dort steht.
                int ausName = NamensErkennung.aufloesung(video.getFileName().toString().replace('.', ' '));
                info = new FilmInfo(info.titel(), info.jahr(), ausName, info.staffel(), info.folge(), info.fassung(),
                        info.reihe(), info.folgentitel());
            }
            ergebnis.add(new Fassung(sammlung.toAbsolutePath().normalize(), name, info, video.toAbsolutePath().normalize(),
                    groesse));
        }
        return ergebnis;
    }

    /**
     * Paare zwischen zwei Listen; sind es dieselben (Prüfung in sich), wird jedes Paar nur einmal und
     * nie eine Fassung mit sich selbst gebildet.
     */
    private List<Fund> paare(List<Fassung> erste, List<Fassung> zweite) throws IOException {
        boolean inSich = erste == zweite;
        List<Fund> funde = new ArrayList<>();
        Set<String> gepaart = new HashSet<>();

        // 1. Gleicher Film (Titel und Jahr bzw. Serie und Folge).
        Map<String, List<Fassung>> nachFilm = new LinkedHashMap<>();
        for (Fassung f : zweite) {
            nachFilm.computeIfAbsent(f.info().schluessel(), k -> new ArrayList<>()).add(f);
        }
        for (int i = 0; i < erste.size(); i++) {
            Fassung f1 = erste.get(i);
            for (Fassung f2 : nachFilm.getOrDefault(f1.info().schluessel(), List.of())) {
                if (inSich && zweite.indexOf(f2) <= i) {
                    continue;
                }
                boolean gleicheFassung = f1.info().fassungsSchluessel().equals(f2.info().fassungsSchluessel());
                funde.add(gleicheFassung ? vergleichen(f1, f2)
                        : new Fund(Art.FASSUNG, f1, f2, "andere Fassung: " + bezeichnung(f1) + " / " + bezeichnung(f2),
                                Wahl.BEIDE_BEHALTEN));
                gepaart.add(paar(f1, f2));
            }
        }

        // 2. Identische Datei unter anderem Titel: gleiche Größe, dann Prüfsumme. Auch eine Fassung, die
        //    schon mit einer anderen gepaart ist, kann zusätzlich irgendwo unter falschem Titel kopiert sein.
        Map<Long, List<Fassung>> nachGroesse = new HashMap<>();
        for (Fassung f : zweite) {
            nachGroesse.computeIfAbsent(f.groesse(), k -> new ArrayList<>()).add(f);
        }
        for (int i = 0; i < erste.size(); i++) {
            Fassung f1 = erste.get(i);
            for (Fassung f2 : nachGroesse.getOrDefault(f1.groesse(), List.of())) {
                if ((inSich && zweite.indexOf(f2) <= i) || gepaart.contains(paar(f1, f2))) {
                    continue;
                }
                if (pruefsumme(f1.video()).equals(pruefsumme(f2.video()))) {
                    funde.add(new Fund(Art.IDENTISCH, f1, f2, "identische Datei unter anderem Namen" + vergleich(f1, f2),
                            Wahl.ERSTE_BEHALTEN));
                    gepaart.add(paar(f1, f2));
                }
            }
        }
        return funde;
    }

    private static String paar(Fassung f1, Fassung f2) {
        return f1.video() + "|" + f2.video();
    }

    /** Derselbe Film in derselben Fassung: identisch, eine besser oder unklar. */
    private Fund vergleichen(Fassung f1, Fassung f2) throws IOException {
        if (f1.groesse() == f2.groesse() && pruefsumme(f1.video()).equals(pruefsumme(f2.video()))) {
            return new Fund(Art.IDENTISCH, f1, f2, "identische Datei" + vergleich(f1, f2), Wahl.ERSTE_BEHALTEN);
        }
        int ergebnis = Planer.vergleichen(f1.info().aufloesung(), f1.groesse(), f2.info().aufloesung(), f2.groesse());
        if (ergebnis == 0) {
            return new Fund(Art.UNKLAR, f1, f2, "nicht eindeutig, welche besser ist" + vergleich(f1, f2), Wahl.OFFEN);
        }
        return new Fund(Art.DOUBLETTE, f1, f2,
                (ergebnis > 0 ? "1 ist besser" : "2 ist besser") + vergleich(f1, f2),
                ergebnis > 0 ? Wahl.ERSTE_BEHALTEN : Wahl.ZWEITE_BEHALTEN);
    }

    private static String vergleich(Fassung f1, Fassung f2) {
        return " – 1: " + kurz(f1) + ", 2: " + kurz(f2);
    }

    private static String kurz(Fassung f) {
        String aufloesung = f.info().aufloesung() > 0 ? f.info().aufloesung() + "p" : "Auflösung unbekannt";
        return aufloesung + String.format(Locale.GERMANY, ", %.1f GB", f.groesse() / 1e9);
    }

    private static String bezeichnung(Fassung f) {
        return f.name() + " " + (f.info().fassung().isEmpty() ? "Kinofassung" : f.info().fassung());
    }

    private String pruefsumme(Path datei) throws IOException {
        String summe = pruefsummen.get(datei);
        if (summe == null) {
            summe = Pruefsumme.schnell(datei);
            pruefsummen.put(datei, summe);
        }
        return summe;
    }

    /**
     * Was beim Ausführen verschoben wird: Die nicht behaltene Fassung kommt mit ihren Begleitdateien in die
     * Quarantäne ihrer eigenen Sammlung. Liegt sie allein in ihrem Ordner, wandert der ganze Ordnerinhalt mit,
     * sonst die Dateien, die mit dem Namen des Videos beginnen (Untertitel, .nfo, Receiver-Dateien).
     */
    public static List<Verschiebung> verschiebungen(Fund fund, Wahl wahl) throws IOException {
        Fassung weg = switch (wahl) {
            case ERSTE_BEHALTEN -> fund.zweite();
            case ZWEITE_BEHALTEN -> fund.erste();
            default -> null;
        };
        if (weg == null || !Files.isRegularFile(weg.video())) {
            return List.of();
        }
        Path ordner = weg.video().getParent();
        String stamm = Dateinamen.ohneEndung(weg.video().getFileName().toString());
        List<Path> dateien;
        try (Stream<Path> inhalt = Files.list(ordner)) {
            List<Path> alle = inhalt.filter(Files::isRegularFile).sorted().toList();
            long videos = alle.stream().filter(p -> Dateinamen.istVideo(p.getFileName().toString())).count();
            boolean eigenerOrdner = videos == 1 && !ordner.equals(weg.sammlung());
            if (eigenerOrdner) {
                try (Stream<Path> baum = Files.walk(ordner)) {
                    dateien = baum.filter(Files::isRegularFile).sorted().toList();
                }
            } else {
                dateien = alle.stream().filter(p -> p.getFileName().toString().startsWith(stamm + ".")
                        || p.getFileName().toString().startsWith(stamm + " - ")).toList();
            }
        }
        List<Verschiebung> verschiebungen = new ArrayList<>();
        for (Path datei : dateien) {
            verschiebungen.add(new Verschiebung(datei, Planer.inQuarantaene(weg.sammlung(), datei)));
        }
        return verschiebungen;
    }
}
