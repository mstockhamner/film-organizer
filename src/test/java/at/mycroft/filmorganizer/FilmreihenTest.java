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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filme einer Reihe wie Harry Potter kommen in einen gemeinsamen Ordner. */
class FilmreihenTest {

    @TempDir
    Path quelle;

    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @Test
    void erkenntReihenAmGemeinsamenTitelanfang() {
        Map<String, String> reihen = Filmreihen.erkennen(List.of(
                "Harry Potter und der Stein der Weisen", "Harry Potter und die Kammer des Schreckens",
                "Gregs Tagebuch - Von Idioten umzingelt", "Gregs Tagebuch 2 - Gibt's Probleme",
                "Gregs Tagebuch 3 - Jetzt reicht's",
                "Zurück in die Zukunft", "Zurueck in die Zukunft II", "Zurück in die Zukunft III",
                "Der Herr der Ringe: Die Gefährten", "Der Herr der Ringe - Die zwei Türme",
                "Shrek", "Shrek 2", "Der Pate"));
        assertEquals("Harry Potter", reihe(reihen, "Harry Potter und der Stein der Weisen"));
        assertEquals("Harry Potter", reihe(reihen, "Harry Potter und die Kammer des Schreckens"));
        assertEquals("Gregs Tagebuch", reihe(reihen, "Gregs Tagebuch - Von Idioten umzingelt"));
        assertEquals("Gregs Tagebuch", reihe(reihen, "Gregs Tagebuch 3 - Jetzt reicht's"));
        assertEquals("Zurück in die Zukunft", reihe(reihen, "Zurück in die Zukunft"));
        assertEquals("Zurück in die Zukunft", reihe(reihen, "Zurueck in die Zukunft II"));
        assertEquals("Der Herr der Ringe", reihe(reihen, "Der Herr der Ringe: Die Gefährten"));
        assertEquals("Shrek", reihe(reihen, "Shrek 2"));
        assertEquals(null, reihe(reihen, "Der Pate"));
    }

    @Test
    void keineReiheBeiZufaelligGleichemAnfang() {
        assertTrue(Filmreihen.erkennen(List.of(
                "Die Schöne und das Biest", "Die Schöne und der Bräutigam",
                "Der Pate", "Der Pianist",
                "Batman", "Batman Begins",
                "Rocky", "Rocky Balboa",
                "Das Dschungelbuch", "Das Dschungelbuch")).isEmpty());
    }

    @Test
    void reiheKommtInEinenGemeinsamenOrdner() throws IOException {
        datei("Harry.Potter.und.der.Stein.der.Weisen.2001.German.1080p/hp1.mkv", "hp1");
        datei("Harry.Potter.und.die.Kammer.des.Schreckens.2002.German.1080p/hp2.mkv", "hp2");
        datei("Der.Pate.1972.German.1080p/pate.mkv", "pate");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);

        assertTrue(Files.exists(ziel().resolve(
                "Harry Potter/Harry Potter und der Stein der Weisen (2001)/Harry Potter und der Stein der Weisen (2001).mkv")));
        assertTrue(Files.exists(ziel().resolve(
                "Harry Potter/Harry Potter und die Kammer des Schreckens (2002)/Harry Potter und die Kammer des Schreckens (2002).mkv")));
        assertTrue(Files.exists(ziel().resolve("Der Pate (1972)/Der Pate (1972).mkv")));

        // Der Katalog kennt die Filme in der Reihe: dieselben Filme noch einmal sind Doubletten.
        datei("Nochmal/Harry.Potter.und.der.Stein.der.Weisen.2001.720p.mkv", "hp1 720p");
        assertTrue(Main.planErstellen(quelle, ziel()).stream().allMatch(a -> a.art() == Art.DOUBLETTE));
    }

    @Test
    void vorhandenerFilmWirdInDieReiheUmsortiert() throws IOException {
        datei("Zurueck.in.die.Zukunft.1985.German.1080p/zidz.mkv", "teil 1");
        datei("Zurueck.in.die.Zukunft.1985.German.1080p/cover.jpg", "bild");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);
        assertTrue(Files.exists(ziel().resolve("Zurueck in die Zukunft (1985)/poster.jpg")));

        datei("Zurueck.in.die.Zukunft.II.1989.German.1080p/zidz2.mkv", "teil 2");
        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        Aktion umsortieren = plan.stream().filter(a -> a.art() == Art.UMSORTIEREN).findFirst().orElseThrow();
        assertEquals("Zurueck in die Zukunft", umsortieren.film().info().reihe());
        Ausfuehrung.ausfuehren(plan, ziel(), still);

        Path reihe = ziel().resolve("Zurueck in die Zukunft");
        assertTrue(Files.exists(reihe.resolve("Zurueck in die Zukunft (1985)/Zurueck in die Zukunft (1985).mkv")));
        assertTrue(Files.exists(reihe.resolve("Zurueck in die Zukunft (1985)/poster.jpg")));
        assertTrue(Files.exists(reihe.resolve("Zurueck in die Zukunft II (1989)/Zurueck in die Zukunft II (1989).mkv")));
        assertFalse(Files.exists(ziel().resolve("Zurueck in die Zukunft (1985)")));
        assertTrue(Main.planErstellen(quelle, ziel()).isEmpty(), "danach ist alles einsortiert");

        // Rückgängig holt Teil 2 zurück und stellt Teil 1 wieder an seinen alten Platz.
        Ausfuehrung.rueckgaengig(ziel(), still);
        assertTrue(Files.exists(ziel().resolve("Zurueck in die Zukunft (1985)/Zurueck in die Zukunft (1985).mkv")));
    }

    @Test
    void vonHandBenannterReihenordnerWirdWeiterverwendet() throws IOException {
        Path hand = ziel().resolve("Meine Potter-Filme/Harry Potter und der Stein der Weisen (2001)/hp1.mkv");
        Files.createDirectories(hand.getParent());
        Files.writeString(hand, "hp1");
        Path hp2 = datei("Harry.Potter.und.die.Kammer.des.Schreckens.2002.German.1080p/hp2.mkv", "hp2");

        Aktion aktion = aktion(Main.planErstellen(quelle, ziel()), hp2);
        assertEquals(Art.EINSORTIEREN, aktion.art());
        assertEquals(ziel().resolve("Meine Potter-Filme/Harry Potter und die Kammer des Schreckens (2002)/"
                + "Harry Potter und die Kammer des Schreckens (2002).mkv"), aktion.verschiebungen().get(0).nach());
    }

    @Test
    void reiheLaesstSichInDerVorschauAendern() throws IOException {
        Path hp1 = datei("Harry.Potter.und.der.Stein.der.Weisen.2001.German.1080p/hp1.mkv", "hp1");
        datei("Harry.Potter.und.die.Kammer.des.Schreckens.2002.German.1080p/hp2.mkv", "hp2");
        Path pate = datei("Der.Pate.1972.German.1080p/pate.mkv", "pate");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());

        v.reiheSetzen(hp1, "");
        assertEquals(ziel().resolve("Harry Potter und der Stein der Weisen (2001)"),
                aktion(v.plan(), hp1).verschiebungen().get(0).nach().getParent());
        v.reiheSetzen(pate, "Mafia");
        assertEquals(ziel().resolve("Mafia/Der Pate (1972)"), aktion(v.plan(), pate).verschiebungen().get(0).nach().getParent());
    }

    @Test
    void reiheNummerUndTitelImNamen() {
        assertEquals(new FilmInfo("Zurück in die Gegenwart", 0, 0, 0, 0, "", "Star Trek"),
                NamensErkennung.erkennenReihenfilm("Star Trek - 04 - Zurück in die Gegenwart").orElseThrow());
        assertEquals(new FilmInfo("Zurück in die Gegenwart", 1986, 1080, 0, 0, "", "Star Trek"),
                NamensErkennung.erkennenReihenfilm("Star.Trek.-.04.-.Zurück.in.die.Gegenwart.(1986).1080p").orElseThrow());
        assertEquals(new FilmInfo("Das unentdeckte Land", 1991, 0, 0, 0, "", "Star Trek"),
                NamensErkennung.erkennen("Star Trek - 6 - Das unentdeckte Land 1991").orElseThrow());
        assertTrue(NamensErkennung.erkennen("Star Trek - 04 - Zurück in die Gegenwart").isEmpty(), "ohne Jahr");
        assertTrue(NamensErkennung.erkennenReihenfilm("Gregs Tagebuch 2 - Gibt's Probleme").isEmpty());
        assertTrue(NamensErkennung.erkennenReihenfilm("Kevin - Allein zu Haus").isEmpty());
    }

    @Test
    void reihenfilmOhneJahrWirdVorgeschlagenUndNachgetragen() throws IOException {
        Path ohneJahr = datei("Star Trek/Star Trek - 04 - Zurück in die Gegenwart.mkv", "st4");
        Path ordner = datei("Star Trek - 06 - Das unentdeckte Land (1991)/st6.mkv", "st6");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());

        Aktion st4 = aktion(v.plan(), ohneJahr);
        assertEquals(Art.UNERKANNT, st4.art());
        assertEquals("Zurück in die Gegenwart", v.titel(st4.film()));
        assertEquals("Star Trek", st4.film().info().reihe());
        assertEquals(ziel().resolve("Star Trek/Das unentdeckte Land (1991)/Das unentdeckte Land (1991).mkv"),
                aktion(v.plan(), ordner).verschiebungen().get(0).nach());

        v.korrigieren(ohneJahr, "Zurück in die Gegenwart", 1986);
        assertEquals(ziel().resolve("Star Trek/Zurück in die Gegenwart (1986)/Zurück in die Gegenwart (1986).mkv"),
                aktion(v.plan(), ohneJahr).verschiebungen().get(0).nach());
    }

    private static String reihe(Map<String, String> reihen, String titel) {
        return reihen.get(FilmInfo.normalisieren(titel));
    }

    private Path ziel() {
        return quelle.resolve("Sammlung").toAbsolutePath().normalize();
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
