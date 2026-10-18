package at.mycroft.filmorganizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erstellt eine .nfo-Datei im Kodi-Format aus den Receiver-Daten einer Sat-Aufnahme: Titel, Jahr,
 * Inhalt, Genre, Regie, Drehbuch und Darsteller. Kodi, Jellyfin und Plex lesen sie lokal, ohne
 * Internet. Filme werden {@code <movie>}, Serienfolgen {@code <episodedetails>}.
 */
public final class Nfo {

    /** Genre vor Land und Jahr: "Thriller, USA 2017", "(Originaltitel - Zeichentrickfilm, DNK/FRA 2006)". */
    private static final Pattern GENRE = Pattern.compile(
            "([\\p{L}][\\p{L}\\- ]{1,60}),\\s*[\\p{Lu}][\\p{L}/ ,]{0,30}?\\s?(?:19|20)[0-9]{2}(?![0-9])");
    /** Zwei Wörter ohne Leerzeichen zusammengeklebt, wie im EPG mancher Sender: "zurückThriller". */
    private static final Pattern GEKLEBT = Pattern.compile("\\p{Ll}(\\p{Lu}[\\p{L}\\-]+)$");
    private static final Pattern ROLLE = Pattern.compile("^(.+?)\\s*\\((.+)\\)$");

    private Nfo() {
    }

    /** Inhalt der .nfo für einen Film oder eine Folge; {@code aufgenommen} ist der Tag der Aufnahme. */
    public static String erstellen(FilmInfo info, ReceiverDaten daten, LocalDate aufgenommen) {
        String lang = daten.langtext().isBlank() ? "" : daten.langtext();
        String inhalt = inhalt(lang.isEmpty() ? daten.kurztext() : lang);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        if (info.istFolge()) {
            xml.append("<episodedetails>\n");
            element(xml, "title", info.istSendung() ? info.folgentitel() : info.titel() + " " + info.kennung());
            element(xml, "showtitle", info.titel());
            if (info.folge() > 0) {
                element(xml, "season", Integer.toString(info.staffel()));
                element(xml, "episode", Integer.toString(info.folge()));
            }
            element(xml, "plot", inhalt);
            if (aufgenommen != null) {
                element(xml, "aired", aufgenommen.toString());
            }
            mitwirkende(xml, lang);
            xml.append("</episodedetails>\n");
        } else {
            xml.append("<movie>\n");
            element(xml, "title", info.titel());
            if (info.jahr() > 0) {
                element(xml, "year", Integer.toString(info.jahr()));
            }
            element(xml, "plot", inhalt);
            String genre = genre(daten.kurztext() + "\n" + lang);
            if (!genre.isEmpty()) {
                element(xml, "genre", genre);
            }
            mitwirkende(xml, lang);
            xml.append("</movie>\n");
        }
        return xml.toString();
    }

    /** Inhaltsangabe: der Text vor den Angaben zu Regie, Drehbuch und Darstellern. */
    static String inhalt(String text) {
        List<String> zeilen = new ArrayList<>();
        for (String zeile : text.split("\n")) {
            if (zeile.matches("(?i)\\s*(Regie|Drehbuch|Buch|Komponist|Musik|Kamera|Schnitt|Darsteller|Mit)\\s*:.*")) {
                break;
            }
            zeilen.add(zeile.trim());
        }
        return String.join("\n", zeilen).trim();
    }

    /** Genre aus "Thriller, USA 2017" o.ä.; leer, wenn keins angegeben ist. */
    static String genre(String text) {
        Matcher m = GENRE.matcher(text);
        if (!m.find()) {
            return "";
        }
        String genre = m.group(1);
        int strich = genre.lastIndexOf(" - ");
        genre = (strich >= 0 ? genre.substring(strich + 3) : genre).trim();
        int leer = genre.lastIndexOf(' ');
        genre = leer >= 0 ? genre.substring(leer + 1) : genre;
        Matcher geklebt = GEKLEBT.matcher(genre);
        return geklebt.find() ? geklebt.group(1) : genre;
    }

    private static void mitwirkende(StringBuilder xml, String text) {
        for (String name : liste(text, "Regie")) {
            element(xml, "director", name);
        }
        for (String name : liste(text, "Drehbuch")) {
            element(xml, "credits", name);
        }
        for (String[] darsteller : darsteller(text)) {
            xml.append("  <actor>\n");
            xml.append("  ");
            element(xml, "name", darsteller[0]);
            if (!darsteller[1].isEmpty()) {
                xml.append("  ");
                element(xml, "role", darsteller[1]);
            }
            xml.append("  </actor>\n");
        }
    }

    /** Namen nach "Regie: A, B" in einer Zeile. */
    static List<String> liste(String text, String angabe) {
        for (String zeile : text.split("\n")) {
            Matcher m = Pattern.compile("(?i)^\\s*" + angabe + "\\s*:\\s*(.+)$").matcher(zeile);
            if (m.matches()) {
                return List.of(m.group(1).trim().split("\\s*,\\s*"));
            }
        }
        return List.of();
    }

    /** Darsteller mit Rolle, untereinander nach "Darsteller:" oder hintereinander in der Zeile. */
    static List<String[]> darsteller(String text) {
        List<String[]> ergebnis = new ArrayList<>();
        String[] zeilen = text.split("\n");
        for (int i = 0; i < zeilen.length; i++) {
            Matcher kopf = Pattern.compile("(?i)^\\s*Darsteller\\s*:\\s*(.*)$").matcher(zeilen[i]);
            if (!kopf.matches()) {
                continue;
            }
            List<String> eintraege = new ArrayList<>();
            if (!kopf.group(1).isBlank()) {
                eintraege.addAll(List.of(kopf.group(1).split("\\s*,\\s*(?![^()]*\\))")));
            }
            for (int j = i + 1; j < zeilen.length && !zeilen[j].isBlank(); j++) {
                eintraege.add(zeilen[j]);
            }
            for (String eintrag : eintraege) {
                Matcher m = ROLLE.matcher(eintrag.trim());
                ergebnis.add(m.matches() ? new String[] {m.group(1).trim(), m.group(2).trim()}
                        : new String[] {eintrag.trim(), ""});
            }
            break;
        }
        return ergebnis;
    }

    private static void element(StringBuilder xml, String name, String wert) {
        xml.append("  <").append(name).append('>').append(xml(wert)).append("</").append(name).append(">\n");
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
