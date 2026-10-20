package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Die eingelesenen Filme mit den vorgeschlagenen Titeln und Jahren, die vor dem Einsortieren
 * noch korrigiert oder abgewählt werden können. Jede Änderung ergibt einen neuen Plan, ohne
 * dass erneut gescannt wird.
 *
 * <p>Mit einem Arbeitsstand ({@link #einlesen(Path, Path, Path)}) wird jede Änderung sofort lokal
 * gespeichert und beim nächsten Einlesen derselben Quelle mit demselben Ziel wiederhergestellt, damit
 * die Liste über mehrere Sitzungen vervollständigt werden kann.
 */
public final class Vorschlaege {

    private static final Logger LOG = Log.fuer(Vorschlaege.class);
    private static final String KORREKTUR = "KORREKTUR";
    private static final String AUSGELASSEN = "AUSGELASSEN";
    private static final String ENTSCHEIDUNG = "ENTSCHEIDUNG";
    private static final String REIHE = "REIHE";

    private final Path ziel;
    private final Katalog katalog;
    private final List<Film> gescannt;
    private final Map<Path, Korrektur> korrekturen = new HashMap<>();
    private final Set<Path> ausgelassen = new HashSet<>();
    private final Map<Path, Entscheidung> entscheidungen = new HashMap<>();
    private final Map<Path, String> reihen = new HashMap<>();
    /** Datei des Arbeitsstands, {@code null} ohne Speichern. */
    private Path arbeitsstand;

    /** Von Hand eingetragener Titel und Jahr; leerer Titel oder Jahr 0 heißt "noch unvollständig". */
    private record Korrektur(String titel, int jahr) {
    }

    private Vorschlaege(Path ziel, Katalog katalog, List<Film> gescannt) {
        this.ziel = ziel;
        this.katalog = katalog;
        this.gescannt = gescannt;
    }

    /** Liest alle Filme der Quelle ein. Ändert nichts im Dateisystem. */
    public static Vorschlaege einlesen(Path quelle, Path ziel) throws IOException {
        return new Vorschlaege(ziel, Katalog.laden(ziel), Scanner.scannen(quelle, ziel));
    }

    /**
     * Liest alle Filme der Quelle ein und stellt den gespeicherten Arbeitsstand zu Quelle und Ziel aus
     * {@code speicherordner} wieder her. Danach wird jede Änderung dort sofort gespeichert. Im Ziel und in
     * der Quelle wird nichts verändert.
     */
    public static Vorschlaege einlesen(Path quelle, Path ziel, Path speicherordner) throws IOException {
        Vorschlaege v = einlesen(quelle, ziel);
        v.arbeitsstand = arbeitsstandDatei(speicherordner, quelle, ziel);
        v.arbeitsstandLaden();
        return v;
    }

    /** Standardordner für Arbeitsstände: {@code ~/.filmorganizer/arbeitsstaende}, neben dem Logfile. */
    public static Path standardSpeicherordner() {
        return Path.of(System.getProperty("user.home"), ".filmorganizer", "arbeitsstaende");
    }

    /** Eine Datei je Quelle und Ziel, benannt nach einer Prüfsumme der beiden Pfade. */
    static Path arbeitsstandDatei(Path speicherordner, Path quelle, Path ziel) {
        String schluessel = quelle.toAbsolutePath().normalize() + "\n" + ziel.toAbsolutePath().normalize();
        try {
            byte[] summe = MessageDigest.getInstance("SHA-256").digest(schluessel.getBytes(StandardCharsets.UTF_8));
            return speicherordner.resolve(HexFormat.of().formatHex(summe, 0, 8) + ".tsv");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Vorschlaege aus(List<Film> filme, Katalog katalog, Path ziel) {
        return new Vorschlaege(ziel, katalog, filme);
    }

    public Path ziel() {
        return ziel;
    }

    /** Anzahl der Filme mit gespeicherten Änderungen (Korrektur, Abwahl, Entscheidung, Filmreihe). */
    public int anzahlAenderungen() {
        Set<Path> videos = new HashSet<>(korrekturen.keySet());
        videos.addAll(ausgelassen);
        videos.addAll(entscheidungen.keySet());
        videos.addAll(reihen.keySet());
        return videos.size();
    }

    /** Verwirft alle Änderungen und den gespeicherten Arbeitsstand. */
    public void verwerfen() {
        korrekturen.clear();
        ausgelassen.clear();
        entscheidungen.clear();
        reihen.clear();
        merken();
    }

    /**
     * Nach dem Einsortieren: Änderungen zu Filmen, die verschoben wurden, sind erledigt und fallen aus dem
     * Arbeitsstand. Alles andere (z.B. abgewählte oder noch unvollständige Filme) bleibt erhalten.
     */
    public void nachEinsortieren(List<Aktion> ausgefuehrt) {
        for (Aktion aktion : ausgefuehrt) {
            if (!aktion.verschiebungen().isEmpty()) {
                Path video = aktion.film().video();
                korrekturen.remove(video);
                ausgelassen.remove(video);
                entscheidungen.remove(video);
                reihen.remove(video);
            }
        }
        merken();
    }

    /** Setzt Titel und Jahr eines Films von Hand. */
    public void korrigieren(Path video, String titel, int jahr) {
        korrekturen.put(video, new Korrektur(titel == null ? "" : titel.trim(), jahr));
        merken();
    }

    /** Wählt einen Film für das Einsortieren ab oder wieder an. */
    public void auslassen(Path video, boolean auslassen) {
        if (auslassen) {
            ausgelassen.add(video);
        } else {
            ausgelassen.remove(video);
        }
        merken();
    }

    /** Wählt mehrere Filme auf einmal ab oder wieder an (ein Speichervorgang). */
    public void auslassen(Collection<Path> videos, boolean auslassen) {
        if (auslassen) {
            ausgelassen.addAll(videos);
        } else {
            ausgelassen.removeAll(videos);
        }
        merken();
    }

    public boolean istAusgelassen(Path video) {
        return ausgelassen.contains(video);
    }

    /** Legt fest, was mit einer anderen Fassung eines vorhandenen Films geschieht. */
    public void entscheiden(Path video, Entscheidung entscheidung) {
        entscheidungen.put(video, entscheidung);
        merken();
    }

    /**
     * Speichert den Arbeitsstand: eine Zeile je Änderung, Art, Videodatei und Werte durch Tabulatoren
     * getrennt. Auch Änderungen zu Filmen, die gerade nicht erreichbar sind (z.B. NAS aus), bleiben drin.
     */
    private void merken() {
        if (arbeitsstand == null) {
            return;
        }
        List<String> zeilen = new ArrayList<>();
        zeilen.add("# Arbeitsstand des Filmorganizers für Ziel " + ziel.toAbsolutePath().normalize());
        korrekturen.forEach((v, k) -> zeilen.add(zeile(KORREKTUR, v, k.titel(), Integer.toString(k.jahr()))));
        ausgelassen.forEach(v -> zeilen.add(zeile(AUSGELASSEN, v)));
        entscheidungen.forEach((v, e) -> zeilen.add(zeile(ENTSCHEIDUNG, v, e.name())));
        reihen.forEach((v, r) -> zeilen.add(zeile(REIHE, v, r)));
        try {
            Files.createDirectories(arbeitsstand.getParent());
            Files.write(arbeitsstand, zeilen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Arbeitsstand nicht gespeichert: " + arbeitsstand, e);
        }
    }

    private static String zeile(String art, Path video, String... werte) {
        StringBuilder zeile = new StringBuilder(art).append('\t').append(video);
        for (String wert : werte) {
            zeile.append('\t').append(wert.replaceAll("[\\t\\r\\n]", " "));
        }
        return zeile.toString();
    }

    private void arbeitsstandLaden() throws IOException {
        if (!Files.isRegularFile(arbeitsstand)) {
            return;
        }
        for (String zeile : Files.readAllLines(arbeitsstand, StandardCharsets.UTF_8)) {
            String[] t = zeile.split("\t", -1);
            if (zeile.startsWith("#") || t.length < 2) {
                continue;
            }
            Path video = Path.of(t[1]);
            try {
                switch (t[0]) {
                    case KORREKTUR -> korrekturen.put(video, new Korrektur(t[2], Integer.parseInt(t[3])));
                    case AUSGELASSEN -> ausgelassen.add(video);
                    case ENTSCHEIDUNG -> entscheidungen.put(video, Entscheidung.valueOf(t[2]));
                    case REIHE -> reihen.put(video, t[2]);
                    default -> LOG.warning(() -> "Unbekannte Zeile im Arbeitsstand: " + zeile);
                }
            } catch (RuntimeException e) {
                LOG.warning(() -> "Zeile im Arbeitsstand übersprungen: " + zeile);
            }
        }
        LOG.info(() -> "Arbeitsstand geladen: " + arbeitsstand + " (" + anzahlAenderungen() + " Filme mit Änderungen)");
    }

    public Entscheidung entscheidung(Path video) {
        return entscheidungen.getOrDefault(video, Entscheidung.OFFEN);
    }

    /** Aktueller Titel eines Films: Korrektur, sonst Erkennung, sonst leer. */
    public String titel(Film film) {
        Korrektur k = korrekturen.get(film.video());
        return k != null ? k.titel() : film.info() != null ? film.info().titel() : "";
    }

    /** Aktuelles Jahr eines Films: Korrektur, sonst Erkennung, sonst 0. */
    public int jahr(Film film) {
        Korrektur k = korrekturen.get(film.video());
        return k != null ? k.jahr() : film.info() != null ? film.info().jahr() : 0;
    }

    public boolean istKorrigiert(Path video) {
        return korrekturen.containsKey(video);
    }

    /** Plan mit allen Korrekturen, sortiert nach Fundort wie im Dateisystem. */
    public List<Aktion> plan() throws IOException {
        List<Film> zuPlanen = new ArrayList<>();
        List<Aktion> aktionen = new ArrayList<>();
        for (Film film : gescannt) {
            if (ausgelassen.contains(film.video())) {
                aktionen.add(new Aktion(Art.AUSGELASSEN, film, List.of(), "abgewählt"));
            } else {
                zuPlanen.add(mitKorrektur(film));
            }
        }
        for (Aktion aktion : Planer.planen(zuPlanen, katalog, ziel, entscheidungen, reihen)) {
            // Auch das Umsortieren eines Films aus der Sammlung lässt sich abwählen.
            aktionen.add(aktion.art() == Art.UMSORTIEREN && ausgelassen.contains(aktion.film().video())
                    ? new Aktion(Art.AUSGELASSEN, aktion.film(), List.of(), "abgewählt, bleibt an seinem Platz")
                    : aktion);
        }
        aktionen.sort(Comparator.comparing(a -> a.film().video()));
        return aktionen;
    }

    /** Setzt die Filmreihe eines Films von Hand; leer heißt "gehört zu keiner Reihe". */
    public void reiheSetzen(Path video, String reihe) {
        reihen.put(video, reihe == null ? "" : reihe.trim());
        merken();
    }

    /**
     * Merkt die in der Vorschau von Hand gesetzten Filmreihen dauerhaft in
     * {@code Ziel/.filmorganizer/filmreihen.tsv}, damit sie auch bei späteren Läufen gelten. Wird nach
     * dem Einsortieren aufgerufen, also erst nach Bestätigung.
     *
     * @return Anzahl gemerkter Zuordnungen
     */
    public int zuordnungenMerken(List<Aktion> plan) throws IOException {
        if (reihen.isEmpty()) {
            return 0;
        }
        Reihenzuordnung gemerkt = Reihenzuordnung.laden(ziel);
        int anzahl = 0;
        for (Aktion aktion : plan) {
            String reihe = reihen.get(aktion.film().video());
            if (reihe != null && aktion.film().erkannt() && !aktion.film().info().istFolge()) {
                gemerkt.setzen(aktion.film().info(), reihe);
                anzahl++;
            }
        }
        gemerkt.speichern();
        return anzahl;
    }

    /**
     * Ein Film der Sammlung für die Verwaltung der Filmreihen.
     *
     * @param video Videodatei (oder Filmordner), unter der eine Reihe von Hand gesetzt wird
     * @param info  Titel, Jahr und die Reihe, in der er jetzt liegt
     * @param reihe die Reihe, in die er soll: von Hand gesetzt, sonst die jetzige
     */
    public record Sammlungsfilm(Path video, FilmInfo info, String reihe) {
    }

    /** Alle Filme der Sammlung (ohne Serienfolgen), nach Titel sortiert. */
    public List<Sammlungsfilm> sammlung() throws IOException {
        List<Sammlungsfilm> filme = new ArrayList<>();
        for (Katalog.Filmordner f : katalog.filmordner()) {
            FilmInfo info = f.eintrag().info();
            filme.add(new Sammlungsfilm(f.video(), info, reihen.getOrDefault(f.video(), info.reihe())));
        }
        filme.sort(Comparator.comparing((Sammlungsfilm s) -> s.info().titel(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(s -> s.info().jahr()));
        return filme;
    }

    /**
     * Gibt allen Filmen der Sammlung in der Reihe {@code alt} die Reihe {@code neu}; leer löst die Reihe
     * auf. Verschoben wird erst beim Einsortieren.
     *
     * @return Anzahl betroffener Filme
     */
    public int reiheUmbenennen(String alt, String neu) throws IOException {
        int anzahl = 0;
        for (Sammlungsfilm s : sammlung()) {
            if (!alt.isEmpty() && s.reihe().equalsIgnoreCase(alt)) {
                reiheSetzen(s.video(), neu);
                anzahl++;
            }
        }
        return anzahl;
    }

    /** Namen aller Filmreihen, die es in der Sammlung schon gibt. */
    public Set<String> sammlungsReihen() {
        Set<String> reihen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        katalog.eintraege().stream().map(e -> e.info().reihe()).filter(r -> !r.isEmpty()).forEach(reihen::add);
        return reihen;
    }

    /** Ob der Film beim Einlesen in der Quelle gefunden wurde (und nicht schon in der Sammlung liegt). */
    public boolean istGescannt(Path video) {
        return gescannt.stream().anyMatch(f -> f.video().equals(video));
    }

    private Film mitKorrektur(Film film) {
        Korrektur k = korrekturen.get(film.video());
        if (k == null) {
            return film;
        }
        // Bei Serienfolgen bleiben Staffel und Folge erhalten, das Jahr ist dort nicht nötig.
        boolean folge = film.info() != null && film.info().istFolge();
        FilmInfo info = null;
        if (!k.titel().isEmpty() && (k.jahr() > 0 || folge)) {
            int aufloesung = film.info() != null ? film.info().aufloesung()
                    : NamensErkennung.aufloesung(film.video().getFileName().toString().replace('.', ' '));
            // Mit vorhandener Erkennung bleiben Auflösung, Fassung, Reihe und Folge erhalten.
            info = film.info() != null
                    ? film.info().mitTitelUndJahr(k.titel(), k.jahr())
                    : new FilmInfo(k.titel(), k.jahr(), aufloesung, 0, 0,
                            NamensErkennung.fassung(Dateinamen.ohneEndung(film.video().getFileName().toString())));
        }
        return new Film(film.video(), film.groesse(), info, film.begleitdateien());
    }
}
