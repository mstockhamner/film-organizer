package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import at.mycroft.filmorganizer.Aktion.NeueDatei;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
    /** Von Hand gesetzte Filmreihen je Videodatei; leer heißt "keine Reihe". */
    private final Map<Path, String> reihenVorgaben;
    /** Erkannte Filmreihe je normalisiertem Titel. */
    private Map<String, String> erkannteReihen = Map.of();
    /** In diesem Lauf zum Einsortieren vorgesehene Filme, damit weitere Fassungen sie als vorhanden sehen. */
    private final List<Katalog.Eintrag> eingeplant = new ArrayList<>();

    /** Gemerkte, von Hand festgelegte Filmreihen aus früheren Läufen. */
    private final Reihenzuordnung gemerkt;

    private Planer(Path ziel, Katalog katalog, Map<Path, Entscheidung> entscheidungen, Map<Path, String> reihen,
            Reihenzuordnung gemerkt) {
        this.ziel = ziel;
        this.quarantaene = ziel.resolve(QUARANTAENE).resolve(LocalDate.now().toString());
        this.katalog = katalog;
        this.entscheidungen = entscheidungen;
        this.reihenVorgaben = reihen;
        this.gemerkt = gemerkt;
    }

    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel) throws IOException {
        return planen(filme, katalog, ziel, Map.of());
    }

    /** Wie {@link #planen(List, Katalog, Path)}, mit den Entscheidungen zu anderen Fassungen je Videodatei. */
    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel,
            Map<Path, Entscheidung> entscheidungen) throws IOException {
        return planen(filme, katalog, ziel, entscheidungen, Map.of());
    }

    /** Wie oben, zusätzlich mit von Hand gesetzten Filmreihen je Videodatei (leer: keine Reihe). */
    public static List<Aktion> planen(List<Film> filme, Katalog katalog, Path ziel,
            Map<Path, Entscheidung> entscheidungen, Map<Path, String> reihen) throws IOException {
        return new Planer(ziel, katalog, entscheidungen, reihen, Reihenzuordnung.laden(ziel)).planen(filme);
    }

    private List<Aktion> planen(List<Film> filme) throws IOException {
        List<Aktion> aktionen = new ArrayList<>();
        erkannteReihen = reihenErkennen(filme);

        // 1. Doubletten innerhalb des Imports: gleicher Titel, gleiches Jahr und gleiche Fassung.
        Map<String, List<Film>> nachSchluessel = new LinkedHashMap<>();
        for (Film film : filme) {
            if (!film.erkannt()) {
                aktionen.add(new Aktion(Art.UNERKANNT, film, List.of(),
                        film.mitTitel() ? "Jahr nicht erkennbar" : "Titel und Jahr nicht erkennbar"));
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
                    aktionen.add(doublette(andere, "identische Datei wie " + bester.video(),
                            Entscheidung.QUARANTAENE, null));
                } else {
                    String hinweis = "gleicher Film wie " + bester.video() + " (diese Fassung wird einsortiert) – diese: "
                            + kurz(andere.info().aufloesung(), andere.groesse())
                            + ", einsortiert: " + kurz(bester.info().aufloesung(), bester.groesse());
                    boolean schlechter = vergleichen(andere.info().aufloesung(), andere.groesse(),
                            bester.info().aufloesung(), bester.groesse()) < 0;
                    aktionen.add(doublette(andere, hinweis,
                            schlechter ? Entscheidung.QUARANTAENE : Entscheidung.LIEGEN_LASSEN, null));
                }
            }
        }

        // 2. Doubletten innerhalb des Imports: identische Datei unter anderem Namen.
        List<Film> eindeutig = new ArrayList<>();
        for (Film film : kandidaten) {
            Film gleich = gleicheDatei(film, eindeutig);
            if (gleich != null) {
                aktionen.add(doublette(film, "identische Datei wie " + gleich.video(), Entscheidung.QUARANTAENE, null));
            } else {
                eindeutig.add(film);
            }
        }

        // 3. Abgleich mit der bestehenden Sammlung und Zielpfade festlegen. Die bessere Fassung zuerst,
        //    damit sie einsortiert wird und weitere Fassungen desselben Films nachgefragt werden.
        eindeutig.sort(BESSER_ZUERST);
        for (Film film : eindeutig) {
            Aktion aktion = gegenSammlung(mitReihe(film, reiheFuer(film.video(), film.info())));
            if (aktion.art() == Art.EINSORTIEREN) {
                eingeplant.add(new Katalog.Eintrag(aktion.film().info(),
                        ziel.relativize(aktion.verschiebungen().get(0).nach()).toString(), film.groesse()));
            }
            aktionen.add(aktion);
        }

        // 4. Filme aus der Sammlung, die jetzt zu einer Filmreihe gehören, in deren Ordner umsortieren.
        aktionen.addAll(umsortieren(aktionen));
        return aktionen;
    }

    /**
     * Erkennt Filmreihen über alle Filme des Imports und der Sammlung. Hat eine Reihe in der Sammlung
     * schon einen Ordner (auch einen von Hand benannten), behält sie dessen Namen.
     */
    private Map<String, String> reihenErkennen(List<Film> filme) {
        List<String> titel = new ArrayList<>();
        filme.stream().filter(f -> f.erkannt() && !f.info().istFolge()).forEach(f -> titel.add(f.info().titel()));
        List<Katalog.Eintrag> sammlung = katalog.eintraege().stream().filter(e -> !e.info().istFolge()).toList();
        sammlung.forEach(e -> titel.add(e.info().titel()));
        Map<String, String> reihen = new HashMap<>(Filmreihen.erkennen(titel));
        Map<String, String> vorhandeneNamen = new HashMap<>();
        for (Katalog.Eintrag e : sammlung) {
            String erkannt = reihen.get(FilmInfo.normalisieren(e.info().titel()));
            if (!e.info().reihe().isEmpty()) {
                if (erkannt != null) {
                    vorhandeneNamen.putIfAbsent(FilmInfo.normalisieren(erkannt), e.info().reihe());
                }
                reihen.put(FilmInfo.normalisieren(e.info().titel()), e.info().reihe());
            }
        }
        reihen.replaceAll((t, r) -> vorhandeneNamen.getOrDefault(FilmInfo.normalisieren(r), r));
        return reihen;
    }

    /**
     * Von Hand gesetzte Reihe (in der Vorschau oder gemerkt), sonst die aus dem Namen
     * ({@code Star Trek - 04 - …}), sonst die erkannte; bei Serienfolgen keine.
     */
    private String reiheFuer(Path video, FilmInfo info) {
        if (info.istFolge()) {
            return "";
        }
        String vonHand = vonHand(video, info);
        if (vonHand != null) {
            return vonHand;
        }
        return !info.reihe().isEmpty() ? info.reihe()
                : erkannteReihen.getOrDefault(FilmInfo.normalisieren(info.titel()), "");
    }

    /** In der Vorschau gesetzte Reihe, sonst die gemerkte; {@code null}, wenn nichts von Hand festgelegt ist. */
    private String vonHand(Path video, FilmInfo info) {
        String vorgabe = reihenVorgaben.get(video);
        return vorgabe != null ? vorgabe.trim() : gemerkt.reihe(info);
    }

    private static Film mitReihe(Film film, String reihe) {
        return new Film(film.video(), film.groesse(), film.info().mitReihe(reihe), film.begleitdateien());
    }

    /**
     * Filme der Sammlung, die in eine andere Filmreihe gehören, ziehen mit ihrem ganzen Ordner um: direkt
     * im Ziel liegende in den Ordner einer erkannten Reihe, und jeder Film dorthin, wo er von Hand
     * hingehört (in eine andere Reihe oder ohne Reihe zurück direkt ins Ziel). Ein Film, der schon in
     * einer Reihe liegt, bleibt dort, solange nichts anderes von Hand festgelegt ist. Ordner, in die in
     * diesem Lauf etwas einsortiert wird, bleiben bis zum nächsten Lauf, wo sie sind.
     */
    private List<Aktion> umsortieren(List<Aktion> geplant) throws IOException {
        Set<Path> belegt = new HashSet<>();
        for (Aktion a : geplant) {
            for (Verschiebung v : a.verschiebungen()) {
                belegt.add(v.nach().getParent());
                belegt.add(v.von().getParent());
            }
        }
        List<Aktion> aktionen = new ArrayList<>();
        for (Katalog.Filmordner f : katalog.filmordner()) {
            Katalog.Eintrag e = f.eintrag();
            String aktuell = e.info().reihe();
            Path ordner = f.ordner();
            if (belegt.contains(ordner)) {
                continue;
            }
            Path anzeige = f.video();
            String vonHand = vonHand(anzeige, e.info());
            String reihe = vonHand != null ? vonHand
                    : aktuell.isEmpty() ? erkannteReihen.getOrDefault(FilmInfo.normalisieren(e.info().titel()), "") : aktuell;
            long groesse = Files.isRegularFile(anzeige) ? Files.size(anzeige) : 0;
            Film film = new Film(anzeige, groesse, e.info().mitReihe(reihe), List.of());
            if (Dateinamen.bereinigen(reihe).equalsIgnoreCase(aktuell)) {
                // Bleibt, wo er ist; in der Vorschau sichtbar, wenn dort gerade etwas geändert wurde.
                if (reihenVorgaben.containsKey(anzeige)) {
                    aktionen.add(new Aktion(Art.AUSGELASSEN, new Film(anzeige, film.groesse(), e.info(), List.of()), List.of(),
                            aktuell.isEmpty() ? "bleibt ohne Filmreihe an seinem Platz" : "bleibt in der Filmreihe " + aktuell));
                }
                continue;
            }
            Path neu = reihe.isEmpty() ? ziel.resolve(ordner.getFileName())
                    : ziel.resolve(Dateinamen.bereinigen(reihe)).resolve(ordner.getFileName());
            if (Files.exists(neu)) {
                aktionen.add(new Aktion(Art.KONFLIKT, film, List.of(), "Zielordner existiert schon: " + neu));
                continue;
            }
            List<Verschiebung> verschiebungen = new ArrayList<>();
            try (Stream<Path> dateien = Files.walk(ordner)) {
                for (Path datei : dateien.filter(Files::isRegularFile).sorted().toList()) {
                    verschiebungen.add(new Verschiebung(datei, neu.resolve(ordner.relativize(datei))));
                }
            }
            String hinweis = reihe.isEmpty() ? "kommt aus der Filmreihe " + aktuell + " direkt ins Ziel"
                    : aktuell.isEmpty() ? "gehört zur Filmreihe " + reihe
                    : "wechselt von der Filmreihe " + aktuell + " nach " + reihe;
            if (!verschiebungen.isEmpty()) {
                aktionen.add(new Aktion(Art.UMSORTIEREN, film, verschiebungen, hinweis));
            }
        }
        return aktionen;
    }


    private Aktion gegenSammlung(Film neuerFilm) throws IOException {
        List<Katalog.Eintrag> fassungen = new ArrayList<>(katalog.alle(neuerFilm.info().schluessel()));
        eingeplant.stream().filter(e -> e.info().schluessel().equals(neuerFilm.info().schluessel()))
                .forEach(fassungen::add);
        // Weitere Fassungen und Ersatz kommen in den Ordner, in dem der Film schon liegt.
        Film film = fassungen.isEmpty() || neuerFilm.info().istFolge() ? neuerFilm
                : mitReihe(neuerFilm, fassungen.get(0).info().reihe());
        Katalog.Eintrag vorhanden = fassungen.stream()
                .filter(e -> e.info().fassungsSchluessel().equals(film.info().fassungsSchluessel()))
                .findFirst().orElse(null);
        if (vorhanden != null) {
            if (identisch(film, vorhanden)) {
                return doublette(film, "identische Datei schon in der Sammlung: " + vorhanden.video(),
                        Entscheidung.QUARANTAENE, null);
            }
            // Ersetzen geht nur, wenn die vorhandene Videodatei eindeutig feststeht.
            Path alt = katalog.alle(film.info().schluessel()).contains(vorhanden) ? alteDatei(vorhanden) : null;
            // Von Hand abgelegte Filme stehen ohne Größe im Katalog, dann zählt die Datei selbst.
            long altGroesse = vorhanden.groesse() > 0 ? vorhanden.groesse() : alt != null ? Files.size(alt) : 0;
            int neuAufl = film.info().aufloesung();
            int altAufl = vorhanden.info().aufloesung();
            String hinweis = "schon in der Sammlung: " + vorhanden.video()
                    + urteil(neuAufl, film.groesse(), altAufl, altGroesse)
                    + " – neu: " + kurz(neuAufl, film.groesse()) + ", vorhanden: " + kurz(altAufl, altGroesse);
            int vergleich = vergleichen(neuAufl, film.groesse(), altAufl, altGroesse);
            Entscheidung vorschlag = vergleich < 0 ? Entscheidung.QUARANTAENE
                    : vergleich > 0 && alt != null ? Entscheidung.ERSETZEN
                    : Entscheidung.LIEGEN_LASSEN;
            return doublette(film, hinweis, vorschlag, alt == null ? null : new Ersatz(alt, fassungen.size() > 1));
        }
        for (Katalog.Eintrag eintrag : katalog.eintraege()) {
            if (identisch(film, eintrag)) {
                return doublette(film, "identische Datei schon in der Sammlung: " + eintrag.video(),
                        Entscheidung.QUARANTAENE, null);
            }
        }
        if (!fassungen.isEmpty()) {
            Katalog.Eintrag andere = fassungen.get(0);
            String hinweis = "andere Fassung (" + bezeichnung(film.info()) + ") von " + andere.video()
                    + " (" + bezeichnung(andere.info()) + ")";
            List<Entscheidung> auswahl = List.of(Entscheidung.OFFEN, Entscheidung.BEIDE_BEHALTEN,
                    Entscheidung.QUARANTAENE, Entscheidung.LIEGEN_LASSEN);
            Entscheidung gewaehlt = wahl(film, Entscheidung.OFFEN, auswahl);
            Aktion aktion = switch (gewaehlt) {
                case BEIDE_BEHALTEN -> einsortieren(film, true);
                case QUARANTAENE -> quarantaene(film, hinweis);
                case LIEGEN_LASSEN -> new Aktion(Art.FASSUNG, film, List.of(), hinweis + ", bleibt liegen");
                default -> new Aktion(Art.FASSUNG, film, List.of(), hinweis + ", bitte entscheiden");
            };
            return aktion.mit(gewaehlt, Entscheidung.OFFEN, auswahl);
        }
        return einsortieren(film, false);
    }

    /**
     * Vorhandene Fassung, die durch eine neue ersetzt werden kann.
     *
     * @param video       ihre Videodatei in der Sammlung
     * @param teiltOrdner ob im Ordner noch andere Fassungen liegen (dann tragen Begleitdateien den Videonamen)
     */
    private record Ersatz(Path video, boolean teiltOrdner) {
    }

    /**
     * Doublette mit Vorschlag. Möglich sind Quarantäne und Liegenlassen, Ersetzen nur gegenüber einer
     * vorhandenen Fassung in der Sammlung ({@code ersatz}).
     */
    private Aktion doublette(Film film, String hinweis, Entscheidung vorschlag, Ersatz ersatz) {
        List<Entscheidung> auswahl = ersatz == null
                ? List.of(Entscheidung.QUARANTAENE, Entscheidung.LIEGEN_LASSEN)
                : List.of(Entscheidung.ERSETZEN, Entscheidung.QUARANTAENE, Entscheidung.LIEGEN_LASSEN);
        Entscheidung gewaehlt = wahl(film, vorschlag, auswahl);
        Aktion aktion = switch (gewaehlt) {
            case QUARANTAENE -> quarantaene(film, hinweis);
            case ERSETZEN -> ersetzen(film, ersatz, hinweis);
            default -> new Aktion(Art.DOUBLETTE, film, List.of(), hinweis);
        };
        return aktion.mit(gewaehlt, vorschlag, auswahl);
    }

    /** Die gewählte Entscheidung, wenn sie für diesen Film möglich ist, sonst der Vorschlag. */
    private Entscheidung wahl(Film film, Entscheidung vorschlag, List<Entscheidung> auswahl) {
        Entscheidung gewaehlt = entscheidungen.get(film.video());
        return gewaehlt != null && auswahl.contains(gewaehlt) ? gewaehlt : vorschlag;
    }

    /**
     * Die neue Fassung nimmt den Platz der vorhandenen ein. Zuerst kommen die alte Videodatei und alle
     * Dateien, die von den neuen Namen belegt sind (z.B. {@code poster.jpg}), in die Quarantäne, dann
     * wird die neue Fassung einsortiert. Andere Dateien im Ordner bleiben, wo sie sind.
     */
    private Aktion ersetzen(Film film, Ersatz ersatz, String hinweis) {
        List<Verschiebung> neu = zielVerschiebungen(film, ersatz.teiltOrdner() || film.info().istFolge());
        List<NeueDatei> neueDateien = nfo(film, neu);
        Set<Path> weg = new LinkedHashSet<>();
        weg.add(ersatz.video());
        for (Verschiebung v : neu) {
            if (Files.isRegularFile(v.nach())) {
                weg.add(v.nach());
            }
        }
        // Eine .nfo der alten Fassung wird durch die neue ersetzt.
        neueDateien.stream().map(NeueDatei::pfad).filter(Files::isRegularFile).forEach(weg::add);
        List<Verschiebung> verschiebungen = new ArrayList<>();
        for (Path datei : weg) {
            Path nach = inQuarantaene(datei);
            if (Files.exists(nach)) {
                return new Aktion(Art.DOUBLETTE, film, List.of(),
                        hinweis + "; Ersetzen nicht möglich, in der Quarantäne liegt schon " + nach);
            }
            verschiebungen.add(new Verschiebung(datei, nach));
        }
        verschiebungen.addAll(neu);
        return new Aktion(Art.ERSETZEN, film, verschiebungen, hinweis).mitNeuen(neueDateien);
    }

    private Path alteDatei(Katalog.Eintrag eintrag) throws IOException {
        return katalog.videoDatei(eintrag);
    }

    private static String bezeichnung(FilmInfo info) {
        return info.fassung().isEmpty() ? "Kinofassung" : info.fassung();
    }

    /**
     * Plant das Verschieben in die Sammlung. Ein Film bekommt einen eigenen Ordner; Serienfolgen und
     * weitere Fassungen eines Films teilen sich einen Ordner ({@code teiltOrdner}).
     */
    private Aktion einsortieren(Film film, boolean teiltOrdner) {
        Path ordner = film.info().zielOrdner(ziel);
        boolean gemeinsam = teiltOrdner || film.info().istFolge();
        if (!gemeinsam && Files.exists(ordner)) {
            return new Aktion(Art.KONFLIKT, film, List.of(), "Zielordner existiert schon: " + ordner);
        }
        List<Verschiebung> verschiebungen = zielVerschiebungen(film, gemeinsam);
        for (Verschiebung v : verschiebungen) {
            if (Files.exists(v.nach())) {
                return new Aktion(Art.KONFLIKT, film, List.of(), "Zieldatei existiert schon: " + v.nach());
            }
        }
        List<NeueDatei> neueDateien = nfo(film, verschiebungen).stream()
                .filter(n -> !Files.exists(n.pfad())).toList();
        return new Aktion(Art.EINSORTIEREN, film, verschiebungen, "").mitNeuen(neueDateien);
    }

    /**
     * Eine .nfo aus den Receiver-Daten, wenn der Film eine Sat-Aufnahme mit .meta oder .eit ist und keine
     * mitgebrachte .nfo denselben Namen bekommt.
     */
    private static List<NeueDatei> nfo(Film film, List<Verschiebung> verschiebungen) {
        Optional<NamensErkennung.Aufnahme> aufnahme =
                NamensErkennung.erkennenAufnahme(Dateinamen.ohneEndung(film.video().getFileName().toString()));
        if (aufnahme.isEmpty()) {
            return List.of();
        }
        ReceiverDaten daten = ReceiverDaten.lesen(film.video());
        if (daten.equals(ReceiverDaten.LEER)) {
            return List.of();
        }
        Path video = verschiebungen.stream().filter(v -> v.von().equals(film.video())).findFirst().orElseThrow().nach();
        Path pfad = video.resolveSibling(film.info().zielName() + ".nfo");
        if (verschiebungen.stream().anyMatch(v -> v.nach().equals(pfad))) {
            return List.of();
        }
        return List.of(new NeueDatei(pfad, Nfo.erstellen(film.info(), daten, aufnahme.get().datum())));
    }

    /** Video und Begleitdateien mit ihren neuen Pfaden in der Sammlung, ohne Prüfung auf belegte Plätze. */
    private List<Verschiebung> zielVerschiebungen(Film film, boolean gemeinsam) {
        String name = film.info().zielName();
        Path ordner = film.info().zielOrdner(ziel);
        List<Verschiebung> verschiebungen = new ArrayList<>();
        String endung = Dateinamen.endung(film.video().getFileName().toString());
        verschiebungen.add(new Verschiebung(film.video(), ordner.resolve(name + "." + endung)));
        for (Map.Entry<Path, String> begleit : begleitNamen(film, name, gemeinsam).entrySet()) {
            verschiebungen.add(new Verschiebung(begleit.getKey(), ordner.resolve(begleit.getValue())));
        }
        return verschiebungen;
    }

    /**
     * Welche von zwei Fassungen behalten wird: zuerst zählt die Auflösung, wenn sie bei beiden bekannt
     * ist und sich unterscheidet, sonst die größere Datei.
     *
     * @return negativ, wenn die neue schlechter ist, positiv, wenn sie besser ist, 0 wenn es nicht
     *         eindeutig ist (gleiche Auflösung und gleiche oder unbekannte Größe)
     */
    static int vergleichen(int neuAufloesung, long neuGroesse, int altAufloesung, long altGroesse) {
        if (neuAufloesung > 0 && altAufloesung > 0 && neuAufloesung != altAufloesung) {
            return Integer.compare(neuAufloesung, altAufloesung);
        }
        if (neuGroesse > 0 && altGroesse > 0) {
            return Long.compare(neuGroesse, altGroesse);
        }
        return 0;
    }

    private static String kurz(int aufloesung, long groesse) {
        String text = aufloesung > 0 ? aufloesung + "p" : "Auflösung unbekannt";
        return groesse > 0 ? text + String.format(Locale.GERMANY, ", %.1f GB", groesse / 1e9) : text;
    }

    private static String urteil(int neuAufloesung, long neuGroesse, int altAufloesung, long altGroesse) {
        if (neuAufloesung > 0 && altAufloesung > 0 && neuAufloesung != altAufloesung) {
            return neuAufloesung > altAufloesung
                    ? " (neue Fassung ist besser: " + neuAufloesung + "p statt " + altAufloesung + "p)"
                    : " (vorhandene Fassung ist besser: " + altAufloesung + "p statt " + neuAufloesung + "p)";
        }
        int vergleich = vergleichen(neuAufloesung, neuGroesse, altAufloesung, altGroesse);
        return vergleich > 0 ? " (neue Datei ist größer)" : vergleich < 0 ? " (vorhandene Datei ist größer)" : "";
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
        return quarantaenePfad(quarantaene, datei);
    }

    /**
     * Platz einer Datei in der Quarantäne einer Sammlung: {@code Ziel/_Doubletten/<heute>/<alter Pfad ohne
     * Laufwerk>}.
     */
    static Path inQuarantaene(Path ziel, Path datei) {
        return quarantaenePfad(ziel.resolve(QUARANTAENE).resolve(LocalDate.now().toString()), datei);
    }

    private static Path quarantaenePfad(Path quarantaeneHeute, Path datei) {
        Path absolut = datei.toAbsolutePath().normalize();
        Path ohneLaufwerk = absolut.getRoot() == null ? absolut : absolut.getRoot().relativize(absolut);
        return quarantaeneHeute.resolve(ohneLaufwerk);
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
            String dateiname = datei.getFileName().toString();
            String videoName = film.video().getFileName().toString();
            if (Dateinamen.istReceiverDatei(dateiname, videoName)) {
                // "Aufnahme.ts.meta" wird "Titel (Jahr).ts.meta", "Aufnahme.eit" wird "Titel (Jahr).eit".
                wunsch = name + dateiname.substring(Dateinamen.ohneEndung(videoName).length());
            } else if (Dateinamen.BILD.contains(endung)) {
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
