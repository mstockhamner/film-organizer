package at.mycroft.filmorganizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Von Hand festgelegte Filmreihen, die bei jedem Lauf vor der automatischen Erkennung gelten.
 *
 * <p>Gespeichert lokal in {@code <Ziel>/.filmorganizer/filmreihen.tsv}, eine Zeile pro Film mit Titel,
 * Jahr und Filmreihe, getrennt durch Tabulatoren. Eine leere Filmreihe heißt "gehört zu keiner Reihe".
 * Die Datei lässt sich auch von Hand bearbeiten; Zeilen mit {@code #} am Anfang sind Kommentare.
 */
public final class Reihenzuordnung {

    static final String DATEI = "filmreihen.tsv";
    private static final String KOPF = "# Titel\tJahr\tFilmreihe (leer = keine Reihe)";

    private record Zeile(String titel, int jahr, String reihe) {
    }

    private final Path datei;
    private final Map<String, Zeile> zeilen = new LinkedHashMap<>();

    private Reihenzuordnung(Path datei) {
        this.datei = datei;
    }

    public static Reihenzuordnung laden(Path ziel) throws IOException {
        Reihenzuordnung zuordnung = new Reihenzuordnung(ziel.resolve(Katalog.VERWALTUNG).resolve(DATEI));
        if (Files.exists(zuordnung.datei)) {
            for (String zeile : Files.readAllLines(zuordnung.datei, StandardCharsets.UTF_8)) {
                String[] teile = zeile.split("\t", -1);
                if (zeile.startsWith("#") || teile.length < 2 || !teile[1].trim().matches("\\d{4}")) {
                    continue;
                }
                String reihe = teile.length > 2 ? teile[2].trim() : "";
                zuordnung.zeilen.put(new FilmInfo(teile[0].trim(), Integer.parseInt(teile[1].trim()), 0).schluessel(),
                        new Zeile(teile[0].trim(), Integer.parseInt(teile[1].trim()), reihe));
            }
        }
        return zuordnung;
    }

    /** Festgelegte Reihe eines Films ("" für keine Reihe) oder {@code null}, wenn nichts festgelegt ist. */
    public String reihe(FilmInfo info) {
        Zeile zeile = info.istFolge() ? null : zeilen.get(info.schluessel());
        return zeile == null ? null : zeile.reihe();
    }

    public void setzen(FilmInfo info, String reihe) {
        if (!info.istFolge()) {
            zeilen.put(info.schluessel(), new Zeile(info.titel(), info.jahr(), reihe == null ? "" : reihe.trim()));
        }
    }

    public void speichern() throws IOException {
        List<String> inhalt = new ArrayList<>();
        inhalt.add(KOPF);
        for (Zeile z : zeilen.values()) {
            inhalt.add(z.titel() + "\t" + z.jahr() + "\t" + z.reihe());
        }
        Files.createDirectories(datei.getParent());
        Files.write(datei, inhalt, StandardCharsets.UTF_8);
    }
}
