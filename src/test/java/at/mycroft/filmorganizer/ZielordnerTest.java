package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ein Zielordner unterhalb der Quelle wird nie durchsucht, egal wie sein Pfad geschrieben ist. */
class ZielordnerTest {

    @TempDir
    Path quelle;

    Path ziel;

    @BeforeEach
    void anlegen() throws IOException {
        ziel = quelle.resolve("Sammlung");
        datei("Neu/Der.Pate.1972.German.1080p/pate.mkv");
        datei("Sammlung/Das Dschungelbuch (2016)/Das Dschungelbuch (2016).mkv");
        datei("Sammlung/Serien/Dark/Staffel 02/Dark - S02E05.mkv");
        datei("Sammlung/_Doubletten/2026-10-18/Kopie/Matrix.1999.mkv");
        datei("Sammlung/.filmorganizer/Irgendwas.2001.mkv");
    }

    @Test
    void zielUnterDerQuelleWirdUebersprungen() throws IOException {
        assertEquals(List.of("pate.mkv"), gefunden(ziel));
    }

    @Test
    void andereSchreibweisenDesZielsWerdenErkannt() throws IOException {
        assertEquals(List.of("pate.mkv"), gefunden(quelle.resolve("Neu/../Sammlung")));
        assertEquals(List.of("pate.mkv"), gefunden(quelle.resolve("Sammlung/.")));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            // Windows unterscheidet nicht zwischen Groß- und Kleinschreibung.
            assertEquals(List.of("pate.mkv"), gefunden(quelle.resolve("SAMMLUNG")));
        }
    }

    @Test
    void zielUeberVerknuepfungWirdErkannt() throws IOException, InterruptedException {
        Path verknuepfung = quelle.getParent().resolve(quelle.getFileName() + "-ziel");
        assumeTrue(verknuepfen(verknuepfung, ziel), "Verknüpfung lässt sich hier nicht anlegen");
        try {
            assertEquals(List.of("pate.mkv"), gefunden(verknuepfung));
        } finally {
            Files.deleteIfExists(verknuepfung);
        }
    }

    @Test
    void nochNichtVorhandenesZielStoertNicht() throws IOException {
        assertEquals(5, Scanner.scannen(quelle, quelle.resolve("Neue Sammlung")).size());
    }

    private List<String> gefunden(Path zielPfad) throws IOException {
        return Scanner.scannen(quelle, zielPfad).stream().map(f -> f.video().getFileName().toString()).toList();
    }

    /** Symlink, unter Windows ersatzweise eine Junction (braucht keine Administratorrechte). */
    private static boolean verknuepfen(Path link, Path ziel) throws InterruptedException {
        try {
            Files.createSymbolicLink(link, ziel);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                return false;
            }
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), ziel.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.isDirectory(link);
        } catch (IOException e) {
            return false;
        }
    }

    private void datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
    }
}
