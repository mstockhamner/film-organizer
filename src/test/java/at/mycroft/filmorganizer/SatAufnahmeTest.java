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
    void receiverDateienWandernMitUndHeissenWieDerFilm() throws IOException {
        Path video = datei("movie/20200612 2226 - ATV HD - The Greatest Showman.ts", "video");
        Files.writeString(video.resolveSibling(video.getFileName() + ".meta"),
                "1:0:19:2F:9:85:C00000:0:0:0::ATV HD\nThe Greatest Showman\nThe Greatest Showman\\nThriller, USA 2017\n");
        for (String endung : List.of(".ts.ap", ".ts.sc", ".ts.cuts", ".eit")) {
            datei("movie/20200612 2226 - ATV HD - The Greatest Showman" + endung, endung);
        }
        // Eine zweite Aufnahme im selben Ordner: ihre Dateien bleiben bei ihr.
        datei("movie/20210110 1232 - PULS 4 Austria - Ready Player One.ts", "anderes video");
        datei("movie/20210110 1232 - PULS 4 Austria - Ready Player One.ts.ap", "ap");

        Aktion aktion = aktion(video);
        List<String> ziele = aktion.verschiebungen().stream().map(v -> v.nach().getFileName().toString()).sorted().toList();
        assertEquals(List.of("The Greatest Showman (2017).eit", "The Greatest Showman (2017).ts",
                "The Greatest Showman (2017).ts.ap", "The Greatest Showman (2017).ts.cuts",
                "The Greatest Showman (2017).ts.meta", "The Greatest Showman (2017).ts.sc"), ziele);
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
    void ohneJahrWirdOhneJahrEinsortiertUndEinJahrLaesstSichNachtragen() throws IOException {
        Path video = datei("Aufnahmen 2020/20200612 2226 - ATV HD - The Greatest Showman.ts", "video");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());
        Aktion aktion = v.plan().stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertTrue(aktion.verschiebungen().get(0).nach().endsWith(Path.of("The Greatest Showman", "The Greatest Showman.ts")));
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
