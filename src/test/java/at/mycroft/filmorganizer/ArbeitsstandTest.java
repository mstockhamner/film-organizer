package at.mycroft.filmorganizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.mycroft.filmorganizer.Aktion.Art;
import at.mycroft.filmorganizer.Aktion.Entscheidung;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Änderungen in der Liste werden gespeichert und beim nächsten Einlesen wiederhergestellt. */
class ArbeitsstandTest {

    @TempDir
    Path basis;

    Path quelle;
    Path ziel;
    Path speicher;
    Path unbekannt;
    Path pate;
    Path showman;

    @BeforeEach
    void anlegen() throws IOException {
        quelle = basis.resolve("Quelle");
        ziel = basis.resolve("Sammlung");
        speicher = basis.resolve("arbeitsstaende");
        unbekannt = datei("Unbekannt/irgendwas.mkv");
        pate = datei("Der.Pate.1972.German.1080p/pate.mkv");
        showman = datei("movie/20200612 2226 - ATV HD - The Greatest Showman.ts");
    }

    @Test
    void aenderungenUeberlebenEinNeuesEinlesen() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel, speicher);
        assertEquals(0, v.anzahlAenderungen());
        v.korrigieren(unbekannt, "Der Pate 2", 0); // noch unvollständig
        v.korrigieren(showman, "The Greatest Showman", 2017);
        v.auslassen(pate, true);
        v.reiheSetzen(showman, "Musicals");

        Vorschlaege spaeter = Vorschlaege.einlesen(quelle, ziel, speicher);
        assertEquals(3, spaeter.anzahlAenderungen());
        List<Aktion> plan = spaeter.plan();
        assertEquals("Der Pate 2", spaeter.titel(film(plan, unbekannt)));
        assertEquals(0, spaeter.jahr(film(plan, unbekannt)));
        assertTrue(spaeter.istAusgelassen(pate));
        assertEquals(Art.AUSGELASSEN, aktion(plan, pate).art());
        assertEquals(ziel.resolve("Musicals/The Greatest Showman (2017)/The Greatest Showman (2017).ts"),
                aktion(plan, showman).verschiebungen().get(0).nach());

        // Weiter vervollständigen und wieder einlesen.
        spaeter.korrigieren(unbekannt, "Der Pate 2", 1974);
        assertEquals(Art.EINSORTIEREN, aktion(Vorschlaege.einlesen(quelle, ziel, speicher).plan(), unbekannt).art());
    }

    @Test
    void entscheidungenWerdenGespeichert() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel, speicher);
        v.entscheiden(pate, Entscheidung.QUARANTAENE);
        assertEquals(Entscheidung.QUARANTAENE, Vorschlaege.einlesen(quelle, ziel, speicher).entscheidung(pate));
    }

    @Test
    void anderesZielHatEinenEigenenArbeitsstand() throws IOException {
        Vorschlaege.einlesen(quelle, ziel, speicher).auslassen(pate, true);
        assertEquals(0, Vorschlaege.einlesen(quelle, basis.resolve("Andere Sammlung"), speicher).anzahlAenderungen());
        assertEquals(0, Vorschlaege.einlesen(quelle, ziel).anzahlAenderungen(), "ohne Speicherordner nichts laden");
    }

    @Test
    void nachDemEinsortierenBleibtNurDasUnerledigte() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel, speicher);
        v.korrigieren(unbekannt, "Der Pate 2", 0);
        v.korrigieren(showman, "The Greatest Showman", 2017);
        List<Aktion> plan = v.plan();
        Ausfuehrung.ausfuehren(plan, ziel, new PrintStream(new ByteArrayOutputStream()));
        v.nachEinsortieren(plan);

        Vorschlaege danach = Vorschlaege.einlesen(quelle, ziel, speicher);
        assertEquals(1, danach.anzahlAenderungen());
        assertTrue(danach.istKorrigiert(unbekannt));
        assertFalse(danach.istKorrigiert(showman));
    }

    @Test
    void verwerfenLoeschtDenArbeitsstand() throws IOException {
        Vorschlaege v = Vorschlaege.einlesen(quelle, ziel, speicher);
        v.auslassen(pate, true);
        v.verwerfen();
        assertEquals(0, Vorschlaege.einlesen(quelle, ziel, speicher).anzahlAenderungen());
    }

    @Test
    void imZielWirdVorDemEinsortierenNichtsAngelegt() throws IOException {
        Vorschlaege.einlesen(quelle, ziel, speicher).auslassen(pate, true);
        assertFalse(Files.exists(ziel));
        assertTrue(Files.exists(Vorschlaege.arbeitsstandDatei(speicher, quelle, ziel)));
    }

    private static Aktion aktion(List<Aktion> plan, Path video) {
        return plan.stream().filter(a -> a.film().video().equals(video)).findFirst().orElseThrow();
    }

    private static Film film(List<Aktion> plan, Path video) {
        return aktion(plan, video).film();
    }

    private Path datei(String pfad) throws IOException {
        Path datei = quelle.resolve(pfad);
        Files.createDirectories(datei.getParent());
        Files.writeString(datei, pfad);
        return datei.toAbsolutePath().normalize();
    }
}
