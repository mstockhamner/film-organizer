package at.mycroft.filmorganizer;

import at.mycroft.filmorganizer.Aktion.Art;
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
import java.util.List;
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
    private static final String ERLEDIGT = ".rueckgaengig";
    private static final DateTimeFormatter ZEITSTEMPEL = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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

    /** Führt alle {@link Art#EINSORTIEREN}-Aktionen aus und gibt die Anzahl verschobener Filme zurück. */
    public static int ausfuehren(List<Aktion> plan, Path ziel, PrintStream ausgabe) throws IOException {
        Files.createDirectories(ziel);
        Katalog katalog = Katalog.laden(ziel);
        Path protokolle = ziel.resolve(Katalog.VERWALTUNG).resolve(PROTOKOLLE);
        Files.createDirectories(protokolle);
        Path protokoll = protokolle.resolve(LocalDateTime.now().format(ZEITSTEMPEL) + ".tsv");
        int filme = 0;
        try (BufferedWriter schreiber = Files.newBufferedWriter(protokoll, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Aktion aktion : plan) {
                if (aktion.art() != Art.EINSORTIEREN) {
                    continue;
                }
                for (Verschiebung v : aktion.verschiebungen()) {
                    verschieben(v.von(), v.nach());
                    LOG.info(() -> "Verschoben: " + v.von() + " -> " + v.nach());
                    schreiber.write(v.von() + "\t" + v.nach());
                    schreiber.newLine();
                    schreiber.flush();
                }
                Path video = aktion.verschiebungen().get(0).nach();
                katalog.hinzufuegen(new Katalog.Eintrag(aktion.film().info(), ziel.relativize(video).toString(),
                        aktion.film().groesse()));
                katalog.speichern();
                filme++;
                ausgabe.println("einsortiert: " + ziel.relativize(video));
            }
        }
        return filme;
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
