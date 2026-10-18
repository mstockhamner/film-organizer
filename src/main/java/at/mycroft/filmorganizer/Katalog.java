package at.mycroft.filmorganizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final String DATEI = "katalog.tsv";
    private static final Pattern ZIELORDNER = Pattern.compile("^(.+) \\((\\d{4})\\)$");
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
                // Ältere Kataloge haben noch keine Spalten für Staffel, Folge und Fassung.
                if (teile.length == 5 || teile.length == 7 || teile.length == 8) {
                    FilmInfo info = new FilmInfo(teile[0], Integer.parseInt(teile[1]), Integer.parseInt(teile[2]),
                            teile.length >= 7 ? Integer.parseInt(teile[5]) : 0,
                            teile.length >= 7 ? Integer.parseInt(teile[6]) : 0,
                            teile.length == 8 ? teile[7] : "");
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
                Matcher m = ZIELORDNER.matcher(o.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                FilmInfo info = new FilmInfo(m.group(1), Integer.parseInt(m.group(2)), 0);
                if (finden(info.schluessel()) == null) {
                    eintraege.add(new Eintrag(info, ziel.relativize(o).toString(), 0));
                }
            }
        }
        folgenErgaenzen();
    }

    /** Folgen nach dem Schema {@code Serien/Titel/Staffel 01/Titel - S01E02.<Endung>}. */
    private void folgenErgaenzen() throws IOException {
        Path serien = ziel.resolve(FilmInfo.SERIEN);
        if (!Files.isDirectory(serien)) {
            return;
        }
        try (Stream<Path> dateien = Files.walk(serien, 3)) {
            for (Path datei : dateien.filter(Files::isRegularFile).sorted().toList()) {
                String name = datei.getFileName().toString();
                Matcher m = ZIELFOLGE.matcher(Dateinamen.ohneEndung(name));
                if (!Dateinamen.istVideo(name) || !m.matches()) {
                    continue;
                }
                FilmInfo info = new FilmInfo(m.group(1), 0, 0, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
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
                        Integer.toString(e.info().staffel()), Integer.toString(e.info().folge()), e.info().fassung()));
            }
        }
        Files.write(verzeichnis.resolve(DATEI), zeilen, StandardCharsets.UTF_8);
    }
}
