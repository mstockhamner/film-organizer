package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Aufnahmen eines Sat-Receivers: Titel aus dem Namen, Jahr aus Ordner oder Receiver-Dateien. */
class SatAufnahmeTest {

    @TempDir
    Path quelle;

    @Test
    void jahrAusDerSendungsbeschreibung() throws IOException {
        Path video = datei("movie/20200612 2226 - ATV HD - The Greatest Showman.ts", "video");
        // .meta von Enigma2: Dienst, Titel, Beschreibung, Zeit, …
        Files.writeString(video.resolveSibling(video.getFileName() + ".meta"),
                "1:0:19:2F:9:85:C00000:0:0:0:\nThe Greatest Showman\nMusical, USA 2017\n1591993560\n",
                StandardCharsets.ISO_8859_1);

        Aktion aktion = aktion(video);
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertEquals(ziel().resolve("The Greatest Showman (2017)/The Greatest Showman (2017).ts"),
                aktion.verschiebungen().get(0).nach());
    }

    @Test
    void jahrAusDerEitDatei() throws IOException {
        Path video = datei("movie/20191224 2015 - ORF1 HD - Kevin - Allein zu Haus.ts", "video");
        byte[] eit = ("\u0000\u0012\u0004 Kevin - Allein zu Haus \u0005Familienkomödie, USA 1990 \u0000")
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(video.resolveSibling("20191224 2015 - ORF1 HD - Kevin - Allein zu Haus.eit"), eit);
        assertEquals(1990, aktion(video).film().info().jahr());
    }

    @Test
    void jahrAusDemOrdnerWennErDenselbenFilmNennt() throws IOException {
        Path video = datei("The Greatest Showman (2017)/20200612 2226 - ATV HD - The Greatest Showman.ts", "video");
        assertEquals(2017, aktion(video).film().info().jahr());
    }

    @Test
    void ohneJahrIstDerTitelVorgeschlagenUndDasJahrWirdNachgetragen() throws IOException {
        Path video = datei("Aufnahmen 2020/20200612 2226 - ATV HD - The Greatest Showman.ts", "video");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());
        Aktion aktion = v.plan().stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
        assertEquals(Art.UNERKANNT, aktion.art());
        assertEquals("Jahr nicht erkennbar", aktion.hinweis());
        assertEquals("The Greatest Showman", v.titel(aktion.film()), "nicht der Ordner 'Aufnahmen 2020'");

        v.korrigieren(video, v.titel(aktion.film()), 2017);
        Aktion danach = v.plan().stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
        assertEquals(Art.EINSORTIEREN, danach.art());
        assertTrue(danach.verschiebungen().get(0).nach().endsWith(Path.of("The Greatest Showman (2017)",
                "The Greatest Showman (2017).ts")));
    }

    private Aktion aktion(Path video) throws IOException {
        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private Path ziel() {
        return quelle.resolve("Sammlung").toAbsolutePath().normalize();
    }

    private Path datei(String pfad, String inhalt) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, inhalt);
        return datei.toAbsolutePath().normalize();
    }
}
