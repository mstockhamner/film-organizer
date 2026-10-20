package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Abgleich.Art;
import at.mycroft.filmorganizer.Abgleich.Fund;
import at.mycroft.filmorganizer.Abgleich.Wahl;
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

/** Zwei mit dem Programm organisierte Sammlungen auf Doubletten abgleichen. */
class AbgleichTest {

    @TempDir
    Path basis;

    Path a;
    Path b;
    final PrintStream still = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void sammlungenAufbauen() throws IOException {
        a = basis.resolve("A");
        b = basis.resolve("B");
        einsortieren(a,
                "Der.Pate.1972.German.1080p.BluRay/pate.mkv", "pate 1080p, die gute Fassung",
                "Der.Pate.1972.German.1080p.BluRay/cover.jpg", "bild",
                "Das.Dschungelbuch.2016.German.720p/jungle.mkv", "jungle 720p",
                "Der.Herr.der.Ringe.2001.German.1080p/hdr.mkv", "hdr kino",
                "Dark.S01E01.German.1080p/dark.mkv", "dark folge 1",
                "Unklar.2010.German.1080p/u.mkv", "unklar aaaa");
        einsortieren(b,
                "Der.Pate.1972.German.720p/pate.mkv", "pate 720p",
                "Das.Dschungelbuch.2016.German.1080p/jungle.mkv", "jungle 1080p",
                "Der.Herr.der.Ringe.2001.EXTENDED.German.1080p/hdr.mkv", "hdr extended",
                "Dark.S01E01.German.1080p/dark.mkv", "dark folge 1",
                "Unklar.2010.German.1080p/u.mkv", "unklar bbbb",
                "Ganz.Anderer.Titel.1999.German.1080p/x.mkv", "pate 1080p, die gute Fassung",
                "Matrix.1999.German.1080p/matrix.mkv", "nur in B");
    }

    @Test
    void findetDoublettenNachDenRegelnDesEinsortierens() throws IOException {
        List<Fund> funde = Abgleich.abgleichen(a, b, false);

        Fund pate = fund(funde, "Der Pate");
        assertEquals(Art.DOUBLETTE, pate.art());
        assertEquals(Wahl.ERSTE_BEHALTEN, pate.vorschlag(), "1080p in A schlägt 720p in B");
        assertEquals(Wahl.ZWEITE_BEHALTEN, fund(funde, "Das Dschungelbuch").vorschlag(), "1080p in B schlägt 720p in A");
        assertEquals(Art.IDENTISCH, fund(funde, "Dark").art(), "Serienfolge, Byte für Byte gleich");
        assertEquals(Wahl.ERSTE_BEHALTEN, fund(funde, "Dark").vorschlag(), "bei identischen Dateien bleibt A");
        assertEquals(Art.FASSUNG, fund(funde, "Der Herr der Ringe").art());
        assertEquals(Wahl.BEIDE_BEHALTEN, fund(funde, "Der Herr der Ringe").vorschlag());
        assertEquals(Art.UNKLAR, fund(funde, "Unklar").art());
        assertEquals(Wahl.OFFEN, fund(funde, "Unklar").vorschlag());

        // Dieselbe Datei wie der Pate in A, in B aber unter ganz anderem Titel.
        Fund kopie = funde.stream().filter(f -> f.zweite().info().titel().equals("Ganz Anderer Titel"))
                .findFirst().orElseThrow();
        assertEquals(Art.IDENTISCH, kopie.art());
        assertEquals("Der Pate", kopie.erste().info().titel());
        assertTrue(funde.stream().noneMatch(f -> f.zweite().info().titel().equals("Matrix")), "nur in B: kein Fund");
        assertEquals(6, funde.size(), funde.toString());
    }

    @Test
    void identischeDateiUnterAnderemTitel() throws IOException {
        einsortieren(a, "Kopie.vom.Paten.2003.German/kopie.mkv", "jungle 1080p");
        Fund fund = Abgleich.abgleichen(a, b, false).stream()
                .filter(f -> f.erste().info().titel().equals("Kopie vom Paten")).findFirst().orElseThrow();
        assertEquals(Art.IDENTISCH, fund.art());
        assertEquals("Das Dschungelbuch", fund.zweite().info().titel());
    }

    @Test
    void schlechtereFassungKommtInDieQuarantaeneIhrerSammlung() throws IOException {
        Fund pate = fund(Abgleich.abgleichen(a, b, false), "Der Pate");
        Path heute = b.resolve(Planer.QUARANTAENE).resolve(LocalDate.now().toString());
        List<Aktion.Verschiebung> v = Abgleich.verschiebungen(pate, Wahl.ERSTE_BEHALTEN);
        assertEquals(1, v.size());
        assertEquals(pate.zweite().video(), v.get(0).von());
        assertTrue(v.get(0).nach().startsWith(heute));
        // Umgekehrt: A abgeben, samt Cover aus dem Filmordner.
        List<Aktion.Verschiebung> umgekehrt = Abgleich.verschiebungen(pate, Wahl.ZWEITE_BEHALTEN);
        assertEquals(2, umgekehrt.size());
        assertTrue(umgekehrt.stream().allMatch(x -> x.nach().startsWith(a.resolve(Planer.QUARANTAENE))));
        assertEquals(List.of(), Abgleich.verschiebungen(pate, Wahl.BEIDE_BEHALTEN));
    }

    @Test
    void aufWunschAuchInSich() throws IOException {
        Path kopie = a.resolve("Pate Kopie (1972)/Pate Kopie (1972).mkv");
        Files.createDirectories(kopie.getParent());
        Files.writeString(kopie, "pate 1080p, die gute Fassung");
        assertTrue(Abgleich.abgleichen(a, b, false).stream().noneMatch(f -> f.zweite().name().equals("A")));
        List<Fund> inSich = Abgleich.abgleichen(a, b, true).stream().filter(f -> f.zweite().name().equals("A")).toList();
        assertEquals(1, inSich.size());
        assertEquals(Art.IDENTISCH, inSich.get(0).art());
    }

    @Test
    void kommandozeileZeigtDieVorschau() throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        Main.abgleichAusgeben(Abgleich.abgleichen(a, b, false), new PrintStream(text, true, "UTF-8"));
        String ausgabe = text.toString("UTF-8");
        assertTrue(ausgabe.contains("Doublette (2)"));
        assertTrue(ausgabe.contains("Vorschlag: 1 behalten"));
        assertTrue(ausgabe.contains("1 A: Der Pate (1972)"));
    }

    private static Fund fund(List<Fund> funde, String titel) {
        return funde.stream().filter(f -> f.erste().info().titel().equals(titel)).findFirst().orElseThrow();
    }

    /** Legt Dateien in einer Quelle an und sortiert sie mit dem Programm in die Sammlung ein. */
    private void einsortieren(Path sammlung, String... pfadeUndInhalte) throws IOException {
        Path quelle = basis.resolve("quelle-" + sammlung.getFileName() + "-" + System.nanoTime());
        for (int i = 0; i < pfadeUndInhalte.length; i += 2) {
            Path datei = quelle.resolve(pfadeUndInhalte[i]);
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, pfadeUndInhalte[i + 1]);
        }
        Ausfuehrung.ausfuehren(Main.planErstellen(quelle, sammlung), sammlung, still);
    }
}
