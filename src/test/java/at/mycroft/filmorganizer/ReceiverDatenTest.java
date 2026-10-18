package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Metadaten von Sat-Aufnahmen, nachgebaut nach echten Enigma2-Dateien (ATV, ORF, Pro7 MAXX, zdf_neo, TOGGO). */
class ReceiverDatenTest {

    @TempDir
    Path quelle;

    @Test
    void liestEitMitUtf8UndIso885915() throws IOException {
        ByteArrayOutputStream eit = new ByteArrayOutputStream();
        eit.write(new byte[12]); // Kopf: Event-ID, Startzeit, Dauer, Länge
        byte[] name = dvb(0x15, "The Greatest Showman", StandardCharsets.UTF_8);
        byte[] kurz = dvb(0x15, "The Greatest Showman\\nThriller, USA 2017", StandardCharsets.UTF_8);
        eit.write(0x4D);
        eit.write(3 + 1 + name.length + 1 + kurz.length);
        eit.write("ger".getBytes(StandardCharsets.US_ASCII));
        eit.write(name.length);
        eit.write(name);
        eit.write(kurz.length);
        eit.write(kurz);
        byte[] lang = dvb(0x0B, "Als der Familienvater P.T. Barnum plötzlich arbeitslos wird", Charset.forName("ISO-8859-15"));
        eit.write(0x4E);
        eit.write(1 + 3 + 1 + 1 + lang.length);
        eit.write(0x00);
        eit.write("ger".getBytes(StandardCharsets.US_ASCII));
        eit.write(0x00);
        eit.write(lang.length);
        eit.write(lang);

        String[] teile = ReceiverDaten.eitLesen(eit.toByteArray());
        assertEquals("The Greatest Showman", teile[0]);
        assertEquals("The Greatest Showman\nThriller, USA 2017", teile[1]);
        assertEquals("Als der Familienvater P.T. Barnum plötzlich arbeitslos wird", teile[2]);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Terra X                  | Rätselhafte Phänomene - Monsterwellen und Fluggiganten                   | Rätselhafte Phänomene - Monsterwellen und Fluggiganten
            Ninjago                  | Die Verschmelzung: Teil 1 (1)                                            | Die Verschmelzung: Teil 1 (1)
            Inside Star Wars         | Die dunkle Bedrohung Doku-Reihe, USA 2022 Altersfreigabe: ab 12          | Die dunkle Bedrohung
            Prinzessin Fantaghirò    | Teil 4                                                                   | Teil 4
            zeit.geschichte          | Soundtrack Österreich - Von heiler Welt und Rock 'n' Roll (1/4)          | Soundtrack Österreich - Von heiler Welt und Rock 'n' Roll (1/4)
            """)
    void erkenntFolgenEinerSendereihe(String titel, String kurz, String folge) {
        assertEquals(folge, new ReceiverDaten(titel, kurz, kurz).folge(titel));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The Greatest Showman     | The Greatest Showman
            Blind Side               | Blind Side - Die große ChanceDrama, USA 2009Altersfreigabe: ab 6
            Angel Has Fallen         | (Actionfilm, USA 2019) 108.00
            Elfen helfen             | Animationsfilm, USA 2011
            District 9               | USA 2009 Vor 28 Jahren ist ein Raumschiff mit Außerirdischen über Johannisburg gestrandet.
            Galaxy Quest             | USA 1999 Vier Staffeln lang war die Besatzung unterwegs, dann wurde die TV-Serie abgesetzt.
            Blade Runner 2049        | Mit dem neuen Replikanten-Modell "Nexus 9" kommt auch die "Blade Runner"-Einheit des LAPD wieder zum Einsatz, die alte
            """)
    void filmeSindKeineFolgen(String titel, String kurz) {
        assertNull(new ReceiverDaten(titel, kurz, kurz).folge(titel));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            (Astérix et les Vikings - Zeichentrickfilm, DNK/FRA 2006) In ihrem achten Abenteuer   | 2006
            (Animationsfilm, DEU 2017) Temporeich und turbulent!                                  | 2017
            Großbritannien 2003London in der Vorweihnachtszeit.                                   | 2003
            Jack Reacher: Kein Weg zurückThriller, USA/VRC 2016Altersfreigabe: ab 16(WH vom Freitag, 17.12.2021) | 2016
            Wie wir wurden. Was wir sind (1945-1960)                                              | 0
            """)
    void jahrAusDerBeschreibung(String beschreibung, int jahr) {
        assertEquals(jahr, new ReceiverDaten("", "", beschreibung).jahr(2026));
    }

    @Test
    void folgenEinerSendereiheKommenInDenSerienordner() throws IOException {
        Path ziel = quelle.resolve("Sammlung").toAbsolutePath().normalize();
        Path inseln = aufnahme("20230820 0837 - zdf_neo - Terra X", "Terra X", "Eine Erde - viele Welten - Inseln");
        Path anfang = aufnahme("20230820 0922 - zdf_neo - Terra X", "Terra X",
                "Sternstunden der Evolution mit Dirk Steffens - 1. Der Anfang von allem");
        Path drachen = aufnahme("20231009 1547 - TOGGO plus - Ninjago - Aufstieg der Drachen",
                "Ninjago - Aufstieg der Drachen", "Die Verschmelzung: Teil 1 (1)");
        Path nochmal = aufnahme("20231109 1547 - TOGGO plus - Ninjago - Aufstieg der Drachen",
                "Ninjago - Aufstieg der Drachen", "Die Verschmelzung: Teil 1 (1)");

        List<Aktion> plan = Main.planErstellen(quelle, ziel);
        assertEquals(ziel.resolve("Serien/Terra X/Terra X - Eine Erde - viele Welten - Inseln.ts"), nach(plan, inseln));
        assertEquals(ziel.resolve("Serien/Terra X/Terra X - Sternstunden der Evolution mit Dirk Steffens - 1. Der Anfang von allem.ts"),
                nach(plan, anfang));
        assertEquals(ziel.resolve("Serien/Ninjago - Aufstieg der Drachen/"
                + "Ninjago - Aufstieg der Drachen - Die Verschmelzung - Teil 1 (1).ts"), nach(plan, drachen));
        assertEquals(Art.DOUBLETTE, aktion(plan, nochmal).art(), "dieselbe Folge zweimal aufgenommen");

        Ausfuehrung.ausfuehren(plan, ziel, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        assertTrue(Files.exists(ziel.resolve("Serien/Terra X/Terra X - Eine Erde - viele Welten - Inseln.ts")));

        // Beim nächsten Lauf sind die Folgen bekannt, auch von Hand abgelegte.
        Path hand = ziel.resolve("Serien/Terra X/Terra X - Rätselhafte Phänomene.ts");
        Files.writeString(hand, "von Hand abgelegt, größer als die neue Aufnahme".repeat(3));
        Path wieder = aufnahme("20240101 1200 - zdf_neo - Terra X", "Terra X", "Eine Erde - viele Welten - Inseln");
        Path raetsel = aufnahme("20240102 1200 - zdf_neo - Terra X", "Terra X", "Rätselhafte Phänomene");
        List<Aktion> danach = Main.planErstellen(quelle, ziel);
        assertEquals(Art.DOUBLETTE, aktion(danach, wieder).art());
        assertEquals(Art.DOUBLETTE, aktion(danach, raetsel).art());
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private static Path nach(List<Aktion> plan, Path video) {
        Aktion a = aktion(plan, video);
        assertEquals(Art.EINSORTIEREN, a.art(), a.hinweis());
        return a.verschiebungen().get(0).nach();
    }

    @Test
    void aufloesungNachSenderUndBildangabe() {
        assertEquals(720, new ReceiverDaten("", "", "", "", "HD Video\nstereo deutsch").aufloesung("VOX Austria"));
        assertEquals(720, new ReceiverDaten("", "ATV HD", "", "", "").aufloesung(""));
        assertEquals(720, ReceiverDaten.LEER.aufloesung("ORF1 HD"));
        assertEquals(576, new ReceiverDaten("", "zdf_neo SD", "", "", "stereo deutsch").aufloesung("zdf_neo SD"));
        assertEquals(576, ReceiverDaten.LEER.aufloesung("RTLZWEI Austria"));
    }

    @Test
    void aufnahmeVerdraengtKeineEchte1080pFassung() throws IOException {
        Path ziel = quelle.resolve("Sammlung").toAbsolutePath().normalize();
        Path blu = quelle.resolve("La.La.Land.2016.German.1080p.BluRay/lll.mkv");
        Files.createDirectories(blu.getParent());
        Files.writeString(blu, "1080p");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel,
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        // Die Aufnahme ist viel größer (Werbung), aber nur HD = 720p.
        Path aufnahme = aufnahme("20200914 0048 - sixx Austria HD - La La Land", "La La Land",
                "La La Land\\nMusical, USA 2016\\nAltersfreigabe: Ohne Altersbeschränkung");
        Files.writeString(aufnahme, "x".repeat(1000));
        Aktion aktion = Main.planErstellen(quelle, ziel).stream()
                .filter(a -> a.film().video().equals(aufnahme)).findFirst().orElseThrow();
        assertEquals(720, aktion.film().info().aufloesung());
        assertTrue(aktion.inQuarantaene(), aktion.hinweis());
    }

    @Test
    void titelAusDerMetaDateiStattAusDemDateinamen() throws IOException {
        Path video = aufnahme("20260509 2332 - RTLZWEI Austria - Die Nackte Kanone 33 13", "Die Nackte Kanone 33 1/3",
                "Chaos-Cop Frank Drebin hat seinen Job gegen das unbeschwerte Leben eines Hausmannes eingetauscht. Doch");
        Aktion aktion = Main.planErstellen(quelle, quelle.resolve("Sammlung")).get(0);
        assertEquals(video, aktion.film().video());
        assertEquals("Die Nackte Kanone 33 1/3", aktion.film().info().titel());
        assertEquals(Art.UNERKANNT, aktion.art(), "RTLZWEI nennt kein Jahr");
    }

    private Path aufnahme(String name, String titel, String beschreibung) throws IOException {
        Path video = quelle.resolve(name + ".ts").toAbsolutePath().normalize();
        Files.writeString(video, name);
        Files.writeString(video.resolveSibling(name + ".ts.meta"),
                "1:0:19:33AC:3EB:1:C00000:0:0:0::Sender\n" + titel + "\n" + beschreibung + "\n1591993560\n",
                StandardCharsets.UTF_8);
        return video;
    }

    private static byte[] dvb(int zeichensatz, String text, Charset kodierung) {
        byte[] t = text.getBytes(kodierung);
        byte[] ergebnis = new byte[t.length + 1];
        ergebnis[0] = (byte) zeichensatz;
        System.arraycopy(t, 0, ergebnis, 1, t.length);
        return ergebnis;
    }
}
