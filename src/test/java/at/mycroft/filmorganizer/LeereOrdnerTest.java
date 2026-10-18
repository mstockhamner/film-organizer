package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordner in der Quelle, die durch das Verschieben leer werden, verschwinden; alles andere bleibt. */
class LeereOrdnerTest {

    @TempDir
    Path basis;

    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @Test
    void leergewordeneOrdnerWerdenEntferntRestBleibt() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        datei(quelle, "Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/lhd-kater-1080p.mkv");
        datei(quelle, "Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/cover.jpg");
        Files.createDirectories(quelle.resolve("Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/Subs"));
        datei(quelle, "Das.Dschungelbuch.2016.German.1080p/jungle.mkv");
        datei(quelle, "Das.Dschungelbuch.2016.German.1080p/Thumbs.db");
        Files.createDirectories(quelle.resolve("Schon vorher leer"));

        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);

        assertFalse(Files.exists(quelle.resolve("Der gestiefelte Kater (1080p)")), "ganz leer, auch mit leerem Unterordner");
        assertFalse(Files.exists(quelle.resolve("Das.Dschungelbuch.2016.German.1080p")), "nur Thumbs.db übrig: gelöscht");
        assertTrue(Files.isDirectory(quelle.resolve("Schon vorher leer")), "nicht betroffen, bleibt");
        assertTrue(Files.isDirectory(quelle), "die Quelle selbst bleibt");
    }

    @Test
    void systemresteWerdenMitgeloeschtAnderesBleibt() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        datei(quelle, "Der.Pate.1972.German.1080p/pate.mkv");
        datei(quelle, "Der.Pate.1972.German.1080p/desktop.ini");
        datei(quelle, "Der.Pate.1972.German.1080p/.DS_Store");
        datei(quelle, "Der.Pate.1972.German.1080p/@eaDir/pate.mkv/SYNOVIDEO_VIDEO_SCREENSHOT.jpg");
        datei(quelle, "Der.Pate.1972.German.1080p/Extras/@eaDir/x/SYNOPHOTO_THUMB_M.jpg");
        datei(quelle, "Der.Pate.1972.German.1080p/Extras/Thumbs.db");
        datei(quelle, "Matrix.1999.German.1080p/matrix.mkv");
        datei(quelle, "Matrix.1999.German.1080p/Thumbs.db");
        datei(quelle, "Matrix.1999.German.1080p/notizen.txt");

        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);

        assertFalse(Files.exists(quelle.resolve("Der.Pate.1972.German.1080p")), "nur Systemreste: weg");
        assertTrue(Files.exists(quelle.resolve("Matrix.1999.German.1080p/notizen.txt")), "eigene Datei: bleibt");
        assertTrue(Files.exists(quelle.resolve("Matrix.1999.German.1080p/Thumbs.db")), "Ordner bleibt, also auch der Rest");
    }

    @Test
    void quelleBleibtAuchWennSieLeerWird() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        datei(quelle, "Der.Pate.1972.German.1080p/pate.mkv");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);
        assertTrue(Files.isDirectory(quelle));
        try (var inhalt = Files.list(quelle)) {
            assertEquals(0, inhalt.count());
        }
        assertTrue(Files.isDirectory(basis), "oberhalb der Quelle wird nichts angefasst");
    }

    @Test
    void auchNachQuarantaeneUndMitZielInDerQuelle() throws IOException {
        Path quelle = basis;
        Path ziel = basis.resolve("Sammlung");
        datei(quelle, "Der.Pate.1972.German.1080p/pate.mkv");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);
        datei(quelle, "Neu/Der.Pate.1972.German.720p/pate.mkv");

        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);

        assertFalse(Files.exists(quelle.resolve("Neu")), "Doublette ist in der Quarantäne, Ordner leer");
        assertTrue(Files.isDirectory(ziel.resolve(Planer.QUARANTAENE)));
    }

    @Test
    void rueckgaengigLegtDieOrdnerWiederAn() throws IOException {
        Path quelle = basis.resolve("Quelle");
        Path ziel = basis.resolve("Sammlung");
        Path kater = datei(quelle, "Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/lhd-kater-1080p.mkv");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), quelle, ziel, still);
        assertFalse(Files.exists(kater.getParent()));

        Ausfuehrung.rueckgaengig(ziel, still);
        assertTrue(Files.exists(kater));
    }

    private static Path datei(Path quelle, String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
        return datei;
    }
}
