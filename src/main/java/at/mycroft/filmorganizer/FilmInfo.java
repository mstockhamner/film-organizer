package at.mycroft.filmorganizer;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Was aus einem Ordner- oder Dateinamen über einen Film oder eine Serienfolge herausgelesen wurde.
 *
 * @param titel      Titel des Films oder der Serie so, wie er im Namen steht (keine Übersetzung)
 * @param jahr       Erscheinungsjahr, bei Serienfolgen 0 wenn unbekannt
 * @param aufloesung vertikale Auflösung in Pixeln (z.B. 1080), 0 wenn unbekannt
 * @param staffel    Staffel einer Serienfolge ({@code S01} = 1), 0 bei Filmen
 * @param folge      Folge innerhalb der Staffel ({@code E02} = 2), 0 bei Filmen
 * @param fassung    Schnittfassung wie {@code Extended} oder {@code Director's Cut}, leer für die Kinofassung
 * @param reihe      Filmreihe wie {@code Harry Potter}, in deren Ordner der Film liegt; leer, wenn er zu keiner gehört
 * @param folgentitel Titel einer Folge einer Sendereihe ohne Staffel- und Folgennummer, z.B. bei Terra X
 *                    "Eine Erde - viele Welten - Inseln"; leer bei Filmen und nummerierten Folgen
 * @param nummer     Nummer eines Films in seiner Reihe ({@code Star Trek - 04 - …} = 4), 0 wenn keine; steht im Ordnernamen
 */
public record FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge, String fassung, String reihe,
        String folgentitel, int nummer) {

    /** Unterordner im Ziel, in dem die Serien liegen. */
    static final String SERIEN = "Serien";

    public FilmInfo(String titel, int jahr, int aufloesung) {
        this(titel, jahr, aufloesung, 0, 0, "", "", "", 0);
    }

    public FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge) {
        this(titel, jahr, aufloesung, staffel, folge, "", "", "", 0);
    }

    public FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge, String fassung) {
        this(titel, jahr, aufloesung, staffel, folge, fassung, "", "", 0);
    }

    public FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge, String fassung, String reihe) {
        this(titel, jahr, aufloesung, staffel, folge, fassung, reihe, "", 0);
    }

    public FilmInfo(String titel, int jahr, int aufloesung, int staffel, int folge, String fassung, String reihe,
            String folgentitel) {
        this(titel, jahr, aufloesung, staffel, folge, fassung, reihe, folgentitel, 0);
    }

    /** Folge einer Sendereihe mit Folgentitel statt Nummer. */
    public static FilmInfo sendung(String titel, String folgentitel) {
        return new FilmInfo(titel, 0, 0, 0, 0, "", "", folgentitel);
    }

    /** Dieselbe Angabe mit einer anderen Fassung. */
    public FilmInfo mitFassung(String andereFassung) {
        return new FilmInfo(titel, jahr, aufloesung, staffel, folge, andereFassung, reihe, folgentitel, nummer);
    }

    /** Dieselbe Angabe in einer anderen Filmreihe (leer: in keiner). */
    public FilmInfo mitReihe(String andereReihe) {
        return new FilmInfo(titel, jahr, aufloesung, staffel, folge, fassung, andereReihe, folgentitel, nummer);
    }

    /** Dieselbe Angabe mit von Hand korrigiertem Titel und Jahr. */
    public FilmInfo mitTitelUndJahr(String andererTitel, int anderesJahr) {
        return new FilmInfo(andererTitel, anderesJahr, aufloesung, staffel, folge, fassung, reihe, folgentitel, nummer);
    }

    /** Dieselbe Angabe mit einer anderen Nummer in der Reihe. */
    public FilmInfo mitNummer(int andereNummer) {
        return new FilmInfo(titel, jahr, aufloesung, staffel, folge, fassung, reihe, folgentitel, andereNummer);
    }

    /** Dieselbe Angabe mit anderer Auflösung und Fassung. */
    public FilmInfo mitAufloesungUndFassung(int andereAufloesung, String andereFassung) {
        return new FilmInfo(titel, jahr, andereAufloesung, staffel, folge, andereFassung, reihe, folgentitel, nummer);
    }

    /** Serienfolge statt Film: nummeriert ({@code S01E02}) oder als Folge einer Sendereihe mit Folgentitel. */
    public boolean istFolge() {
        return folge > 0 || istSendung();
    }

    /** Folge einer Sendereihe mit Folgentitel statt Nummer. */
    public boolean istSendung() {
        return !folgentitel.isEmpty();
    }

    /** Kennung wie {@code S01E02}. */
    public String kennung() {
        return String.format(Locale.ROOT, "S%02dE%02d", staffel, folge);
    }

    /**
     * Name der Zieldatei ohne Endung: {@code Titel (Jahr)} bei Filmen, mit Fassung
     * {@code Titel (Jahr) - Extended}, {@code Titel - S01E02} bei Serienfolgen und {@code Titel - Folgentitel}
     * bei Folgen einer Sendereihe. So können mehrere Fassungen eines Films im selben Ordner liegen.
     */
    public String zielName() {
        if (istSendung()) {
            return Dateinamen.bereinigen(titel) + " - " + Dateinamen.bereinigen(folgentitel);
        }
        if (istFolge()) {
            return Dateinamen.bereinigen(titel) + " - " + kennung();
        }
        return fassung.isEmpty() ? titelUndJahr() : titelUndJahr() + " - " + Dateinamen.bereinigen(fassung);
    }

    private String titelUndJahr() {
        return Dateinamen.bereinigen(titel) + " (" + jahr + ")";
    }

    /** Ordner eines Films: {@code Titel (Jahr)}, in einer Reihe mit Nummer {@code 04 - Titel (Jahr)}. */
    private String ordnerName() {
        return nummer > 0 && !reihe.isEmpty()
                ? String.format(Locale.ROOT, "%02d - %s", nummer, titelUndJahr()) : titelUndJahr();
    }

    /**
     * {@code Ziel/Titel (Jahr)} bei Filmen, {@code Ziel/Reihe/Titel (Jahr)} bei Filmen einer Filmreihe,
     * {@code Ziel/Serien/Titel/Staffel 01} bei Serienfolgen und {@code Ziel/Serien/Titel} bei Folgen einer
     * Sendereihe.
     */
    public Path zielOrdner(Path ziel) {
        if (istSendung()) {
            return ziel.resolve(SERIEN).resolve(Dateinamen.bereinigen(titel));
        }
        if (istFolge()) {
            return ziel.resolve(SERIEN).resolve(Dateinamen.bereinigen(titel))
                    .resolve(String.format(Locale.ROOT, "Staffel %02d", staffel));
        }
        return reihe.isEmpty() ? ziel.resolve(ordnerName()) : ziel.resolve(Dateinamen.bereinigen(reihe)).resolve(ordnerName());
    }

    /**
     * Vergleichsschlüssel für die Doublettenprüfung: Groß-/Kleinschreibung, Akzente,
     * Umlaute und Satzzeichen spielen keine Rolle. Bei Serienfolgen zählen Titel, Staffel und
     * Folge, bei Folgen einer Sendereihe Titel und Folgentitel, nicht das Jahr.
     */
    public String schluessel() {
        if (istSendung()) {
            return normalisieren(titel) + "|folge|" + normalisieren(folgentitel);
        }
        return istFolge()
                ? normalisieren(titel) + "|" + kennung()
                : normalisieren(titel) + "|" + jahr;
    }

    /** Schlüssel für Film und Fassung: Extended und Kinofassung desselben Films sind verschieden. */
    public String fassungsSchluessel() {
        return schluessel() + "|" + normalisieren(fassung);
    }

    static String normalisieren(String text) {
        String ohneUmlaute = text.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", "and");
        String ohneAkzente = Normalizer.normalize(ohneUmlaute, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ohneAkzente.replaceAll("[^a-z0-9]", "");
    }
}
