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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        assertEquals(new FilmInfo("Zurück in die Gegenwart", 0, 0, 0, 0, "", "Star Trek").mitNummer(4),
                NamensErkennung.erkennenReihenfilm("Star Trek - 04 - Zurück in die Gegenwart").orElseThrow());
        assertEquals(new FilmInfo("Zurück in die Gegenwart", 1986, 1080, 0, 0, "", "Star Trek").mitNummer(4),
                NamensErkennung.erkennenReihenfilm("Star.Trek.-.04.-.Zurück.in.die.Gegenwart.(1986).1080p").orElseThrow());
        assertEquals(new FilmInfo("Das unentdeckte Land", 1991, 0, 0, 0, "", "Star Trek").mitNummer(6),
                NamensErkennung.erkennen("Star Trek - 6 - Das unentdeckte Land 1991").orElseThrow());
        assertTrue(NamensErkennung.erkennen("Star Trek - 04 - Zurück in die Gegenwart").isEmpty(), "ohne Jahr");
        assertTrue(NamensErkennung.erkennenReihenfilm("Kevin - Allein zu Haus").isEmpty());
    }

    /** Die Namen aus V:\Filme\sort\Star Trek (Reihe und Nummer ohne Bindestrich dazwischen). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Star Trek 01 - Der Film                         | Der Film                        | 1
            Star Trek 03 - Auf der suche nach Mr Spock      | Auf der suche nach Mr Spock     | 3
            Star Trek 04 - Zurück in die Gegenwart          | Zurück in die Gegenwart         | 4
            Star Trek - 04 - Zurück in die Gegenwart        | Zurück in die Gegenwart         | 4
            Star Trek 10 - Nemesis                          | Nemesis                         | 10
            Star Trek 12 - Into Darkness                    | Into Darkness                   | 12
            Gregs Tagebuch 2 - Gibt's Probleme              | Gibt's Probleme                 | 2
            """)
    void reiheUndNummerMitOderOhneBindestrich(String name, String titel, int nummer) {
        FilmInfo info = NamensErkennung.erkennenReihenfilm(name).orElseThrow();
        assertEquals(titel, info.titel());
        assertEquals(nummer, info.nummer());
        assertTrue(info.reihe().equals("Star Trek") || info.reihe().equals("Gregs Tagebuch"));
    }

    @Test
    void reihenfilmOhneJahrWirdVorgeschlagenUndNachgetragen() throws IOException {
        Path ohneJahr = datei("Star Trek/Star Trek - 04 - Zurück in die Gegenwart.mkv", "st4");
        Path ordner = datei("Star Trek - 06 - Das unentdeckte Land (1991)/st6.mkv", "st6");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());

        // Ohne Jahr: einsortiert ohne "(Jahr)" im Ordner- und Dateinamen.
        Aktion st4 = aktion(v.plan(), ohneJahr);
        assertEquals(Art.EINSORTIEREN, st4.art());
        assertEquals("Zurück in die Gegenwart", v.titel(st4.film()));
        assertEquals("Star Trek", st4.film().info().reihe());
        assertEquals(ziel().resolve("Star Trek/04 - Zurück in die Gegenwart/Zurück in die Gegenwart.mkv"),
                st4.verschiebungen().get(0).nach());
        assertEquals(ziel().resolve("Star Trek/06 - Das unentdeckte Land (1991)/Das unentdeckte Land (1991).mkv"),
                aktion(v.plan(), ordner).verschiebungen().get(0).nach());

        v.korrigieren(ohneJahr, "Zurück in die Gegenwart", 1986);
        assertEquals(ziel().resolve("Star Trek/04 - Zurück in die Gegenwart (1986)/Zurück in die Gegenwart (1986).mkv"),
                aktion(v.plan(), ohneJahr).verschiebungen().get(0).nach());

        // Nach dem Einsortieren kennt der Katalog Titel und Nummer: dieselben Filme noch einmal sind Doubletten.
        List<Aktion> plan = v.plan();
        Ausfuehrung.ausfuehren(plan, ziel(), still);
        Katalog katalog = Katalog.laden(ziel());
        Katalog.Eintrag eintrag = katalog.alle(new FilmInfo("Zurück in die Gegenwart", 1986, 0).schluessel()).get(0);
        assertEquals(4, eintrag.info().nummer());
        assertEquals("Star Trek", eintrag.info().reihe());
        datei("Nochmal/Star Trek - 04 - Zurück in die Gegenwart (1986) 720p.mkv", "st4 720p");
        Aktion nochmal = Main.planErstellen(quelle, ziel()).stream()
                .filter(x -> x.film().video().getFileName().toString().contains("720p")).findFirst().orElseThrow();
        // Größer bei unbekannter Auflösung der alten: Vorschlag Ersetzen, und zwar im nummerierten Ordner.
        assertEquals(Art.ERSETZEN, nochmal.art());
        assertEquals(ziel().resolve("Star Trek/04 - Zurück in die Gegenwart (1986)/Zurück in die Gegenwart (1986).mkv"),
                nochmal.verschiebungen().get(nochmal.verschiebungen().size() - 1).nach());
    }

    @Test
    void jahrGiltFuerAlleDateienMitGleichemTitel() throws IOException {
        Path klein = datei("Star Trek 04 - Zurück in die Gegenwart.m4v", "klein");
        Path gross = datei("Star Trek 04 - Zurück in die Gegenwart.mkv", "die große Fassung des Films");
        Path anderer = datei("Star Trek 05 - Am Rande des Universum.mkv", "fünf");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());

        v.korrigieren(gross, "Zurück in die Gegenwart", 1986);
        List<Aktion> plan = v.plan();
        assertEquals(1986, v.jahr(aktion(plan, klein).film()), "auch für die .m4v übernommen");
        assertEquals(0, v.jahr(aktion(plan, anderer).film()), "anderer Titel bleibt offen");
        assertEquals(ziel().resolve("Star Trek/04 - Zurück in die Gegenwart (1986)/Zurück in die Gegenwart (1986).mkv"),
                aktion(plan, gross).verschiebungen().get(0).nach());
        assertTrue(aktion(plan, klein).inQuarantaene(), "kleinere Datei derselben Fassung ist eine Doublette");
    }

    @Test
    void filmMitReihennamenAmAnfangKommtInDieBekannteReihe() throws IOException {
        datei("Star Trek 10 - Nemesis (2002).mkv", "nemesis");
        Path beyond = datei("Star.Trek.Beyond.2016.German.1080p.BluRay/beyond.mkv", "beyond");
        Path trekkies = datei("Star.Trekkies.1997.German/trekkies.mkv", "trekkies");
        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        assertEquals(ziel().resolve("Star Trek/Star Trek Beyond (2016)/Star Trek Beyond (2016).mkv"),
                aktion(plan, beyond).verschiebungen().get(0).nach());
        assertEquals(ziel().resolve("Star Trekkies (1997)/Star Trekkies (1997).mkv"),
                aktion(plan, trekkies).verschiebungen().get(0).nach(), "nur ganze Wörter");
        assertTrue(Planer.beginntMitWoertern("Star Trek-Into Darkness", "Star Trek"));
        assertFalse(Planer.beginntMitWoertern("Star Trek", "Star Trek"));
    }

    @Test
    void vonHandAbgelegterNummerierterReihenfilm() throws IOException {
        Path hand = ziel().resolve("Star Trek/04 - Zurück in die Gegenwart (1986)/st4.mkv");
        Files.createDirectories(hand.getParent());
        Files.writeString(hand, "von Hand");
        Katalog.Eintrag eintrag = Katalog.laden(ziel()).eintraege().get(0);
        assertEquals("Zurück in die Gegenwart", eintrag.info().titel());
        assertEquals(4, eintrag.info().nummer());
        assertEquals("Star Trek", eintrag.info().reihe());
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
