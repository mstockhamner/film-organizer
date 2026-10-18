package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.NeueDatei;
import at.mycroft.filmorganizer.Aktion.Verschiebung;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Führt einen Plan aus und macht ihn auf Wunsch wieder rückgängig.
 *
 * <p>Jede Verschiebung wird sofort in {@code <Ziel>/.filmorganizer/protokolle/} festgehalten,
 * damit auch ein abgebrochener Lauf rückgängig gemacht werden kann. Es wird nie überschrieben
 * und nie gelöscht, außer der Quelldatei nach einer geprüften Kopie auf ein anderes Laufwerk.
 */
public final class Ausfuehrung {

    private static final String PROTOKOLLE = "protokolle";
    static final String BERICHTE = "berichte";
    /** Kennung im Protokoll für eine vom Programm neu angelegte Datei (z.B. .nfo). */
    private static final String NEU = "NEU:";
    private static final String ERLEDIGT = ".rueckgaengig";
    // Mit Millisekunden, damit zwei Läufe in derselben Sekunde verschiedene Protokolle bekommen.
    private static final DateTimeFormatter ZEITSTEMPEL = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final Logger LOG = Log.fuer(Ausfuehrung.class);

    private Ausfuehrung() {
    }

    /** Fehlender Platz auf dem Ziellaufwerk in Byte, 0 wenn genug Platz ist. */
    public static long fehlenderPlatz(List<Aktion> plan, Path ziel) throws IOException {
        FileStore zielLaufwerk = laufwerk(ziel);
        long benoetigt = 0;
        for (Aktion aktion : plan) {
            for (Verschiebung v : aktion.verschiebungen()) {
                if (!laufwerk(v.von()).equals(zielLaufwerk)) {
                    benoetigt += Files.size(v.von());
                }
            }
        }
        return Math.max(0, benoetigt - zielLaufwerk.getUsableSpace());
    }

    /**
     * Führt alle {@link Art#EINSORTIEREN}-, {@link Art#ERSETZEN}- und {@link Art#UMSORTIEREN}-Aktionen aus,
     * verschiebt Doubletten in die Quarantäne und gibt die Anzahl einsortierter Filme zurück (ersetzte
     * mitgezählt, umsortierte nicht). Gibt es
     * Doubletten oder andere Fassungen, wird zusätzlich ein Doublettenbericht geschrieben.
     *
     * <p>Ordner unter {@code quelle}, die durch das Verschieben leer geworden sind, werden danach entfernt,
     * die Quelle selbst nicht. Ordner, in denen noch etwas liegt (auch nur {@code Thumbs.db}), bleiben.
     */
    public static int ausfuehren(List<Aktion> plan, Path quelle, Path ziel, PrintStream ausgabe) throws IOException {
        Set<Path> herkunft = new LinkedHashSet<>();
        for (Aktion aktion : plan) {
            if (aktion.art() == Art.EINSORTIEREN || aktion.art() == Art.ERSETZEN || aktion.inQuarantaene()) {
                aktion.verschiebungen().forEach(v -> herkunft.add(v.von().toAbsolutePath().normalize().getParent()));
            }
        }
        int filme = ausfuehren(plan, ziel, ausgabe);
        leereQuellordnerEntfernen(herkunft, quelle, ziel, ausgabe);
        return filme;
    }

    /** Wie {@link #ausfuehren(List, Path, Path, PrintStream)}, ohne leere Quellordner zu entfernen. */
    public static int ausfuehren(List<Aktion> plan, Path ziel, PrintStream ausgabe) throws IOException {
        Files.createDirectories(ziel);
        Katalog katalog = Katalog.laden(ziel);
        Path protokolle = ziel.resolve(Katalog.VERWALTUNG).resolve(PROTOKOLLE);
        Files.createDirectories(protokolle);
        String zeitstempel = LocalDateTime.now().format(ZEITSTEMPEL);
        Path protokoll = protokolle.resolve(zeitstempel + ".tsv");
        berichtSchreiben(plan, ziel.resolve(Katalog.VERWALTUNG).resolve(BERICHTE).resolve(zeitstempel + "-doubletten.txt"));
        int filme = 0;
        try (BufferedWriter schreiber = Files.newBufferedWriter(protokoll, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Aktion aktion : plan) {
                if (aktion.art() != Art.EINSORTIEREN && aktion.art() != Art.ERSETZEN && aktion.art() != Art.UMSORTIEREN
                        && !aktion.inQuarantaene()) {
                    continue;
                }
                for (Verschiebung v : aktion.verschiebungen()) {
                    verschieben(v.von(), v.nach());
                    LOG.info(() -> "Verschoben: " + v.von() + " -> " + v.nach());
                    schreiber.write(v.von() + "\t" + v.nach());
                    schreiber.newLine();
                    schreiber.flush();
                }
                for (NeueDatei neu : aktion.neueDateien()) {
                    if (Files.exists(neu.pfad())) {
                        LOG.warning(() -> "Nicht angelegt, existiert schon: " + neu.pfad());
                        continue;
                    }
                    Files.createDirectories(neu.pfad().getParent());
                    Files.writeString(neu.pfad(), neu.inhalt(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    LOG.info(() -> "Angelegt: " + neu.pfad());
                    schreiber.write(NEU + "\t" + neu.pfad());
                    schreiber.newLine();
                    schreiber.flush();
                }
                if (aktion.art() == Art.UMSORTIEREN) {
                    // Filmordner vorher und nachher: "Titel (Jahr)" direkt im Ziel oder "Reihe/Titel (Jahr)".
                    Path von = ziel.relativize(aktion.verschiebungen().get(0).von());
                    Path nach = ziel.relativize(aktion.verschiebungen().get(0).nach());
                    Path alt = von.subpath(0, Katalog.reiheAusPfad(von.toString()).isEmpty() ? 1 : 2);
                    Path neu = nach.subpath(0, aktion.film().info().reihe().isEmpty() ? 1 : 2);
                    leereOrdnerEntfernen(ziel.resolve(alt));
                    leerenOrdnerEntfernen(ziel.resolve(alt).getParent(), ziel);
                    katalog.umziehen(alt, neu, aktion.film().info().reihe());
                    katalog.speichern();
                    ausgabe.println("umsortiert: " + alt + " -> " + neu);
                    continue;
                }
                Path video = aktion.verschiebungen().stream().filter(v -> v.von().equals(aktion.film().video()))
                        .findFirst().orElseThrow().nach();
                if (aktion.inQuarantaene()) {
                    ausgabe.println("in Quarantäne: " + ziel.relativize(video));
                    continue;
                }
                // Beim Ersetzen fällt der alte Eintrag weg, weil Film und Fassung gleich sind.
                katalog.hinzufuegen(new Katalog.Eintrag(aktion.film().info(), ziel.relativize(video).toString(),
                        aktion.film().groesse()));
                katalog.speichern();
                filme++;
                ausgabe.println((aktion.art() == Art.ERSETZEN ? "ersetzt: " : "einsortiert: ") + ziel.relativize(video));
            }
        }
        return filme;
    }

    /** Listet Doubletten und andere Fassungen mit Entscheidung, Hinweis und Ziel auf. */
    static void berichtSchreiben(List<Aktion> plan, Path datei) throws IOException {
        List<Aktion> betroffen = plan.stream().filter(a -> !a.auswahl().isEmpty()).toList();
        if (betroffen.isEmpty()) {
            return;
        }
        List<String> zeilen = new ArrayList<>();
        zeilen.add("Doublettenbericht vom " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
        for (Aktion a : betroffen) {
            zeilen.add("");
            zeilen.add(a.entscheidung().text() + ": " + a.film().video());
            zeilen.add("    " + a.hinweis());
            for (Verschiebung v : a.verschiebungen()) {
                zeilen.add("    " + v.von() + " -> " + v.nach());
            }
        }
        Files.createDirectories(datei.getParent());
        Files.write(datei, zeilen, StandardCharsets.UTF_8);
    }

    /**
     * Macht den letzten noch nicht rückgängig gemachten Lauf rückgängig.
     *
     * @return Anzahl zurückverschobener Dateien, -1 wenn es nichts rückgängig zu machen gibt
     */
    public static int rueckgaengig(Path ziel, PrintStream ausgabe) throws IOException {
        Path protokolle = ziel.resolve(Katalog.VERWALTUNG).resolve(PROTOKOLLE);
        if (!Files.isDirectory(protokolle)) {
            return -1;
        }
        Path letztes;
        try (Stream<Path> dateien = Files.list(protokolle)) {
            letztes = dateien.filter(p -> p.getFileName().toString().endsWith(".tsv"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
        if (letztes == null) {
            return -1;
        }
        List<String> zeilen = new ArrayList<>(Files.readAllLines(letztes, StandardCharsets.UTF_8));
        Collections.reverse(zeilen);
        Katalog katalog = Katalog.laden(ziel);
        int zurueck = 0;
        for (String zeile : zeilen) {
            String[] teile = zeile.split("\t");
            if (teile.length != 2) {
                continue;
            }
            if (teile[0].equals(NEU)) {
                // Vom Programm angelegt (z.B. .nfo): beim Rückgängigmachen wieder entfernen.
                Path angelegt = Path.of(teile[1]);
                if (Files.deleteIfExists(angelegt)) {
                    LOG.info(() -> "Rückgängig: angelegte Datei entfernt: " + angelegt);
                }
                leerenOrdnerEntfernen(angelegt.getParent(), ziel);
                continue;
            }
            Path urspruenglich = Path.of(teile[0]);
            Path jetzt = Path.of(teile[1]);
            if (!Files.exists(jetzt)) {
                LOG.warning(() -> "Rückgängig: Datei fehlt, übersprungen: " + jetzt);
                ausgabe.println("fehlt, übersprungen: " + jetzt);
                continue;
            }
            if (Files.exists(urspruenglich)) {
                LOG.warning(() -> "Rückgängig: ursprünglicher Platz belegt, übersprungen: " + urspruenglich);
                ausgabe.println("ursprünglicher Platz belegt, übersprungen: " + urspruenglich);
                continue;
            }
            verschieben(jetzt, urspruenglich);
            LOG.info(() -> "Zurückverschoben: " + jetzt + " -> " + urspruenglich);
            katalog.entfernen(ziel.relativize(jetzt).toString());
            leerenOrdnerEntfernen(jetzt.getParent(), ziel);
            zurueck++;
        }
        katalog.speichern();
        Files.move(letztes, letztes.resolveSibling(letztes.getFileName() + ERLEDIGT));
        return zurueck;
    }

    /**
     * Verschiebt eine Datei, ohne etwas zu überschreiben. Auf demselben Laufwerk wird nur
     * umbenannt. Auf ein anderes Laufwerk wird kopiert, die Kopie per Prüfsumme geprüft und erst
     * dann das Original entfernt.
     */
    static void verschieben(Path von, Path nach) throws IOException {
        if (Files.exists(nach)) {
            throw new IOException("Ziel existiert schon, nichts überschrieben: " + nach);
        }
        Files.createDirectories(nach.getParent());
        if (laufwerk(von).equals(laufwerk(nach.getParent()))) {
            try {
                Files.move(von, nach, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(von, nach);
            }
            return;
        }
        Path teil = nach.resolveSibling(nach.getFileName() + ".teil");
        Files.copy(von, teil, StandardCopyOption.COPY_ATTRIBUTES);
        if (Files.size(teil) != Files.size(von) || !Pruefsumme.voll(teil).equals(Pruefsumme.voll(von))) {
            Files.delete(teil);
            throw new IOException("Kopie fehlerhaft, Original bleibt erhalten: " + von);
        }
        Files.move(teil, nach);
        Files.delete(von);
    }

    /**
     * Entfernt leere Ordner, aus denen etwas verschoben wurde: zuerst leere Unterordner darin, dann den
     * Ordner selbst und seine Elternordner, solange sie leer sind. Grenze ist die Quelle (bzw. das Ziel,
     * wenn der Ordner darin liegt); Quelle, Ziel und Ordner außerhalb der Quelle bleiben immer stehen.
     */
    static void leereQuellordnerEntfernen(Set<Path> ordner, Path quelle, Path ziel, PrintStream ausgabe)
            throws IOException {
        Path quelleAbs = quelle.toAbsolutePath().normalize();
        Path zielAbs = ziel.toAbsolutePath().normalize();
        for (Path start : ordner) {
            Path grenze = start.startsWith(zielAbs) ? zielAbs : quelleAbs;
            if (!start.startsWith(grenze) || start.equals(grenze)) {
                continue;
            }
            if (Files.isDirectory(start)) {
                leereUnterordnerEntfernen(start, zielAbs, ausgabe);
            }
            for (Path o = start; o != null && o.startsWith(grenze) && !o.equals(grenze) && !o.equals(zielAbs);
                    o = o.getParent()) {
                if (!Files.isDirectory(o) || !leer(o)) {
                    break;
                }
                Files.delete(o);
                Path entfernt = o;
                LOG.info(() -> "Leerer Ordner entfernt: " + entfernt);
                ausgabe.println("leerer Ordner entfernt: " + o);
            }
        }
    }

    private static void leereUnterordnerEntfernen(Path ordner, Path zielAbs, PrintStream ausgabe) throws IOException {
        List<Path> tiefsteZuerst;
        try (Stream<Path> alle = Files.walk(ordner)) {
            tiefsteZuerst = alle.filter(Files::isDirectory).filter(o -> !o.equals(ordner) && !o.startsWith(zielAbs))
                    .sorted(Comparator.reverseOrder()).toList();
        }
        for (Path o : tiefsteZuerst) {
            if (leer(o)) {
                Files.delete(o);
                LOG.info(() -> "Leerer Ordner entfernt: " + o);
                ausgabe.println("leerer Ordner entfernt: " + o);
            }
        }
    }

    private static boolean leer(Path ordner) throws IOException {
        try (Stream<Path> inhalt = Files.list(ordner)) {
            return inhalt.findAny().isEmpty();
        }
    }

    /** Entfernt einen umsortierten Filmordner samt Unterordnern, soweit sie leer sind. */
    private static void leereOrdnerEntfernen(Path ordner) throws IOException {
        if (!Files.isDirectory(ordner)) {
            return;
        }
        List<Path> ordnerZuerstDieTiefsten;
        try (Stream<Path> alle = Files.walk(ordner)) {
            ordnerZuerstDieTiefsten = alle.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path o : ordnerZuerstDieTiefsten) {
            try {
                Files.deleteIfExists(o);
            } catch (DirectoryNotEmptyException e) {
                // Enthält noch etwas, bleibt stehen.
            }
        }
    }

    /** Entfernt leere Ordner bis hinauf zum Ziel, z.B. Staffel-, Serien- und Filmordner. */
    private static void leerenOrdnerEntfernen(Path ordner, Path ziel) throws IOException {
        for (Path o = ordner; o != null && !o.equals(ziel) && o.startsWith(ziel); o = o.getParent()) {
            try {
                Files.deleteIfExists(o);
            } catch (DirectoryNotEmptyException e) {
                return; // Ordner enthält noch etwas, bleibt stehen.
            }
        }
    }

    private static FileStore laufwerk(Path pfad) throws IOException {
        Path vorhanden = pfad.toAbsolutePath();
        while (vorhanden != null && !Files.exists(vorhanden)) {
            vorhanden = vorhanden.getParent();
        }
        if (vorhanden == null) {
            throw new IOException("Laufwerk nicht gefunden: " + pfad);
        }
        return Files.getFileStore(vorhanden);
    }
}
