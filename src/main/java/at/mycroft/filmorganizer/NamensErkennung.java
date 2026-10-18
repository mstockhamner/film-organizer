package at.mycroft.filmorganizer;

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

    /** Angaben zu Sprache, Ton, Quelle und Codec, die nicht zum Titel gehören. */
    private static final Set<String> TECHNIK = Set.of(
            "german", "deutsch", "english", "dl", "ml", "dubbed", "subbed", "ac3", "ac3d", "ac3md", "dts", "dtshd",
            "dd51", "aac", "truehd", "atmos", "bluray", "bd", "bdrip", "brrip", "webrip", "webdl", "hdtv", "dvdrip",
            "dvd", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx", "remux", "hdr", "uhd", "4k",
            "extended", "unrated", "uncut", "proper", "repack", "internal", "hd");

    private NamensErkennung() {
    }

    /**
     * Erkennt Titel und Jahr. Ohne erkennbares Jahr gibt es kein Ergebnis, weil das
     * Zielschema {@code Titel (Jahr)} das Jahr braucht.
     */
    public static Optional<FilmInfo> erkennen(String name) {
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
                return Optional.of(new FilmInfo(titel, Integer.parseInt(jahr.group(1)), aufloesung));
            }
        }

        // Sonderfall "(Jahr) Titel Technik": Titel steht hinter dem Jahr.
        MatchResult jahr = jahre.get(0);
        String titel = aufraeumen(bisZurTechnik(kopf.substring(jahr.end())));
        if (titel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FilmInfo(titel, Integer.parseInt(jahr.group(1)), aufloesung));
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
