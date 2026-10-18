package at.mycroft.filmorganizer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion;
import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Ausfuehrung;
import at.mycroft.filmorganizer.Main;
import at.mycroft.filmorganizer.Vorschlaege;
import at.mycroft.filmorganizer.Vorschlaege.Sammlungsfilm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verwaltung der Filmreihen der Sammlung: umbenennen, auflösen, Filme verschieben. */
class ReihenDialogTest {

    @TempDir
    Path quelle;

    Path ziel;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void sammlungAnlegen() throws IOException {
        ziel = quelle.resolve("Sammlung").toAbsolutePath().normalize();
        datei("Harry.Potter.und.der.Stein.der.Weisen.2001.German.1080p/hp1.mkv");
        datei("Harry.Potter.und.die.Kammer.des.Schreckens.2002.German.1080p/hp2.mkv");
        datei("Der.Pate.1972.German.1080p/pate.mkv");
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, ziel), ziel, still);
    }

    @Test
    void sammlungNachReihenGruppiert() throws IOException {
        Map<String, List<Sammlungsfilm>> gruppen = ReihenDialog.gruppieren(Vorschlaege.einlesen(quelle, ziel).sammlung());
        assertEquals(List.of(ReihenDialog.OHNE_REIHE, "Harry Potter"), List.copyOf(gruppen.keySet()));
        assertEquals(List.of("Harry Potter und der Stein der Weisen", "Harry Potter und die Kammer des Schreckens"),
                gruppen.get("Harry Potter").stream().map(s -> s.info().titel()).toList());
        assertEquals("Der Pate", gruppen.get(ReihenDialog.OHNE_REIHE).get(0).info().titel());
    }

    @Test
    void reiheUmbenennenSortiertAlleFilmeUm() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        assertEquals(2, v.reiheUmbenennen("harry potter", "Zauberer"));
        // Vor dem Einsortieren: gewünscht "Zauberer", liegen noch in "Harry Potter".
        List<Sammlungsfilm> zauberer = ReihenDialog.gruppieren(v.sammlung()).get("Zauberer");
        assertEquals(2, zauberer.size());
        assertTrue(zauberer.stream().allMatch(s -> s.info().reihe().equals("Harry Potter")));
        List<Aktion> plan = v.plan();
        assertEquals(2, plan.stream().filter(a -> a.art() == Art.UMSORTIEREN).count());
        Ausfuehrung.ausfuehren(plan, ziel, still);
        v.zuordnungenMerken(plan);

        assertTrue(Files.exists(ziel.resolve("Zauberer/Harry Potter und der Stein der Weisen (2001)")));
        assertTrue(Files.exists(ziel.resolve("Zauberer/Harry Potter und die Kammer des Schreckens (2002)")));
        assertTrue(Files.notExists(ziel.resolve("Harry Potter")), "leerer Reihen-Ordner ist weg");
        assertTrue(Main.planErstellen(quelle, ziel).isEmpty());
    }

    @Test
    void reiheAufloesenUndFilmAufnehmen() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel);
        assertEquals(2, v.reiheUmbenennen("Harry Potter", ""));
        Sammlungsfilm pate = v.sammlung().stream().filter(s -> s.info().titel().equals("Der Pate")).findFirst().orElseThrow();
        v.reiheSetzen(pate.video(), "Klassiker");

        Map<String, List<Sammlungsfilm>> gruppen = ReihenDialog.gruppieren(v.sammlung());
        assertEquals(2, gruppen.get(ReihenDialog.OHNE_REIHE).size());
        assertEquals(1, gruppen.get("Klassiker").size());

        Ausfuehrung.ausfuehren(v.plan(), ziel, still);
        assertTrue(Files.exists(ziel.resolve("Harry Potter und der Stein der Weisen (2001)")));
        assertTrue(Files.exists(ziel.resolve("Klassiker/Der Pate (1972)/Der Pate (1972).mkv")));
    }

    private void datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
    }
}
