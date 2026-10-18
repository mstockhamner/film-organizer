package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identische und schlechtere Doubletten kommen nach Ziel/_Doubletten, alles andere bleibt liegen. */
class QuarantaeneTest {

    @TempDir
    Path quelle;

    Path ziel;
    Path heute;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void sammlungEinsortieren() throws IOException {
        ziel = quelle.resolve("Sammlung");
        heute = ziel.resolve(Planer.QUARANTAENE).resolve(LocalDate.now().toString());
        datei("Das.Dschungelbuch.2016.German.1080p.BluRay.x264-FDHQ/jungle.mkv", "dschungel 1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
    }

    @Test
    void identischeUndSchlechtereFassungKommenInDieQuarantaene() throws IOException {
        Path kopie = datei("Kopie/Irgendein.Name.2001.mkv", "dschungel 1080p");
        Path schlechter = datei("Das Dschungelbuch (2016) 720p/dschungel.mkv", "dschungel 720p");
        Path poster = datei("Das Dschungelbuch (2016) 720p/cover.jpg", "bild");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertTrue(aktion(plan, kopie).inQuarantaene());
        assertTrue(aktion(plan, schlechter).inQuarantaene());
        Ausfuehrung.ausfuehren(plan, ziel, still);

        assertTrue(Files.exists(inQuarantaene(kopie)));
        assertTrue(Files.exists(inQuarantaene(schlechter)));
        assertTrue(Files.exists(inQuarantaene(poster)));
        assertFalse(Files.exists(kopie));
        assertFalse(Files.exists(schlechter));
    }

    @Test
    void bessereOderUnklareFassungBleibtLiegen() throws IOException {
        Path besser = datei("Das.Dschungelbuch.2016.German.2160p.UHD.BluRay.x265-GRP/jungle.mkv", "4k");
        Path unklar = datei("Das Dschungelbuch (2016)/andere.mkv", "ohne aufloesung");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(Art.DOUBLETTE, aktion(plan, besser).art());
        assertFalse(aktion(plan, besser).inQuarantaene());
        assertFalse(aktion(plan, unklar).inQuarantaene());
        Ausfuehrung.ausfuehren(plan, ziel, still);
        assertTrue(Files.exists(besser));
        assertTrue(Files.exists(unklar));
    }

    @Test
    void doubletteInnerhalbDesImportsKommtInDieQuarantaene() throws IOException {
        Path gut = datei("Der.Pate.1972.German.1080p.BluRay/pate.mkv", "pate 1080p");
        Path schlecht = datei("Der.Pate.1972.German.720p.WEBRip/pate.mkv", "pate 720p");
        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(Art.EINSORTIEREN, aktion(plan, gut).art());
        assertTrue(aktion(plan, schlecht).inQuarantaene());
    }

    @Test
    void scanUebersiehtDieQuarantaeneUndRueckgaengigHoltAllesZurueck() throws IOException {
        Path kopie = datei("Kopie/Irgendein.Name.2001.mkv", "dschungel 1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
        assertTrue(Main.planErstellen(quelle, ziel).isEmpty());

        assertEquals(1, Ausfuehrung.rueckgaengig(ziel, still));
        assertTrue(Files.exists(kopie));
        assertFalse(Files.exists(ziel.resolve(Planer.QUARANTAENE)));
    }

    @Test
    void belegterPlatzInDerQuarantaeneWirdNichtUeberschrieben() throws IOException {
        Path kopie = datei("Kopie/Irgendein.Name.2001.mkv", "dschungel 1080p");
        Files.createDirectories(inQuarantaene(kopie).getParent());
        Files.writeString(inQuarantaene(kopie), "schon da");
        Aktion aktion = aktion(Main.planErstellen(quelle, ziel), kopie);
        assertEquals(Art.DOUBLETTE, aktion.art());
        assertFalse(aktion.inQuarantaene());
    }

    private Path inQuarantaene(Path datei) {
        Path absolut = datei.toAbsolutePath().normalize();
        return heute.resolve(absolut.getRoot().relativize(absolut));
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().toAbsolutePath().normalize().equals(video))
                .findFirst().orElseThrow();
    }

    private Path datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
