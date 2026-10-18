package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Von Hand gesetzte Filmreihen werden gemerkt und gelten auch für die Sammlung. */
class ReihenzuordnungTest {

    @TempDir
    Path quelle;

    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @Test
    void vonHandGesetzteReihenWerdenGemerkt() throws IOException {
        Path bb = datei("Batman.Begins.2005.German.1080p/bb.mkv", "bb");
        Path tdk = datei("The.Dark.Knight.2008.German.1080p/tdk.mkv", "tdk");
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());
        v.reiheSetzen(bb, "Batman");
        v.reiheSetzen(tdk, "Batman");
        List<Aktion> plan = v.plan();
        Ausfuehrung.ausfuehren(plan, ziel(), still);
        assertEquals(2, v.zuordnungenMerken(plan));

        assertTrue(Files.exists(ziel().resolve("Batman/The Dark Knight (2008)/The Dark Knight (2008).mkv")));
        String datei = Files.readString(ziel().resolve(".filmorganizer/filmreihen.tsv"));
        assertTrue(datei.contains("Batman Begins\t2005\tBatman"));
        assertTrue(datei.contains("The Dark Knight\t2008\tBatman"));

        // Dieselben Filme noch einmal aus einer anderen Quelle: die gemerkte Reihe gilt weiter.
        Path nochmal = datei("Neu/The.Dark.Knight.2008.German.720p/tdk.mkv", "andere fassung");
        Aktion aktion = Main.planErstellen(quelle, ziel()).stream()
                .filter(a -> a.film().video().toAbsolutePath().normalize().equals(nochmal)).findFirst().orElseThrow();
        assertEquals("Batman", aktion.film().info().reihe());
    }

    @Test
    void gemerkteDateiGiltVorDerErkennung() throws IOException {
        merken("# Titel\tJahr\tFilmreihe", "The Dark Knight Rises\t2012\tBatman", "Shrek\t2001\t", "Shrek 2\t2004\t");
        datei("The.Dark.Knight.Rises.2012.German.1080p/tdkr.mkv", "tdkr");
        datei("Shrek.2001.German.1080p/shrek.mkv", "shrek");
        datei("Shrek.2.2004.German.1080p/shrek2.mkv", "shrek 2");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);

        assertTrue(Files.exists(ziel().resolve("Batman/The Dark Knight Rises (2012)/The Dark Knight Rises (2012).mkv")));
        assertTrue(Files.exists(ziel().resolve("Shrek (2001)/Shrek (2001).mkv")), "keine Reihe trotz Erkennung");
        assertTrue(Files.exists(ziel().resolve("Shrek 2 (2004)/Shrek 2 (2004).mkv")));
    }

    @Test
    void filmDerSammlungWechseltDieReiheUndKommtAufWunschZurueck() throws IOException {
        datei("Harry.Potter.und.der.Stein.der.Weisen.2001.German.1080p/hp1.mkv", "hp1");
        datei("Harry.Potter.und.der.Stein.der.Weisen.2001.German.1080p/cover.jpg", "bild");
        datei("Harry.Potter.und.die.Kammer.des.Schreckens.2002.German.1080p/hp2.mkv", "hp2");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);
        Path hp1 = ziel().resolve("Harry Potter/Harry Potter und der Stein der Weisen (2001)/"
                + "Harry Potter und der Stein der Weisen (2001).mkv");
        assertTrue(Files.exists(hp1));

        // In eine andere Reihe.
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel());
        v.reiheSetzen(hp1, "Zauberei");
        List<Aktion> plan = v.plan();
        Aktion wechsel = plan.stream().filter(a -> a.art() == Art.UMSORTIEREN).findFirst().orElseThrow();
        assertTrue(wechsel.hinweis().contains("wechselt von der Filmreihe Harry Potter nach Zauberei"));
        Ausfuehrung.ausfuehren(plan, ziel(), still);
        v.zuordnungenMerken(plan);
        Path neu = ziel().resolve("Zauberei/Harry Potter und der Stein der Weisen (2001)");
        assertTrue(Files.exists(neu.resolve("Harry Potter und der Stein der Weisen (2001).mkv")));
        assertTrue(Files.exists(neu.resolve("poster.jpg")));
        assertTrue(Files.exists(ziel().resolve("Harry Potter/Harry Potter und die Kammer des Schreckens (2002)")));
        assertTrue(Main.planErstellen(quelle, ziel()).isEmpty(), "gemerkt: kein erneutes Umsortieren");

        // Und ohne Reihe zurück direkt ins Ziel; der leere Reihen-Ordner verschwindet.
        Vorschlaege zurueck = Vorschlaege.einlesen(quelle, ziel());
        zurueck.reiheSetzen(neu.resolve("Harry Potter und der Stein der Weisen (2001).mkv"), "");
        Ausfuehrung.ausfuehren(zurueck.plan(), ziel(), still);
        assertTrue(Files.exists(ziel().resolve("Harry Potter und der Stein der Weisen (2001)/poster.jpg")));
        assertFalse(Files.exists(ziel().resolve("Zauberei")));
    }

    @Test
    void gemerkteReiheSortiertAuchAufDerKommandozeileUm() throws IOException {
        datei("Der.Pate.1972.German.1080p/pate.mkv", "pate");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel()), ziel(), still);
        merken("Der Pate\t1972\tMafia");

        List<Aktion> plan = Main.planErstellen(quelle, ziel());
        assertEquals(List.of(Art.UMSORTIEREN), plan.stream().map(Aktion::art).toList());
        Ausfuehrung.ausfuehren(plan, ziel(), still);
        assertTrue(Files.exists(ziel().resolve("Mafia/Der Pate (1972)/Der Pate (1972).mkv")));

        // Rückgängig stellt den alten Platz wieder her.
        Ausfuehrung.rueckgaengig(ziel(), still);
        assertTrue(Files.exists(ziel().resolve("Der Pate (1972)/Der Pate (1972).mkv")));
        assertFalse(Files.exists(ziel().resolve("Mafia")));
    }

    private void merken(String... zeilen) throws IOException {
        Path datei = ziel().resolve(".filmorganizer/filmreihen.tsv");
        Files.createDirectories(datei.getParent());
        Files.write(datei, List.of(zeilen), StandardCharsets.UTF_8);
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
