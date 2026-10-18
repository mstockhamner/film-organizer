package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Die eingelesenen Filme mit den vorgeschlagenen Titeln und Jahren, die vor dem Einsortieren
 * noch korrigiert oder abgewählt werden können. Jede Änderung ergibt einen neuen Plan, ohne
 * dass erneut gescannt wird.
 */
public final class Vorschlaege {

    private final Path ziel;
    private final Katalog katalog;
    private final List<Film> gescannt;
    private final Map<Path, Korrektur> korrekturen = new HashMap<>();
    private final Set<Path> ausgelassen = new HashSet<>();
    private final Map<Path, Entscheidung> entscheidungen = new HashMap<>();
    private final Map<Path, String> reihen = new HashMap<>();

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

    static Vorschlaege aus(List<Film> filme, Katalog katalog, Path ziel) {
        return new Vorschlaege(ziel, katalog, filme);
    }

    public Path ziel() {
        return ziel;
    }

    /** Setzt Titel und Jahr eines Films von Hand. */
    public void korrigieren(Path video, String titel, int jahr) {
        korrekturen.put(video, new Korrektur(titel == null ? "" : titel.trim(), jahr));
    }

    /** Wählt einen Film für das Einsortieren ab oder wieder an. */
    public void auslassen(Path video, boolean auslassen) {
        if (auslassen) {
            ausgelassen.add(video);
        } else {
            ausgelassen.remove(video);
        }
    }

    public boolean istAusgelassen(Path video) {
        return ausgelassen.contains(video);
    }

    /** Legt fest, was mit einer anderen Fassung eines vorhandenen Films geschieht. */
    public void entscheiden(Path video, Entscheidung entscheidung) {
        entscheidungen.put(video, entscheidung);
    }

    public Entscheidung entscheidung(Path video) {
        return entscheidungen.getOrDefault(video, Entscheidung.OFFEN);
    }

    /** Aktueller Titel eines Films: Korrektur, sonst Erkennung, sonst leer. */
    public String titel(Film film) {
        Korrektur k = korrekturen.get(film.video());
        return k != null ? k.titel() : film.erkannt() ? film.info().titel() : "";
    }

    /** Aktuelles Jahr eines Films: Korrektur, sonst Erkennung, sonst 0. */
    public int jahr(Film film) {
        Korrektur k = korrekturen.get(film.video());
        return k != null ? k.jahr() : film.erkannt() ? film.info().jahr() : 0;
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
        boolean folge = film.erkannt() && film.info().istFolge();
        FilmInfo info = null;
        if (!k.titel().isEmpty() && (k.jahr() > 0 || folge)) {
            int aufloesung = film.erkannt() ? film.info().aufloesung()
                    : NamensErkennung.aufloesung(film.video().getFileName().toString().replace('.', ' '));
            info = folge
                    ? new FilmInfo(k.titel(), k.jahr(), aufloesung, film.info().staffel(), film.info().folge())
                    : new FilmInfo(k.titel(), k.jahr(), aufloesung, 0, 0, film.erkannt() ? film.info().fassung()
                            : NamensErkennung.fassung(Dateinamen.ohneEndung(film.video().getFileName().toString())));
        }
        return new Film(film.video(), film.groesse(), info, film.begleitdateien());
    }
}
