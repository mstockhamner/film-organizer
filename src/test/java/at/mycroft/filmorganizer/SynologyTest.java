package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Papierkorb und Metadaten-Ordner einer Synology-Freigabe gehören nicht zur Sammlung. */
class SynologyTest {

    @TempDir
    Path quelle;

    @Test
    void papierkorbUndEaDirWerdenIgnoriert() throws IOException {
        datei("Der.Pate.1972.German.1080p/pate.mkv");
        datei("#recycle/Der.Pate.1972.German.720p/pate.mkv");
        datei("#Recycle/Matrix.1999.German.1080p/matrix.mkv");
        datei("Filme/#recycle/Alien.1979.mkv");
        datei("Der.Pate.1972.German.1080p/@eaDir/pate.mkv/SYNOVIDEO_VIDEO_SCREENSHOT.jpg");
        datei("Serien/@eaDir/Dark.S01E01.mkv");

        List<Film> filme = Scanner.scannen(quelle, quelle.resolve("Sammlung"));
        assertEquals(List.of("pate.mkv"), filme.stream().map(f -> f.video().getFileName().toString()).toList());
        // Das Vorschaubild in @eaDir wird auch nicht als Cover mitgenommen.
        assertEquals(List.of(), filme.get(0).begleitdateien());
        // Die Kopie im Papierkorb ist keine Doublette.
        assertEquals(Art.EINSORTIEREN, Main.planErstellen(quelle, quelle.resolve("Sammlung")).get(0).art());
    }

    @Test
    void quelleDarfSelbstDerPapierkorbSein() throws IOException {
        Path papierkorb = quelle.resolve("#recycle");
        datei("#recycle/Der.Pate.1972.German.720p/pate.mkv");
        assertEquals(1, Scanner.scannen(papierkorb, quelle.resolve("Sammlung")).size());
    }

    private void datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
    }
}
