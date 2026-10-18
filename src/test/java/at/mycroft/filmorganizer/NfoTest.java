package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** .nfo im Kodi-Format aus den Receiver-Daten, nachgebaut nach "The Greatest Showman" in z:\4sort. */
class NfoTest {

    static final String LANG = """
            Mitreißende Filmbiografie mit Hugh Jackman: Als der Familienvater P.T. Barnum plötzlich arbeitslos wird, \
            gründet er ein Kuriositätenkabinett.

            Regie: Michael Gracey
            Drehbuch: Jenny Bicks, Bill Condon
            Kamera: Seamus McGarvey

            Darsteller:
            Hugh Jackman (P.T. Barnum)
            Michelle Williams (Charity Barnum)
            Zac Efron (Phillip Carlyle)""";

    @TempDir
    Path quelle;

    @Test
    void filmMitInhaltGenreRegieUndDarstellern() {
        ReceiverDaten daten = new ReceiverDaten("The Greatest Showman", "ATV HD",
                "The Greatest Showman\nThriller, USA 2017\nAltersfreigabe: ab 6", LANG, "HD Video");
        String nfo = Nfo.erstellen(new FilmInfo("The Greatest Showman", 2017, 720), daten, LocalDate.of(2020, 6, 12));
        assertTrue(nfo.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<movie>\n"));
        assertTrue(nfo.contains("  <title>The Greatest Showman</title>\n  <year>2017</year>\n"));
        assertTrue(nfo.contains("<plot>Mitreißende Filmbiografie mit Hugh Jackman: Als der Familienvater P.T. Barnum "
                + "plötzlich arbeitslos wird, gründet er ein Kuriositätenkabinett.</plot>"));
        assertTrue(nfo.contains("<genre>Thriller</genre>"));
        assertTrue(nfo.contains("<director>Michael Gracey</director>"));
        assertTrue(nfo.contains("<credits>Jenny Bicks</credits>\n  <credits>Bill Condon</credits>"));
        assertTrue(nfo.contains("<actor>\n    <name>Hugh Jackman</name>\n    <role>P.T. Barnum</role>\n  </actor>"));
        assertTrue(nfo.contains("<name>Zac Efron</name>"));
    }

    @Test
    void folgeEinerSendereihe() {
        ReceiverDaten daten = new ReceiverDaten("Terra X", "zdf_neo", "Eine Erde - viele Welten - Inseln",
                "Inseln sind Laboratorien der Evolution & <Rückzugsorte>.", "");
        String nfo = Nfo.erstellen(FilmInfo.sendung("Terra X", "Eine Erde - viele Welten - Inseln"), daten,
                LocalDate.of(2023, 8, 20));
        assertTrue(nfo.contains("<episodedetails>\n  <title>Eine Erde - viele Welten - Inseln</title>\n"
                + "  <showtitle>Terra X</showtitle>\n"));
        assertTrue(nfo.contains("<plot>Inseln sind Laboratorien der Evolution &amp; &lt;Rückzugsorte&gt;.</plot>"));
        assertTrue(nfo.contains("<aired>2023-08-20</aired>"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The Greatest Showman\\nThriller, USA 2017                                        | Thriller
            (Astérix et les Vikings - Zeichentrickfilm, DNK/FRA 2006) In ihrem achten         | Zeichentrickfilm
            Jack Reacher: Kein Weg zurückThriller, USA/VRC 2016Altersfreigabe: ab 16         | Thriller
            Das fünfte Element Science-Fiction, USA/F 1996 Altersfreigabe: ab 6              | Science-Fiction
            Animationsfilm, USA 2011                                                         | Animationsfilm
            Brian, zur Zeit der römischen Besatzung in Judäa geboren                         | ''
            """)
    void genre(String text, String genre) {
        assertEquals(genre, Nfo.genre(text.replace("\\n", "\n")));
    }

    @Test
    void nfoWirdBeimEinsortierenAngelegtUndBeimRueckgaengigEntfernt() throws IOException {
        Path ziel = quelle.resolve("Sammlung").toAbsolutePath().normalize();
        Path video = quelle.resolve("movie/20200612 2226 - ATV HD - The Greatest Showman.ts").toAbsolutePath().normalize();
        Files.createDirectories(video.getParent());
        Files.writeString(video, "video");
        Files.writeString(video.resolveSibling(video.getFileName() + ".meta"),
                "1:0:19:33AC:3EB:1:C00000:0:0:0::ATV HD\nThe Greatest Showman\n"
                        + "The Greatest Showman\\nThriller, USA 2017\\nAltersfreigabe: ab 6\n1591993560\n",
                StandardCharsets.UTF_8);
        PrintStream still = new PrintStream(new ByteArrayOutputStream());

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        Path nfo = ziel.resolve("The Greatest Showman (2017)/The Greatest Showman (2017).nfo");
        assertEquals(List.of(nfo), plan.get(0).neueDateien().stream().map(Aktion.NeueDatei::pfad).toList());
        Ausfuehrung.ausfuehren(plan, ziel, still);
        assertTrue(Files.readString(nfo).contains("<genre>Thriller</genre>"));

        Ausfuehrung.rueckgaengig(ziel, still);
        assertFalse(Files.exists(nfo));
        assertFalse(Files.exists(nfo.getParent()), "Filmordner ist wieder weg");
        assertTrue(Files.exists(video));
    }

    @Test
    void ohneReceiverDatenKeineNfo() throws IOException {
        Path ordner = quelle.resolve("The Greatest Showman (2017)");
        Files.createDirectories(ordner);
        Files.writeString(ordner.resolve("20200612 2226 - ATV HD - The Greatest Showman.ts"), "video");
        List<Aktion> plan = Main.planErstellen(quelle, quelle.resolve("Sammlung"));
        assertEquals(List.of(), plan.get(0).neueDateien());
    }
}
