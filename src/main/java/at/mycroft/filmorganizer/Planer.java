package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Verschiebung;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Erstellt aus den gefundenen Filmen einen Plan: was einsortiert wird, was eine Doublette ist
 * und was liegen bleibt. Ändert nichts im Dateisystem.
 */
public final class Planer {

    /** Bessere Fassung zuerst: höhere Auflösung, dann größere Datei. */
    static final Comparator<Film> BESSER_ZUERST = Comparator
            .comparingInt((Film f) -> f.info().aufloesung()).reversed()
            .thenComparing(Comparator.comparingLong(Film::groesse).reversed());

    private final Path ziel;
    private final Katalog katalog;
    private final Map<Path, String> pruefsummen = new HashMap<>();

    private Planer(Path ziel, Katalog katalog) {
        this.ziel = ziel;
        this.katalog = katalog;
    }

    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel) throws IOException {
        return new Planer(ziel, katalog).planen(filme);
    }

    private List<Aktion> planen(List<Film> filme) throws IOException {
        List<Aktion> aktionen = new ArrayList<>();

        // 1. Doubletten innerhalb des Imports: gleicher Titel und gleiches Jahr.
        Map<String, List<Film>> nachSchluessel = new LinkedHashMap<>();
        for (Film film : filme) {
            if (!film.erkannt()) {
                aktionen.add(new Aktion(Art.UNERKANNT, film, List.of(), "Titel und Jahr nicht erkennbar"));
            } else {
                nachSchluessel.computeIfAbsent(film.info().schluessel(), k -> new ArrayList<>()).add(film);
            }
        }
        List<Film> kandidaten = new ArrayList<>();
        for (List<Film> gruppe : nachSchluessel.values()) {
            List<Film> sortiert = gruppe.stream().sorted(BESSER_ZUERST).toList();
            Film bester = sortiert.get(0);
            kandidaten.add(bester);
            for (Film andere : sortiert.subList(1, sortiert.size())) {
                aktionen.add(new Aktion(Art.DOUBLETTE, andere, List.of(),
                        "gleicher Film wie " + bester.video() + " (diese Fassung wird einsortiert)"));
            }
        }

        // 2. Doubletten innerhalb des Imports: identische Datei unter anderem Namen.
        List<Film> eindeutig = new ArrayList<>();
        for (Film film : kandidaten) {
            Film gleich = gleicheDatei(film, eindeutig);
            if (gleich != null) {
                aktionen.add(new Aktion(Art.DOUBLETTE, film, List.of(), "identische Datei wie " + gleich.video()));
            } else {
                eindeutig.add(film);
            }
        }

        // 3. Abgleich mit der bestehenden Sammlung und Zielpfade festlegen.
        for (Film film : eindeutig) {
            aktionen.add(gegenSammlung(film));
        }
        return aktionen;
    }

    private Aktion gegenSammlung(Film film) throws IOException {
        Katalog.Eintrag vorhanden = katalog.finden(film.info().schluessel());
        if (vorhanden != null) {
            return new Aktion(Art.DOUBLETTE, film, List.of(),
                    "schon in der Sammlung: " + vorhanden.video() + vergleich(film, vorhanden));
        }
        for (Katalog.Eintrag eintrag : katalog.eintraege()) {
            Path video = ziel.resolve(eintrag.video());
            if (eintrag.groesse() == film.groesse() && Files.isRegularFile(video)
                    && pruefsumme(video).equals(pruefsumme(film.video()))) {
                return new Aktion(Art.DOUBLETTE, film, List.of(),
                        "identische Datei schon in der Sammlung: " + eintrag.video());
            }
        }

        String name = film.info().zielName();
        Path ordner = ziel.resolve(name);
        if (Files.exists(ordner)) {
            return new Aktion(Art.KONFLIKT, film, List.of(), "Zielordner existiert schon: " + ordner);
        }
        List<Verschiebung> verschiebungen = new ArrayList<>();
        String endung = Dateinamen.endung(film.video().getFileName().toString());
        verschiebungen.add(new Verschiebung(film.video(), ordner.resolve(name + "." + endung)));
        for (Map.Entry<Path, String> begleit : begleitNamen(film, name).entrySet()) {
            verschiebungen.add(new Verschiebung(begleit.getKey(), ordner.resolve(begleit.getValue())));
        }
        return new Aktion(Art.EINSORTIEREN, film, verschiebungen, "");
    }

    private static String vergleich(Film neu, Katalog.Eintrag alt) {
        int aufloesungAlt = alt.info().aufloesung();
        int aufloesungNeu = neu.info().aufloesung();
        if (aufloesungAlt > 0 && aufloesungNeu > 0 && aufloesungAlt != aufloesungNeu) {
            return aufloesungNeu > aufloesungAlt
                    ? " (neue Fassung ist besser: " + aufloesungNeu + "p statt " + aufloesungAlt + "p)"
                    : " (vorhandene Fassung ist besser: " + aufloesungAlt + "p statt " + aufloesungNeu + "p)";
        }
        if (alt.groesse() > 0 && alt.groesse() != neu.groesse()) {
            return neu.groesse() > alt.groesse()
                    ? " (neue Datei ist größer)"
                    : " (vorhandene Datei ist größer)";
        }
        return "";
    }

    private Film gleicheDatei(Film film, List<Film> andere) throws IOException {
        for (Film anderer : andere) {
            if (anderer.groesse() == film.groesse()
                    && pruefsumme(anderer.video()).equals(pruefsumme(film.video()))) {
                return anderer;
            }
        }
        return null;
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
     * Neue Namen der Begleitdateien: ein einzelnes Bild wird {@code poster.<endung>}, Untertitel und
     * .nfo heißen wie der Film (eine Sprachkennung wie {@code .de} bleibt erhalten). Was sich nicht
     * eindeutig benennen lässt, behält seinen Namen.
     */
    static Map<Path, String> begleitNamen(Film film, String name) {
        Map<Path, String> ergebnis = new LinkedHashMap<>();
        Set<String> vergeben = new HashSet<>();
        vergeben.add((name + "." + Dateinamen.endung(film.video().getFileName().toString())).toLowerCase(Locale.ROOT));
        long bilder = film.begleitdateien().stream()
                .filter(p -> Dateinamen.BILD.contains(Dateinamen.endung(p.getFileName().toString())))
                .count();
        for (Path datei : film.begleitdateien()) {
            String original = datei.getFileName().toString();
            String endung = Dateinamen.endung(original);
            String wunsch;
            if (Dateinamen.BILD.contains(endung)) {
                wunsch = bilder == 1 ? "poster." + endung : original;
            } else if (Dateinamen.UNTERTITEL.contains(endung)) {
                String sprache = sprachkennung(original);
                wunsch = name + (sprache.isEmpty() ? "" : "." + sprache) + "." + endung;
            } else {
                wunsch = name + "." + endung;
            }
            String vergebenerName = vergeben.add(wunsch.toLowerCase(Locale.ROOT)) ? wunsch : eindeutig(original, vergeben);
            ergebnis.put(datei, vergebenerName);
        }
        return ergebnis;
    }

    private static String eindeutig(String original, Set<String> vergeben) {
        String kandidat = original;
        for (int i = 2; !vergeben.add(kandidat.toLowerCase(Locale.ROOT)); i++) {
            kandidat = Dateinamen.ohneEndung(original) + "-" + i + "." + Dateinamen.endung(original);
        }
        return kandidat;
    }

    /** Kennung wie {@code de} oder {@code ger} aus {@code film.de.srt}, sonst leer. */
    private static String sprachkennung(String dateiname) {
        String ohneEndung = Dateinamen.ohneEndung(dateiname);
        String letzte = Dateinamen.endung(ohneEndung);
        return letzte.matches("[a-z]{2,3}") && ohneEndung.contains(".") ? letzte : "";
    }
}
