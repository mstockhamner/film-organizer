package at.mycroft.filmorganizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Die bereits einsortierte Sammlung im Zielordner.
 *
 * <p>Gespeichert wird lokal in {@code <Ziel>/.filmorganizer/katalog.tsv}. Zusätzlich zählt jeder
 * Ordner nach dem Schema {@code Titel (Jahr)} im Ziel als vorhandener Film, auch wenn er nicht
 * im Katalog steht (z.B. von Hand angelegt), ebenso jede Folge nach dem Schema
 * {@code Serien/Titel/Staffel 01/Titel - S01E02.<Endung>}.
 */
public final class Katalog {

    static final String VERWALTUNG = ".filmorganizer";
    static final String DATEI = "katalog.tsv";
    private static final Pattern ZIELORDNER = Pattern.compile("^(.+) \\((\\d{4})\\)$");
    /** Ordner im Ziel, die weder Film noch Filmreihe sind (in Kleinbuchstaben). */
    private static final Set<String> SONDERORDNER = Set.of(FilmInfo.SERIEN.toLowerCase(Locale.ROOT),
            Planer.QUARANTAENE.toLowerCase(Locale.ROOT), VERWALTUNG, "#recycle", "@eadir");
    /** Ordner eines nummerierten Films in einer Reihe: {@code 04 - Zurück in die Gegenwart (1986)}. */
    private static final Pattern NUMMER_IM_ORDNER = Pattern.compile("^([0-9]{1,2}) - (.+)$");
    private static final Pattern ZIELFOLGE = Pattern.compile("^(.+) - S(\\d{2,})E(\\d{2,})$");

    /**
     * Ein Film in der Sammlung.
     *
     * @param info    Titel, Jahr und Auflösung
     * @param video   Pfad der Videodatei relativ zum Zielordner
     * @param groesse Größe in Byte, 0 wenn unbekannt
     */
    public record Eintrag(FilmInfo info, String video, long groesse) {
    }

    private final Path ziel;
    private final List<Eintrag> eintraege;

    private Katalog(Path ziel, List<Eintrag> eintraege) {
        this.ziel = ziel;
        this.eintraege = eintraege;
    }

    public static Katalog laden(Path ziel) throws IOException {
        List<Eintrag> eintraege = new ArrayList<>();
        Path datei = ziel.resolve(VERWALTUNG).resolve(DATEI);
        if (Files.exists(datei)) {
            for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                String[] teile = zeile.split("\t", -1);
                // Ältere Kataloge haben noch keine Spalten für Staffel, Folge, Fassung und Folgentitel.
                if (teile.length == 5 || teile.length >= 7 && teile.length <= 9) {
                    FilmInfo info = new FilmInfo(teile[0], Integer.parseInt(teile[1]), Integer.parseInt(teile[2]),
                            teile.length >= 7 ? Integer.parseInt(teile[5]) : 0,
                            teile.length >= 7 ? Integer.parseInt(teile[6]) : 0,
                            teile.length >= 8 ? teile[7] : "", reiheAusPfad(teile[3]),
                            teile.length == 9 ? teile[8] : "", nummerAusPfad(teile[3]));
                    eintraege.add(new Eintrag(info, teile[3], Long.parseLong(teile[4])));
                }
            }
        }
        Katalog katalog = new Katalog(ziel, eintraege);
        katalog.ordnerErgaenzen();
        return katalog;
    }

    private void ordnerErgaenzen() throws IOException {
        if (!Files.isDirectory(ziel)) {
            return;
        }
        try (Stream<Path> ordner = Files.list(ziel)) {
            for (Path o : ordner.filter(Files::isDirectory).sorted().toList()) {
                String name = o.getFileName().toString();
                if (ZIELORDNER.matcher(name).matches()) {
                    filmordnerErgaenzen(o, "");
                } else if (!SONDERORDNER.contains(name.toLowerCase(Locale.ROOT))) {
                    // Ordner einer Filmreihe, z.B. "Harry Potter/Harry Potter und … (2001)".
                    try (Stream<Path> filme = Files.list(o)) {
                        for (Path film : filme.filter(Files::isDirectory).sorted().toList()) {
                            filmordnerErgaenzen(film, name);
                        }
                    }
                }
            }
        }
        folgenErgaenzen();
    }

    private void filmordnerErgaenzen(Path ordner, String reihe) {
        Matcher m = ZIELORDNER.matcher(ordner.getFileName().toString());
        if (!m.matches()) {
            return;
        }
        String titel = m.group(1);
        int nummer = 0;
        Matcher mitNummer = NUMMER_IM_ORDNER.matcher(titel);
        if (!reihe.isEmpty() && mitNummer.matches()) {
            nummer = Integer.parseInt(mitNummer.group(1));
            titel = mitNummer.group(2);
        }
        FilmInfo info = new FilmInfo(titel, Integer.parseInt(m.group(2)), 0, 0, 0, "", reihe).mitNummer(nummer);
        if (finden(info.schluessel()) == null) {
            eintraege.add(new Eintrag(info, ziel.relativize(ordner).toString(), 0));
        }
    }

    /** Nummer eines Films in seiner Reihe aus dem Ordnernamen ({@code Star Trek/04 - Titel (Jahr)/…}), sonst 0. */
    static int nummerAusPfad(String video) {
        Path pfad = Path.of(video);
        if (reiheAusPfad(video).isEmpty() || pfad.getNameCount() < 2) {
            return 0;
        }
        Matcher m = NUMMER_IM_ORDNER.matcher(pfad.getName(1).toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Filmreihe aus dem Pfad relativ zum Ziel: {@code Harry Potter/Harry Potter und … (2001)/…} liegt in
     * der Reihe {@code Harry Potter}, {@code Der Pate (1972)/…} in keiner.
     */
    static String reiheAusPfad(String video) {
        Path pfad = Path.of(video);
        // Reihe/Film/Datei; bei Film/Datei (auch ohne Jahr im Ordnernamen) gibt es keine Reihe.
        if (pfad.getNameCount() < 3) {
            return "";
        }
        String erster = pfad.getName(0).toString();
        return ZIELORDNER.matcher(erster).matches() || SONDERORDNER.contains(erster.toLowerCase(Locale.ROOT))
                ? "" : erster;
    }

    /**
     * Ordner eines Films der Sammlung.
     *
     * @param ordner  {@code Ziel/Titel (Jahr)} oder {@code Ziel/Reihe/Titel (Jahr)}
     * @param video   die eindeutige Videodatei darin, sonst der Ordner selbst
     * @param eintrag der Katalogeintrag (bei mehreren Fassungen im Ordner der erste)
     */
    public record Filmordner(Path ordner, Path video, Eintrag eintrag) {
    }

    /** Ein Eintrag pro Filmordner, ohne Serienfolgen, in der Reihenfolge des Katalogs. */
    public List<Filmordner> filmordner() throws IOException {
        List<Filmordner> ergebnis = new ArrayList<>();
        Set<Path> gesehen = new HashSet<>();
        for (Eintrag e : eintraege) {
            if (e.info().istFolge()) {
                continue;
            }
            Path pfad = Path.of(e.video());
            int tiefe = e.info().reihe().isEmpty() ? 1 : 2;
            if (pfad.getNameCount() < tiefe) {
                continue;
            }
            Path ordner = ziel.resolve(pfad.subpath(0, tiefe));
            if (Files.isDirectory(ordner) && gesehen.add(ordner)) {
                Path video = videoDatei(e);
                ergebnis.add(new Filmordner(ordner, video != null ? video : ordner, e));
            }
        }
        return ergebnis;
    }

    /**
     * Videodatei eines Eintrags: die Datei selbst oder, bei einem von Hand angelegten Ordner, die einzige
     * Videodatei darin. {@code null}, wenn sie nicht eindeutig ist.
     */
    public Path videoDatei(Eintrag eintrag) throws IOException {
        Path pfad = ziel.resolve(eintrag.video());
        if (Files.isRegularFile(pfad)) {
            return pfad;
        }
        if (!Files.isDirectory(pfad)) {
            return null;
        }
        try (Stream<Path> inhalt = Files.list(pfad)) {
            List<Path> videos = inhalt.filter(Files::isRegularFile)
                    .filter(p -> Dateinamen.istVideo(p.getFileName().toString())).toList();
            return videos.size() == 1 ? videos.get(0) : null;
        }
    }

    /**
     * Verschiebt im Katalog alle Einträge aus dem Filmordner {@code alt} nach {@code neu} (beide relativ
     * zum Ziel) und trägt die neue Filmreihe ein (leer: keine).
     */
    public void umziehen(Path alt, Path neu, String reihe) {
        for (int i = 0; i < eintraege.size(); i++) {
            Eintrag e = eintraege.get(i);
            Path pfad = Path.of(e.video());
            if (pfad.startsWith(alt)) {
                eintraege.set(i, new Eintrag(e.info().mitReihe(reihe), neu.resolve(alt.relativize(pfad)).toString(),
                        e.groesse()));
            }
        }
    }

    /**
     * Folgen nach dem Schema {@code Serien/Titel/Staffel 01/Titel - S01E02.<Endung>} und Folgen einer
     * Sendereihe nach {@code Serien/Titel/Titel - Folgentitel.<Endung>}.
     */
    private void folgenErgaenzen() throws IOException {
        Path serien = ziel.resolve(FilmInfo.SERIEN);
        if (!Files.isDirectory(serien)) {
            return;
        }
        try (Stream<Path> dateien = Files.walk(serien, 3)) {
            for (Path datei : dateien.filter(Files::isRegularFile).sorted().toList()) {
                String name = datei.getFileName().toString();
                if (!Dateinamen.istVideo(name)) {
                    continue;
                }
                String ohneEndung = Dateinamen.ohneEndung(name);
                Matcher m = ZIELFOLGE.matcher(ohneEndung);
                String reihe = datei.getParent().getFileName().toString();
                FilmInfo info;
                if (m.matches()) {
                    info = new FilmInfo(m.group(1), 0, 0, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                } else if (datei.getParent().getParent().equals(serien) && ohneEndung.startsWith(reihe + " - ")) {
                    info = FilmInfo.sendung(reihe, ohneEndung.substring(reihe.length() + 3));
                } else {
                    continue;
                }
                if (finden(info.schluessel()) == null) {
                    eintraege.add(new Eintrag(info, ziel.relativize(datei).toString(), 0));
                }
            }
        }
    }

    public List<Eintrag> eintraege() {
        return List.copyOf(eintraege);
    }

    /** Eintrag mit diesem Doubletten-Schlüssel oder {@code null}. */
    public Eintrag finden(String schluessel) {
        return eintraege.stream().filter(e -> e.info().schluessel().equals(schluessel)).findFirst().orElse(null);
    }

    /** Alle Fassungen eines Films in der Sammlung. */
    public List<Eintrag> alle(String schluessel) {
        return eintraege.stream().filter(e -> e.info().schluessel().equals(schluessel)).toList();
    }

    public void hinzufuegen(Eintrag eintrag) {
        eintraege.removeIf(e -> e.info().fassungsSchluessel().equals(eintrag.info().fassungsSchluessel()));
        eintraege.add(eintrag);
    }

    public void entfernen(String video) {
        eintraege.removeIf(e -> e.video().equals(video));
    }

    public void speichern() throws IOException {
        Path verzeichnis = ziel.resolve(VERWALTUNG);
        Files.createDirectories(verzeichnis);
        List<String> zeilen = new ArrayList<>();
        for (Eintrag e : eintraege) {
            // Nur Einträge mit Videodatei speichern; reine Ordner werden beim Laden ohnehin erkannt.
            if (e.groesse() > 0) {
                zeilen.add(String.join("\t", e.info().titel(), Integer.toString(e.info().jahr()),
                        Integer.toString(e.info().aufloesung()), e.video(), Long.toString(e.groesse()),
                        Integer.toString(e.info().staffel()), Integer.toString(e.info().folge()), e.info().fassung(),
                        e.info().folgentitel()));
            }
        }
        Files.write(verzeichnis.resolve(DATEI), zeilen, StandardCharsets.UTF_8);
    }
}
