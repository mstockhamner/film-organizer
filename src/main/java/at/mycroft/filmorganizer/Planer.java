package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import at.mycroft.filmorganizer.Aktion.Verschiebung;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 *
 * <p>Doubletten kommen in die Quarantäne {@code Ziel/_Doubletten/<Datum>/}, wenn sie eine identische
 * Datei oder eine Fassung mit niedrigerer Auflösung sind. Bessere oder nicht eindeutig vergleichbare
 * Fassungen bleiben in der Quelle liegen.
 */
public final class Planer {

    /** Bessere Fassung zuerst: höhere Auflösung, dann größere Datei. */
    static final Comparator<Film> BESSER_ZUERST = Comparator
            .comparingInt((Film f) -> f.info().aufloesung()).reversed()
            .thenComparing(Comparator.comparingLong(Film::groesse).reversed());

    /** Ordner im Ziel, in den Doubletten verschoben werden, statt sie zu löschen. */
    public static final String QUARANTAENE = "_Doubletten";

    private final Path ziel;
    private final Path quarantaene;
    private final Katalog katalog;
    private final Map<Path, String> pruefsummen = new HashMap<>();

    private final Map<Path, Entscheidung> entscheidungen;
    /** In diesem Lauf zum Einsortieren vorgesehene Filme, damit weitere Fassungen sie als vorhanden sehen. */
    private final List<Katalog.Eintrag> eingeplant = new ArrayList<>();

    private Planer(Path ziel, Katalog katalog, Map<Path, Entscheidung> entscheidungen) {
        this.ziel = ziel;
        this.quarantaene = ziel.resolve(QUARANTAENE).resolve(LocalDate.now().toString());
        this.katalog = katalog;
        this.entscheidungen = entscheidungen;
    }

    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel) throws IOException {
        return planen(filme, katalog, ziel, Map.of());
    }

    /** Wie {@link #planen(List, Katalog, Path)}, mit den Entscheidungen zu anderen Fassungen je Videodatei. */
    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel,
            Map<Path, Entscheidung> entscheidungen) throws IOException {
        return new Planer(ziel, katalog, entscheidungen).planen(filme);
    }

    private List<Aktion> planen(List<Film> filme) throws IOException {
        List<Aktion> aktionen = new ArrayList<>();

        // 1. Doubletten innerhalb des Imports: gleicher Titel, gleiches Jahr und gleiche Fassung.
        Map<String, List<Film>> nachSchluessel = new LinkedHashMap<>();
        for (Film film : filme) {
            if (!film.erkannt()) {
                aktionen.add(new Aktion(Art.UNERKANNT, film, List.of(), "Titel und Jahr nicht erkennbar"));
            } else {
                nachSchluessel.computeIfAbsent(film.info().fassungsSchluessel(), k -> new ArrayList<>()).add(film);
            }
        }
        List<Film> kandidaten = new ArrayList<>();
        for (List<Film> gruppe : nachSchluessel.values()) {
            List<Film> sortiert = gruppe.stream().sorted(BESSER_ZUERST).toList();
            Film bester = sortiert.get(0);
            kandidaten.add(bester);
            for (Film andere : sortiert.subList(1, sortiert.size())) {
                if (gleicheDatei(andere, List.of(bester)) != null) {
                    aktionen.add(quarantaene(andere, "identische Datei wie " + bester.video()));
                } else if (schlechter(andere.info().aufloesung(), bester.info().aufloesung())) {
                    aktionen.add(quarantaene(andere, "gleicher Film wie " + bester.video()
                            + " (schlechtere Fassung: " + andere.info().aufloesung() + "p statt "
                            + bester.info().aufloesung() + "p)"));
                } else {
                    aktionen.add(new Aktion(Art.DOUBLETTE, andere, List.of(),
                            "gleicher Film wie " + bester.video() + " (diese Fassung wird einsortiert)"));
                }
            }
        }

        // 2. Doubletten innerhalb des Imports: identische Datei unter anderem Namen.
        List<Film> eindeutig = new ArrayList<>();
        for (Film film : kandidaten) {
            Film gleich = gleicheDatei(film, eindeutig);
            if (gleich != null) {
                aktionen.add(quarantaene(film, "identische Datei wie " + gleich.video()));
            } else {
                eindeutig.add(film);
            }
        }

        // 3. Abgleich mit der bestehenden Sammlung und Zielpfade festlegen. Die bessere Fassung zuerst,
        //    damit sie einsortiert wird und weitere Fassungen desselben Films nachgefragt werden.
        eindeutig.sort(BESSER_ZUERST);
        for (Film film : eindeutig) {
            Aktion aktion = gegenSammlung(film);
            if (aktion.art() == Art.EINSORTIEREN) {
                eingeplant.add(new Katalog.Eintrag(film.info(),
                        ziel.relativize(aktion.verschiebungen().get(0).nach()).toString(), film.groesse()));
            }
            aktionen.add(aktion);
        }
        return aktionen;
    }

    private Aktion gegenSammlung(Film film) throws IOException {
        List<Katalog.Eintrag> fassungen = new ArrayList<>(katalog.alle(film.info().schluessel()));
        eingeplant.stream().filter(e -> e.info().schluessel().equals(film.info().schluessel())).forEach(fassungen::add);
        Katalog.Eintrag vorhanden = fassungen.stream()
                .filter(e -> e.info().fassungsSchluessel().equals(film.info().fassungsSchluessel()))
                .findFirst().orElse(null);
        if (vorhanden != null) {
            if (identisch(film, vorhanden)) {
                return quarantaene(film, "identische Datei schon in der Sammlung: " + vorhanden.video());
            }
            String hinweis = "schon in der Sammlung: " + vorhanden.video() + vergleich(film, vorhanden);
            // Eine bessere oder gleichwertige neue Fassung bleibt liegen, bis über das Ersetzen entschieden ist.
            return schlechter(film.info().aufloesung(), vorhanden.info().aufloesung())
                    ? quarantaene(film, hinweis)
                    : new Aktion(Art.DOUBLETTE, film, List.of(), hinweis);
        }
        for (Katalog.Eintrag eintrag : katalog.eintraege()) {
            if (identisch(film, eintrag)) {
                return quarantaene(film, "identische Datei schon in der Sammlung: " + eintrag.video());
            }
        }
        if (!fassungen.isEmpty()) {
            Katalog.Eintrag andere = fassungen.get(0);
            String hinweis = "andere Fassung (" + bezeichnung(film.info()) + ") von " + andere.video()
                    + " (" + bezeichnung(andere.info()) + ")";
            return switch (entscheidungen.getOrDefault(film.video(), Entscheidung.OFFEN)) {
                case OFFEN -> new Aktion(Art.FASSUNG, film, List.of(), hinweis + ", bitte entscheiden");
                case QUARANTAENE -> quarantaene(film, hinweis);
                case BEIDE_BEHALTEN -> einsortieren(film, true);
            };
        }
        return einsortieren(film, false);
    }

    private static String bezeichnung(FilmInfo info) {
        return info.fassung().isEmpty() ? "Kinofassung" : info.fassung();
    }

    /**
     * Plant das Verschieben in die Sammlung. Ein Film bekommt einen eigenen Ordner; Serienfolgen und
     * weitere Fassungen eines Films teilen sich einen Ordner ({@code teiltOrdner}).
     */
    private Aktion einsortieren(Film film, boolean teiltOrdner) {
        String name = film.info().zielName();
        Path ordner = film.info().zielOrdner(ziel);
        boolean gemeinsam = teiltOrdner || film.info().istFolge();
        if (!gemeinsam && Files.exists(ordner)) {
            return new Aktion(Art.KONFLIKT, film, List.of(), "Zielordner existiert schon: " + ordner);
        }
        List<Verschiebung> verschiebungen = new ArrayList<>();
        String endung = Dateinamen.endung(film.video().getFileName().toString());
        verschiebungen.add(new Verschiebung(film.video(), ordner.resolve(name + "." + endung)));
        for (Map.Entry<Path, String> begleit : begleitNamen(film, name, gemeinsam).entrySet()) {
            verschiebungen.add(new Verschiebung(begleit.getKey(), ordner.resolve(begleit.getValue())));
        }
        for (Verschiebung v : verschiebungen) {
            if (Files.exists(v.nach())) {
                return new Aktion(Art.KONFLIKT, film, List.of(), "Zieldatei existiert schon: " + v.nach());
            }
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

    /**
     * Doublette, die in die Quarantäne kommt. Video und Begleitdateien behalten dort ihren alten
     * Pfad ohne Laufwerk, z.B. {@code F:\Filme\X\a.mkv} nach {@code Ziel\_Doubletten\2026-10-18\Filme\X\a.mkv},
     * damit immer erkennbar bleibt, woher eine Datei kam. Ist dieser Platz schon belegt, bleibt sie liegen.
     */
    private Aktion quarantaene(Film film, String hinweis) {
        List<Verschiebung> verschiebungen = new ArrayList<>();
        verschiebungen.add(new Verschiebung(film.video(), inQuarantaene(film.video())));
        for (Path begleit : film.begleitdateien()) {
            verschiebungen.add(new Verschiebung(begleit, inQuarantaene(begleit)));
        }
        for (Verschiebung v : verschiebungen) {
            if (Files.exists(v.nach())) {
                return new Aktion(Art.DOUBLETTE, film, List.of(),
                        hinweis + "; bleibt liegen, in der Quarantäne liegt schon " + v.nach());
            }
        }
        return new Aktion(Art.DOUBLETTE, film, verschiebungen, hinweis);
    }

    private Path inQuarantaene(Path datei) {
        Path absolut = datei.toAbsolutePath().normalize();
        Path ohneLaufwerk = absolut.getRoot() == null ? absolut : absolut.getRoot().relativize(absolut);
        return quarantaene.resolve(ohneLaufwerk);
    }

    /** Nur bei bekannter Auflösung auf beiden Seiten ist eine Fassung sicher schlechter. */
    private static boolean schlechter(int aufloesung, int vergleich) {
        return aufloesung > 0 && vergleich > 0 && aufloesung < vergleich;
    }

    private boolean identisch(Film film, Katalog.Eintrag eintrag) throws IOException {
        Path video = ziel.resolve(eintrag.video());
        return eintrag.groesse() == film.groesse() && Files.isRegularFile(video)
                && pruefsumme(video).equals(pruefsumme(film.video()));
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
     * eindeutig benennen lässt, behält seinen Namen. Teilen sich mehrere Videos einen Ordner (Folgen einer
     * Staffel, Fassungen eines Films), tragen alle Begleitdateien den Namen des Videos, ein einzelnes Bild
     * also {@code Titel - S01E02.jpg} und andere {@code Titel - S01E02 - <alter Name>}.
     */
    static Map<Path, String> begleitNamen(Film film, String name, boolean teiltOrdner) {
        Map<Path, String> ergebnis = new LinkedHashMap<>();
        Set<String> vergeben = new HashSet<>();
        vergeben.add((name + "." + Dateinamen.endung(film.video().getFileName().toString())).toLowerCase(Locale.ROOT));
        long bilder = film.begleitdateien().stream()
                .filter(p -> Dateinamen.BILD.contains(Dateinamen.endung(p.getFileName().toString())))
                .count();
        for (Path datei : film.begleitdateien()) {
            String original = teiltOrdner ? name + " - " + datei.getFileName() : datei.getFileName().toString();
            String endung = Dateinamen.endung(original);
            String wunsch;
            if (Dateinamen.BILD.contains(endung)) {
                wunsch = bilder != 1 ? original : teiltOrdner ? name + "." + endung : "poster." + endung;
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
