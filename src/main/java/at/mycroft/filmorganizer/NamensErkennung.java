package at.mycroft.filmorganizer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Titel, Jahr und Auflösung aus Ordner- und Dateinamen, wie sie bei Filmsammlungen
 * üblich sind, z.B. {@code Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ}
 * oder {@code (2011) Der gestiefelte Kater (1080p)}. Arbeitet rein lokal auf dem Namen.
 */
public final class NamensErkennung {

    private static final Pattern JAHR = Pattern.compile("(?<![0-9A-Za-z])(19[0-9]{2}|20[0-9]{2})(?![0-9A-Za-z])");
    private static final Pattern AUFLOESUNG = Pattern.compile(
            "(?i)(?<![0-9A-Za-z])(?:(480|576|720|1080|2160)[pi]|(4k|uhd))(?![0-9A-Za-z])");
    private static final Pattern WORT = Pattern.compile("[0-9A-Za-zÄÖÜäöüß]+");
    private static final Pattern FOLGE = Pattern.compile("(?i)(?<![0-9A-Za-z])S([0-9]{1,2}) ?E([0-9]{1,3})(?![0-9])");
    private static final Pattern STAFFEL = Pattern.compile(
            "(?i)(?<![0-9A-Za-z])(?:S|Staffel |Season )([0-9]{1,2})(?![0-9A-Za-z])");
    private static final Pattern TITEL_JAHR = Pattern.compile("^(.+) ((?:19|20)[0-9]{2})$");
    /** Aufnahme eines Sat-Receivers: Datum, Uhrzeit, Sender, Titel, z.B. {@code 20200612 2226 - ATV HD - Titel}. */
    private static final Pattern AUFNAHME = Pattern.compile(
            "^((?:19|20)[0-9]{2})([0-9]{2})([0-9]{2}) ([0-9]{4}) - (.+?) - (.+)$");
    /** Film einer Reihe mit Nummer: {@code Star Trek - 04 - Zurück in die Gegenwart}. */
    private static final Pattern REIHENFILM = Pattern.compile("^(.+?) - ([0-9]{1,2}) - (.+)$");

    /** Teil einer gestückelten Aufnahme wie {@code …_001}. */
    private static final Pattern TEIL = Pattern.compile("_[0-9]{3}$");

    /**
     * Was im Namen einer Sat-Aufnahme steht.
     *
     * @param titel  Titel der Sendung
     * @param sender Sender, z.B. {@code ATV HD}
     * @param datum  Tag der Aufnahme (nicht das Erscheinungsjahr des Films)
     */
    public record Aufnahme(String titel, String sender, LocalDate datum) {
    }

    /** Angaben zu Sprache, Ton, Quelle und Codec, die nicht zum Titel gehören. */
    private static final Set<String> TECHNIK = Set.of(
            "german", "deutsch", "english", "dl", "ml", "dubbed", "subbed", "ac3", "ac3d", "ac3md", "dts", "dtshd",
            "dd51", "aac", "truehd", "atmos", "bluray", "bd", "bdrip", "brrip", "webrip", "webdl", "hdtv", "dvdrip",
            "dvd", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "remux", "hdr", "uhd", "4k",
            "extended", "unrated", "uncut", "directors", "kinofassung", "theatrical", "proper", "repack", "internal",
            "hd");

    /** Schnittfassungen; Kinofassung und Theatrical stehen für die normale Fassung. */
    private static final Pattern FASSUNG = Pattern.compile(
            "(?i)(?<![0-9A-Za-z])(extended|unrated|uncut|director'?s cut|directors|final cut|kinofassung|theatrical)"
                    + "(?![0-9A-Za-z])");

    private NamensErkennung() {
    }

    /**
     * Erkennt Titel und Jahr. Ohne erkennbares Jahr gibt es kein Ergebnis, weil das
     * Zielschema {@code Titel (Jahr)} das Jahr braucht.
     */
    public static Optional<FilmInfo> erkennen(String name) {
        Optional<FilmInfo> reihenfilm = erkennenReihenfilm(name);
        if (reihenfilm.isPresent()) {
            return reihenfilm.filter(info -> info.jahr() > 0);
        }
        String text = vereinfachen(name);
        int aufloesung = aufloesung(text);

        // Alles ab der Auflösungsangabe ist Technik, auch ein Gruppenname wie "x264-GRUPPE2020".
        Matcher aufl = AUFLOESUNG.matcher(text);
        String kopf = aufl.find() ? text.substring(0, aufl.start()) : text;

        List<MatchResult> jahre = JAHR.matcher(kopf).results()
                .filter(t -> Integer.parseInt(t.group(1)) <= Year.now().getValue() + 1)
                .toList();
        if (jahre.isEmpty()) {
            return Optional.empty();
        }

        // Normalfall "Titel Jahr Technik": das letzte Jahr, vor dem ein Titel steht.
        // So bleiben Jahreszahlen im Titel erhalten ("Blade Runner 2049 2017", "1917 2019").
        for (int i = jahre.size() - 1; i >= 0; i--) {
            MatchResult jahr = jahre.get(i);
            String davor = kopf.substring(0, jahr.start());
            String titel = aufraeumen(davor);
            if (!titel.isEmpty() && !enthaeltTechnik(davor)) {
                return Optional.of(new FilmInfo(titel, Integer.parseInt(jahr.group(1)), aufloesung, 0, 0, fassung(text)));
            }
        }

        // Sonderfall "(Jahr) Titel Technik": Titel steht hinter dem Jahr.
        MatchResult jahr = jahre.get(0);
        String titel = aufraeumen(bisZurTechnik(kopf.substring(jahr.end())));
        if (titel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FilmInfo(titel, Integer.parseInt(jahr.group(1)), aufloesung, 0, 0, fassung(text)));
    }

    /**
     * Erkennt einen Film einer Reihe nach dem Schema {@code Reihe - Nummer - Titel}, z.B.
     * {@code Star Trek - 04 - Zurück in die Gegenwart}: Titel "Zurück in die Gegenwart" in der Filmreihe
     * "Star Trek". Ein Jahr am Ende ({@code (1986)} oder {@code 1986}) wird übernommen, sonst ist es 0 und
     * wird in der Vorschau nachgetragen. Technik-Angaben wie {@code 1080p} werden abgeschnitten.
     */
    public static Optional<FilmInfo> erkennenReihenfilm(String name) {
        String text = vereinfachen(name);
        Matcher m = REIHENFILM.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        String reihe = aufraeumen(m.group(1));
        String rest = m.group(3);
        Matcher aufl = AUFLOESUNG.matcher(rest);
        String kopf = aufl.find() ? rest.substring(0, aufl.start()) : rest;
        String titel = aufraeumen(bisZurTechnik(kopf));
        int jahr = 0;
        Matcher mitJahr = TITEL_JAHR.matcher(titel);
        if (mitJahr.matches() && Integer.parseInt(mitJahr.group(2)) <= Year.now().getValue() + 1) {
            titel = aufraeumen(mitJahr.group(1));
            jahr = Integer.parseInt(mitJahr.group(2));
        }
        if (reihe.isEmpty() || titel.isEmpty() || reihe.matches("[0-9 ]+")) {
            return Optional.empty();
        }
        return Optional.of(new FilmInfo(titel, jahr, aufloesung(text), 0, 0, fassung(text), reihe)
                .mitNummer(Integer.parseInt(m.group(2))));
    }

    /**
     * Erkennt den Namen einer Sat-Receiver-Aufnahme (Enigma2 u.ä.):
     * {@code 20200612 2226 - ATV HD - The Greatest Showman}, auch mit Unterstrichen statt Leerzeichen
     * und mit Teilnummer ({@code _001}).
     */
    public static Optional<Aufnahme> erkennenAufnahme(String name) {
        String text = TEIL.matcher(name.trim()).replaceFirst("").replace('_', ' ').replaceAll("\\s+", " ").trim();
        Matcher m = AUFNAHME.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        LocalDate datum;
        try {
            datum = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        String titel = aufraeumen(m.group(6));
        return titel.isEmpty() ? Optional.empty() : Optional.of(new Aufnahme(titel, m.group(5).trim(), datum));
    }

    /**
     * Schnittfassung aus dem Namen, z.B. {@code Extended} oder {@code Director's Cut}; leer für die
     * Kinofassung oder wenn nichts angegeben ist.
     */
    public static String fassung(String name) {
        Matcher m = FASSUNG.matcher(vereinfachen(name));
        if (!m.find()) {
            return "";
        }
        String wort = m.group(1).toLowerCase(Locale.ROOT);
        return switch (wort.substring(0, 3)) {
            case "ext" -> "Extended";
            case "unr" -> "Unrated";
            case "unc" -> "Uncut";
            case "dir" -> "Director's Cut";
            case "fin" -> "Final Cut";
            default -> "";
        };
    }

    /**
     * Erkennt Serienangaben: eine Folge wie {@code Breaking.Bad.S01E02.German.1080p} oder einen
     * Staffelordner wie {@code Breaking.Bad.S01.German.1080p} bzw. {@code Staffel 1} (dann ist die
     * Folge 0). Der Serientitel steht vor der Kennung und ist leer, wenn dort nichts steht. Eine
     * Jahreszahl am Ende des Titels wird als Jahr abgetrennt ({@code Doctor.Who.2005.S01E01}).
     */
    public static Optional<FilmInfo> erkennenSerie(String name) {
        String text = vereinfachen(name);
        Matcher folge = FOLGE.matcher(text);
        if (folge.find()) {
            return Optional.of(serie(text.substring(0, folge.start()), aufloesung(text),
                    Integer.parseInt(folge.group(1)), Integer.parseInt(folge.group(2))));
        }
        Matcher staffel = STAFFEL.matcher(text);
        if (staffel.find()) {
            return Optional.of(serie(text.substring(0, staffel.start()), aufloesung(text),
                    Integer.parseInt(staffel.group(1)), 0));
        }
        return Optional.empty();
    }

    /** Serientitel aus einem Namen ohne Kennung, z.B. dem Ordner {@code Breaking.Bad.German.1080p}. */
    public static FilmInfo serienTitel(String name) {
        String text = vereinfachen(name);
        Matcher aufl = AUFLOESUNG.matcher(text);
        return serie(aufl.find() ? text.substring(0, aufl.start()) : text, aufloesung(text), 0, 0);
    }

    private static FilmInfo serie(String davor, int aufloesung, int staffel, int folge) {
        String titel = aufraeumen(bisZurTechnik(davor));
        int jahr = 0;
        Matcher m = TITEL_JAHR.matcher(titel);
        if (m.matches()) {
            titel = aufraeumen(m.group(1));
            jahr = Integer.parseInt(m.group(2));
        }
        return new FilmInfo(titel, jahr, aufloesung, staffel, folge);
    }

    private static String vereinfachen(String name) {
        return name.replaceAll("[._]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Vertikale Auflösung aus dem Namen, 0 wenn keine angegeben ist. */
    static int aufloesung(String text) {
        Matcher m = AUFLOESUNG.matcher(text);
        if (!m.find()) {
            return 0;
        }
        return m.group(1) != null ? Integer.parseInt(m.group(1)) : 2160;
    }

    private static boolean enthaeltTechnik(String text) {
        return WORT.matcher(text).results().anyMatch(w -> istTechnik(w.group()));
    }

    private static String bisZurTechnik(String text) {
        Matcher m = WORT.matcher(text);
        while (m.find()) {
            if (istTechnik(m.group())) {
                return text.substring(0, m.start());
            }
        }
        return text;
    }

    private static boolean istTechnik(String wort) {
        return TECHNIK.contains(wort.toLowerCase(Locale.ROOT));
    }

    private static String aufraeumen(String text) {
        return text.replaceAll("[\\[\\](){}]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-–,:;]+|[\\s\\-–,:;]+$", "")
                .trim();
    }
}
