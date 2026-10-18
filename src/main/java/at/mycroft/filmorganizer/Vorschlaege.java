package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        aktionen.addAll(Planer.planen(zuPlanen, katalog, ziel));
        aktionen.sort(Comparator.comparing(a -> a.film().video()));
        return aktionen;
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
                    : new FilmInfo(k.titel(), k.jahr(), aufloesung);
        }
        return new Film(film.video(), film.groesse(), info, film.begleitdateien());
    }
}
