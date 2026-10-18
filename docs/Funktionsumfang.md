# Film-Organizer: Funktionsumfang (Entwurf)

Stand: 18.10.2026 · Projekt: `E:\claude\work\film-organizer` (at.mycroft.filmorganizer, Java 25, Maven)

## 1. Grundsätze

1. **Alles lokal.** Das Programm überträgt keine Daten ins Internet: keine Online-Metadaten (TMDb, OMDb o.ä.), keine Telemetrie, keine Update-Prüfung, keine Cloud-Dienste. Es baut keine Netzwerkverbindung auf.
2. **Nichts ohne Bestätigung.** Jede Änderung im Dateisystem wird zuerst als Vorschau gezeigt und erst nach ausdrücklicher Bestätigung ausgeführt.
3. **Nichts geht verloren.** Es wird verschoben und umbenannt, nie gelöscht. Doubletten werden in einen Quarantäne-Ordner verschoben, nicht entfernt. Jede Aktion landet in einem Protokoll, mit dem sie rückgängig gemacht werden kann.

## 2. Ausgangslage (aus den Beispielen in `F:\Daten-Filmorganizer`)

| Beobachtung | Beispiel | Folge für das Programm |
|---|---|---|
| Ordner heißt wie ein Release, mit Titel, Jahr, Sprache, Auflösung, Codec und Gruppe | `Das.Dschungelbuch.2016.German.DTSHD.1080p.BluRay.x264-FDHQ` | Titel und Jahr aus dem Ordnernamen parsen, Technik-Angaben abschneiden |
| Videodatei hat einen kryptischen Namen, oft englischer Titel oder Kürzel | `WodkaE-The.Jungle.Book.1080p.mkv`, `lhd-kater-1080p.mkv` | Ordnername hat Vorrang vor dem Dateinamen |
| Jahr steht an verschiedenen Stellen | `.2016.` mitten im Namen, `(2011)` am Anfang | Mehrere Muster für das Jahr erkennen |
| Verschachtelte Ordner | `Der gestiefelte Kater (1080p)/(2011) Der gestiefelte Kater (1080p)/` | Rekursiv scannen, ein Film = ein Ordner mit genau einer Hauptvideodatei |
| Begleitdateien | `cover.png`, `cover.PNG`, `cover.jpg`, `Thumbs.db` | Cover mitnehmen (einheitlich `poster.jpg/png`), `Thumbs.db` ignorieren |
| Große Dateien | 6 bis 10 GB pro Film | Doublettenprüfung darf nicht jede Datei komplett lesen |

## 3. Funktionen

### 3.1 Scannen
- Einen oder mehrere Quellordner rekursiv durchsuchen.
- Videodateien erkennen (`.mkv`, `.mp4`, `.avi`, `.m4v`, `.ts`), Begleitdateien zuordnen (Cover, `.nfo`, Untertitel `.srt/.sub/.idx`).
- Ergebnis wird in einem lokalen Katalog gespeichert (Datei im Projekt- oder Benutzerordner, z.B. SQLite oder JSON).
- **Wachsende Sammlung:** Das Programm wird wiederholt eingesetzt. Bereits einsortierte Filme stehen im Katalog und werden nicht erneut verarbeitet. Neue Filme in den Quellordnern werden erkannt und vor dem Einsortieren gegen den bestehenden Zielordner auf Doubletten geprüft. Folgescans lesen nur neue oder geänderte Dateien.

### 3.2 Titel und Jahr erkennen
- Aus Ordner- und Dateinamen, ergänzt um lokal vorhandene Infos: `.nfo`-Dateien und eingebettete MKV-Tags.
- Technik-Angaben herauslösen und separat speichern: Auflösung (1080p), Quelle (BluRay), Codec (x264), Ton (AC3, DTSHD), Sprache (German, DL).
- Unsichere Erkennungen werden markiert und manuell bestätigt oder korrigiert.

### 3.3 Doubletten finden
Stufenweise, damit nicht jede Datei komplett gelesen werden muss:
1. **Gleicher Film:** gleicher normalisierter Titel und gleiches Jahr (findet auch verschiedene Fassungen, z.B. 720p und 1080p).
2. **Gleiche Datei:** gleiche Größe, dann Prüfsumme über Anfang, Mitte und Ende der Datei, auf Wunsch volle Prüfsumme.
- Bericht pro Doublettengruppe mit Größe, Auflösung und Pfad, plus Vorschlag, welche Fassung behalten wird (höchste Auflösung, dann größte Datei).
- **Prüfung beim Import:** Jeder Import prüft automatisch auf Doubletten, und zwar innerhalb der neuen Filme und gegen die bereits einsortierte Sammlung. Ein als Doublette erkannter Film wird nicht einsortiert, sondern in der Vorschau gemeldet. Es wird nicht stillschweigend etwas ersetzt: Wenn die neue Fassung besser ist, kann sie auf Bestätigung die alte ersetzen, und die alte kommt in die Quarantäne.

### 3.4 Neue Struktur
- Die Filme werden aus den Quellordnern in einen **neuen Zielordner** verschoben (entschieden 18.10.2026). Die Quellordner bleiben danach mit den nicht zugeordneten Resten (z.B. `Thumbs.db`) zurück und werden nicht automatisch gelöscht.
- Liegt der Zielordner auf einem anderen Laufwerk, wird zuerst kopiert, die Kopie geprüft (Größe und Prüfsumme) und erst dann die Quelldatei entfernt.
- Vor dem Start wird geprüft, ob auf dem Ziellaufwerk genug Platz ist.
- Zielschema (entschieden 18.10.2026): `Ziel/Titel (Jahr)/Titel (Jahr).<Endung>`, die Original-Endung bleibt erhalten (`.mkv`, `.mp4` usw.). Dazu `poster.jpg/png` und Untertitel `Titel (Jahr).de.srt`.
- Zeichen, die unter Windows in Dateinamen verboten sind (`: ? * " < > |`), werden ersetzt, z.B. `:` durch ` -`.
- Vorschau aller geplanten Verschiebungen, dann Ausführung.
- Konflikte (Zieldatei existiert schon) werden nicht überschrieben, sondern gemeldet.

### 3.5 Rückgängig machen
- Jede Ausführung schreibt ein Protokoll (alter Pfad, neuer Pfad, Zeitpunkt).
- Ein Befehl macht die letzte Ausführung vollständig rückgängig.

### 3.6 Bedienung
- Erste Version als Kommandozeilenprogramm: `scan`, `duplikate`, `vorschau`, `ausfuehren`, `rueckgaengig`.
- Eine grafische Oberfläche (z.B. JavaFX) ist eine mögliche spätere Ausbaustufe.

## 4. Nicht im Umfang (erste Version)
- Online-Metadaten, Poster-Download, Bewertungen
- Serien und Episoden
- Umwandeln oder Komprimieren von Videos
- Löschen von Dateien

## 5. Offene Punkte
1. ~~Wo soll die geordnete Sammlung hin?~~ Entschieden: in einen neuen Zielordner (siehe 3.4). Zum Testen: Quelle `F:\Daten-Filmorganizer`, Ziel `F:\Daten-Filmorganizer\Sammlung`. Da das Ziel innerhalb der Quelle liegt, schließt der Scan den Zielordner immer aus. Ziel und Quelle sind auf demselben Laufwerk, das Verschieben ist also ein schnelles Umbenennen ohne Kopieren.
2. ~~Zielschema?~~ Entschieden: `Titel (Jahr)/Titel (Jahr).<Endung>` (siehe 3.4).
3. ~~Sprache der Titel?~~ Entschieden: Es wird der Titel verwendet, der sich aus den Namen lesen lässt, egal in welcher Sprache. Es wird nichts übersetzt. Der Ordnername hat Vorrang vor dem Namen der Videodatei, weil diese oft nur Kürzel enthält (`lhd-kater-1080p.mkv`).
4. ~~Größe der Sammlung?~~ Wachsend, daher wiederholte Läufe mit Katalog (siehe 3.1).
