package at.mycroft.filmorganizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Metadaten einer Sat-Aufnahme (Enigma2-Receiver), die neben der Videodatei liegen:
 * {@code film.ts.meta} (Text: Sender, Titel, Kurzbeschreibung, …) und {@code film.eit} (DVB-Programmdaten
 * mit Titel, Kurz- und Langbeschreibung). Rein lokal, ändert nichts.
 *
 * @param titel    Titel der Sendung, leer wenn unbekannt
 * @param sender   Sender, z.B. {@code ATV HD}, leer wenn unbekannt
 * @param kurztext Kurzbeschreibung; ihre erste Zeile ist bei Serien meist der Folgentitel
 * @param langtext ausführliche Beschreibung mit Inhalt, Regie, Darstellern
 * @param technik  Bild- und Tonangaben aus dem EIT, z.B. "HD Video", "stereo deutsch"
 */
public record ReceiverDaten(String titel, String sender, String kurztext, String langtext, String technik) {

    static final ReceiverDaten LEER = new ReceiverDaten("", "", "", "", "");

    /** Für Tests und einfache Fälle: Titel, Kurzbeschreibung, ausführliche Beschreibung. */
    public ReceiverDaten(String titel, String kurztext, String langtext) {
        this(titel, "", kurztext, langtext, "");
    }

    /** Länder als Kürzel (USA, D, DEU, DNK/FRA …) oder Name, gefolgt vom Produktionsjahr: "USA 2017". */
    private static final Pattern LAND_JAHR = Pattern.compile(
            "(?<![\\p{L}])(?:USA|GB|UK|D|A|CH|F|I|E|CDN|CAN|AUS|NZ|IRL|J|DK|S|N|B|NL|PL|CZ|RUS|IND|HK|CHN|MEX|BR"
                    + "|DEU|AUT|FRA|DNK|GBR|ITA|ESP|NLD|BEL|SWE|NOR|FIN|POL|CZE|NZL|JPN|KOR|BRA|ARG|ZAF|HKG"
                    + "|Deutschland|Österreich|Schweiz|Frankreich|Großbritannien|England|Kanada|Italien|Spanien|Japan"
                    + "|Dänemark|Schweden|Norwegen|Australien|Irland|Belgien|Niederlande)"
                    + "(?:\\s*[/,]\\s*[\\p{Lu}][\\p{L}]{0,13})*\\s*,?\\s*((?:19|20)[0-9]{2})(?![0-9])");
    private static final Pattern JAHR_ANGABE = Pattern.compile(
            "(?i)(?:Produktionsjahr|Erscheinungsjahr|Jahr)\\s*:?\\s*((?:19|20)[0-9]{2})(?![0-9])");
    /** Hinweise auf eine Folge einer Sendereihe in der Kurzbeschreibung. */
    private static final Pattern REIHE = Pattern.compile(
            "(?i)(?:Doku-?Reihe|Dokureihe|Staffel\\s*\\d|Folge\\s*\\d|^Teil\\s*\\d|\\(\\d+(?:/\\d+)?\\)\\s*$)");
    private static final Pattern DOKU_REIHE = Pattern.compile("(?i)\\s*Doku-?Reihe.*$");

    /** Liest {@code video.meta} und {@code video-ohne-Endung.eit}; fehlt beides, {@link #LEER}. */
    public static ReceiverDaten lesen(Path video) {
        String name = video.getFileName().toString();
        String titel = "";
        String sender = "";
        String kurztext = "";
        String langtext = "";
        String technik = "";

        byte[] meta = bytes(video.resolveSibling(name + ".meta"));
        if (meta != null) {
            String[] zeilen = text(meta).split("\n", -1);
            int dienstEnde = zeilen[0].lastIndexOf("::");
            sender = dienstEnde < 0 ? "" : zeilen[0].substring(dienstEnde + 2).trim();
            if (zeilen.length > 1) {
                titel = zeilen[1].trim();
            }
            if (zeilen.length > 2) {
                kurztext = zeilen[2].replace("\\n", "\n").trim();
            }
        }

        byte[] eit = bytes(video.resolveSibling(Dateinamen.ohneEndung(name) + ".eit"));
        if (eit != null) {
            String[] teile = eitLesen(eit);
            if (titel.isEmpty()) {
                titel = teile[0];
            }
            if (kurztext.isEmpty()) {
                kurztext = teile[1];
            }
            langtext = teile[2];
            technik = teile[3];
            if (teile[0].isEmpty() && teile[1].isEmpty() && teile[2].isEmpty()) {
                // Kein gültiges EIT-Format: als Text durchsuchen.
                langtext = new String(eit, StandardCharsets.ISO_8859_1);
            }
        }
        return new ReceiverDaten(titel, sender, kurztext, langtext, technik);
    }

    /** Erste Zeile der Kurzbeschreibung, bei Serien meist der Folgentitel. */
    public String kurz() {
        return ersteZeile(kurztext);
    }

    /** Alle Beschreibungstexte zusammen. */
    public String beschreibung() {
        return kurztext + "\n" + langtext;
    }

    /**
     * Auflösung einer Aufnahme: 720 bei HD (laut Bildangabe "HD Video" oder Sendername wie "ATV HD"),
     * sonst 576 (SD). So verdrängt eine große Aufnahme mit Werbung keine echte 1080p-Fassung.
     */
    public int aufloesung(String senderAusDateiname) {
        boolean hd = technik.matches("(?is).*\\bHD\\b.*") || sender.matches("(?i).*\\bHD\\b.*")
                || senderAusDateiname.matches("(?i).*\\bHD\\b.*");
        return hd ? 720 : 576;
    }

    /** Produktionsjahr aus der Beschreibung ("Thriller, USA 2017"), höchstens {@code hoechstens}, sonst 0. */
    public int jahr(int hoechstens) {
        for (Pattern muster : List.of(LAND_JAHR, JAHR_ANGABE)) {
            Matcher m = muster.matcher(beschreibung());
            while (m.find()) {
                int jahr = Integer.parseInt(m.group(1));
                if (jahr <= hoechstens) {
                    return jahr;
                }
            }
        }
        return 0;
    }

    /**
     * Folgentitel, wenn die Aufnahme eine Folge einer Sendereihe ist (z.B. "Terra X" mit
     * "Rätselhafte Phänomene - Monsterwellen und Fluggiganten"), sonst {@code null}. Bei Filmen beginnt
     * die Kurzbeschreibung mit dem Titel, mit "(" oder enthält Land und Jahr, oder sie ist ein langer
     * Inhaltstext; eine Folge hat dort einen kurzen eigenen Titel oder Angaben wie "Doku-Reihe", "Teil 4".
     */
    public String folge(String filmtitel) {
        String k = kurz();
        if (k.isEmpty()) {
            return null;
        }
        if (REIHE.matcher(k).find()) {
            String folge = DOKU_REIHE.matcher(k).replaceFirst("").trim();
            return folge.isEmpty() ? k : folge;
        }
        boolean wieFilm = k.startsWith("(") || LAND_JAHR.matcher(k).find()
                || FilmInfo.normalisieren(k).startsWith(FilmInfo.normalisieren(filmtitel));
        return !wieFilm && k.length() <= 100 ? k : null;
    }

    private static String ersteZeile(String text) {
        int ende = text.indexOf('\n');
        return (ende < 0 ? text : text.substring(0, ende)).trim();
    }

    private static byte[] bytes(Path datei) {
        if (!Files.isRegularFile(datei)) {
            return null;
        }
        try (InputStream ein = Files.newInputStream(datei)) {
            return ein.readNBytes(64 * 1024);
        } catch (IOException e) {
            return null;
        }
    }

    /** Text als UTF-8, wenn er gültig ist, sonst als Latin-1. */
    private static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Liest ein Enigma2-EIT: 12 Byte Kopf (Event-ID, Startzeit, Dauer, Länge der Deskriptoren), dann
     * DVB-Deskriptoren. 0x4D (Kurzbeschreibung) liefert Titel und Kurztext, 0x4E (erweiterte
     * Beschreibung, auf mehrere Deskriptoren verteilt) den Langtext, 0x50 (Komponenten) Bild und Ton.
     *
     * @return Titel, Kurztext, Langtext, Technik (leer, wenn nicht vorhanden)
     */
    static String[] eitLesen(byte[] b) {
        String titel = "";
        String kurz = "";
        StringBuilder lang = new StringBuilder();
        StringBuilder technik = new StringBuilder();
        int pos = 12;
        while (pos + 2 <= b.length) {
            int tag = b[pos] & 0xff;
            int laenge = b[pos + 1] & 0xff;
            int start = pos + 2;
            if (start + laenge > b.length) {
                break;
            }
            if (tag == 0x4D && laenge >= 5) {
                int namensLaenge = b[start + 3] & 0xff;
                int textPos = start + 4 + namensLaenge;
                if (textPos < start + laenge) {
                    titel = dvbText(b, start + 4, namensLaenge);
                    kurz = dvbText(b, textPos + 1, Math.min(b[textPos] & 0xff, start + laenge - textPos - 1));
                }
            } else if (tag == 0x4E && laenge >= 6) {
                int textPos = start + 5 + (b[start + 4] & 0xff);
                if (textPos < start + laenge) {
                    lang.append(dvbText(b, textPos + 1, Math.min(b[textPos] & 0xff, start + laenge - textPos - 1)));
                }
            } else if (tag == 0x50 && laenge > 6) {
                // Komponente (Bild/Ton): Typ, Tag, Sprache, dann Text wie "HD Video".
                technik.append(dvbText(b, start + 6, laenge - 6)).append('\n');
            }
            pos = start + laenge;
        }
        return new String[] {titel.trim(), kurz.trim(), lang.toString().trim(), technik.toString().trim()};
    }

    /**
     * DVB-Zeichenkette (EN 300 468, Anhang A): ein Startbyte unter 0x20 wählt den Zeichensatz
     * (0x15 UTF-8, 0x01–0x0B ISO-8859-5 bis -15, 0x10 mit Nummer ISO-8859-n); ohne Startbyte ist es
     * ISO 6937, hier näherungsweise Latin-1. 0x8A ist ein Zeilenumbruch.
     */
    static String dvbText(byte[] b, int start, int laenge) {
        if (laenge <= 0 || start + laenge > b.length) {
            return "";
        }
        int erstes = b[start] & 0xff;
        Charset zeichensatz = StandardCharsets.ISO_8859_1;
        int text = start;
        if (erstes == 0x15) {
            zeichensatz = StandardCharsets.UTF_8;
            text = start + 1;
        } else if (erstes >= 0x01 && erstes <= 0x0B) {
            zeichensatz = zeichensatz("ISO-8859-" + (erstes + 4));
            text = start + 1;
        } else if (erstes == 0x10 && laenge >= 3) {
            zeichensatz = zeichensatz("ISO-8859-" + (b[start + 2] & 0xff));
            text = start + 3;
        } else if (erstes == 0x11) {
            zeichensatz = StandardCharsets.UTF_16BE;
            text = start + 1;
        }
        byte[] roh = new byte[start + laenge - text];
        System.arraycopy(b, text, roh, 0, roh.length);
        if (zeichensatz != StandardCharsets.UTF_8 && zeichensatz != StandardCharsets.UTF_16BE) {
            for (int i = 0; i < roh.length; i++) {
                if ((roh[i] & 0xff) == 0x8A) {
                    roh[i] = '\n';
                }
            }
        }
        return new String(roh, zeichensatz).replace("\\n", "\n").replaceAll("[\\p{Cc}&&[^\n]]", "");
    }

    private static Charset zeichensatz(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
