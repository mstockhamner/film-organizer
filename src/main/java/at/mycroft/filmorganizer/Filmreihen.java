package at.mycroft.filmorganizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Erkennt Filmreihen wie {@code Harry Potter}, {@code Gregs Tagebuch} oder {@code Zurück in die Zukunft}
 * rein an den Titeln, ohne Online-Dienst: Filme gehören zu einer Reihe, wenn ihre Titel mit denselben
 * Wörtern beginnen und danach eine Nummer, ein Trenner oder das Titelende folgt, z.B.
 * {@code Zurück in die Zukunft} und {@code Zurück in die Zukunft II} oder
 * {@code Gregs Tagebuch - Von Idioten umzingelt} und {@code Gregs Tagebuch 2 - Gibt's Probleme}.
 */
public final class Filmreihen {

    /** Wörter, nach denen ein neuer Titelteil beginnt. */
    private static final Set<String> TRENNER = Set.of("-", "–", ":", "&", "teil", "part", "kapitel", "chapter",
            "episode", "episoden");
    /** "Harry Potter und der …"; nicht nach einem Artikel, sonst wäre "Die Schöne und das Biest" eine Reihe. */
    private static final Set<String> UND = Set.of("und", "and");
    private static final Set<String> ARTIKEL = Set.of("der", "die", "das", "the", "a", "an", "ein", "eine", "le",
            "la", "les", "el");
    private static final Pattern NUMMER = Pattern.compile("[0-9]{1,2}|[IVX]{1,5}");

    private Filmreihen() {
    }

    /**
     * Ordnet Titeln den Namen ihrer Filmreihe zu. Schlüssel ist der normalisierte Titel
     * ({@link FilmInfo#normalisieren(String)}); Titel ohne Reihe fehlen im Ergebnis. Gleiche Titel
     * (z.B. Neuverfilmungen) bilden allein keine Reihe.
     */
    public static Map<String, String> erkennen(Collection<String> titel) {
        Map<String, List<String>> eindeutig = new LinkedHashMap<>();
        for (String t : titel) {
            List<String> woerter = woerter(t);
            if (!woerter.isEmpty()) {
                eindeutig.putIfAbsent(FilmInfo.normalisieren(t), woerter);
            }
        }
        // Nur Titel mit demselben ersten Wort können zur selben Reihe gehören.
        Map<String, List<List<String>>> nachErstemWort = new HashMap<>();
        for (List<String> w : eindeutig.values()) {
            nachErstemWort.computeIfAbsent(norm(w.get(0)), k -> new ArrayList<>()).add(w);
        }
        Map<String, String> ergebnis = new HashMap<>();
        for (List<List<String>> gruppe : nachErstemWort.values()) {
            if (gruppe.size() < 2) {
                continue;
            }
            for (List<String> t : gruppe) {
                String reihe = reihe(t, gruppe);
                if (reihe != null) {
                    ergebnis.put(FilmInfo.normalisieren(String.join(" ", t)), reihe);
                }
            }
        }
        // Eine Reihe, ein Name: "Zurück in die Zukunft" und "Zurueck in die Zukunft" sind dieselbe.
        Map<String, String> namen = new HashMap<>();
        for (String name : ergebnis.values()) {
            namen.merge(FilmInfo.normalisieren(name), name, Filmreihen::schoenerer);
        }
        ergebnis.replaceAll((t, name) -> namen.get(FilmInfo.normalisieren(name)));
        return ergebnis;
    }

    /** Bevorzugt die Schreibweise mit Umlauten, sonst die alphabetisch erste, damit das Ergebnis stabil ist. */
    private static String schoenerer(String a, String b) {
        boolean umlautA = !a.chars().allMatch(c -> c < 128);
        boolean umlautB = !b.chars().allMatch(c -> c < 128);
        if (umlautA != umlautB) {
            return umlautA ? a : b;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Kürzester Titelanfang von {@code t}, den mindestens ein anderer Titel gültig teilt. */
    private static String reihe(List<String> t, List<List<String>> gruppe) {
        for (int k = 1; k <= t.size(); k++) {
            if (!gueltig(t, k)) {
                continue;
            }
            for (List<String> s : gruppe) {
                if (s != t && s.size() >= k && gleicherAnfang(t, s, k) && gueltig(s, k)
                        && (k > 1 || mitNummer(t, s))) {
                    return name(t, k);
                }
            }
        }
        return null;
    }

    /** Ob nach den ersten {@code k} Wörtern ein neuer Titelteil beginnt oder der Titel endet. */
    private static boolean gueltig(List<String> t, int k) {
        if (ARTIKEL.contains(norm(t.get(k - 1))) || TRENNER.contains(norm(t.get(k - 1)))) {
            return false; // Ein Reihenname endet nicht mit einem Artikel oder Trenner.
        }
        if (k == t.size()) {
            return true;
        }
        String folgt = t.get(k);
        return NUMMER.matcher(folgt).matches() || TRENNER.contains(norm(folgt))
                || UND.contains(norm(folgt)) && !ARTIKEL.contains(norm(t.get(0)));
    }

    /** Ein einzelnes Wort ("Shrek", "Rocky") reicht nur, wenn ein Titel mit einer Nummer weitergeht. */
    private static boolean mitNummer(List<String> t, List<String> s) {
        boolean tOk = t.size() == 1 || NUMMER.matcher(t.get(1)).matches();
        boolean sOk = s.size() == 1 || NUMMER.matcher(s.get(1)).matches();
        return tOk && sOk && (t.size() > 1 || s.size() > 1);
    }

    private static boolean gleicherAnfang(List<String> a, List<String> b, int k) {
        for (int i = 0; i < k; i++) {
            if (!norm(a.get(i)).equals(norm(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static String name(List<String> t, int k) {
        return String.join(" ", t.subList(0, k));
    }

    private static List<String> woerter(String titel) {
        String bereinigt = Dateinamen.bereinigen(titel);
        return bereinigt.isEmpty() ? List.of() : Arrays.asList(bereinigt.split(" "));
    }

    /** Vergleichsform eines Wortes; Trenner wie "-" bleiben erhalten. */
    private static String norm(String wort) {
        String n = FilmInfo.normalisieren(wort);
        return n.isEmpty() ? wort : n;
    }
}
