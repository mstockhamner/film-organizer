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
- **Serien** (ergänzt 18.10.2026): In den Quellordnern liegen Filme und Serien gemischt. Eine Kennung wie `S01E02` (Staffel 1, Folge 2) im Datei- oder Ordnernamen macht aus der Datei eine Serienfolge. Staffel und Folge kommen bevorzugt aus dem Dateinamen, der Serientitel aus einem Ordner mit Kennung (`Breaking.Bad.S01.German.1080p`), sonst aus dem Dateinamen, sonst aus dem Ordner über einem Staffelordner (`Dark/Staffel 2/s02e05.mp4`). Für Folgen ist kein Jahr nötig. Doubletten bei Folgen: gleicher Serientitel, gleiche Staffel und Folge.

### 3.3 Doubletten finden
Stufenweise, damit nicht jede Datei komplett gelesen werden muss:
1. **Gleicher Film:** gleicher normalisierter Titel und gleiches Jahr (findet auch verschiedene Fassungen, z.B. 720p und 1080p).
2. **Gleiche Datei:** gleiche Größe, dann Prüfsumme über Anfang, Mitte und Ende der Datei, auf Wunsch volle Prüfsumme.
- Bericht pro Doublettengruppe mit Größe, Auflösung und Pfad, plus Vorschlag, welche Fassung behalten wird (höchste Auflösung, dann größte Datei).
- **Prüfung beim Import:** Jeder Import prüft automatisch auf Doubletten, und zwar innerhalb der neuen Filme und gegen die bereits einsortierte Sammlung. Ein als Doublette erkannter Film wird nicht einsortiert, sondern in der Vorschau gemeldet. Es wird nicht stillschweigend etwas ersetzt: Wenn die neue Fassung besser ist, kann sie auf Bestätigung die alte ersetzen, und die alte kommt in die Quarantäne.
- **Quarantäne** (entschieden 18.10.2026): `Ziel\_Doubletten\<Datum>\<alter Pfad ohne Laufwerk>`, z.B. `Ziel\_Doubletten\2026-10-18\Daten-Filmorganizer\Kopie\film.mkv`, samt Begleitdateien. Beim Ausführen kommen identische Dateien und schlechtere Fassungen dorthin. Bessere Fassungen ersetzen die vorhandene, nicht eindeutig vergleichbare bleiben in der Quelle liegen.
- **Welche Fassung ist besser** (ergänzt 18.10.2026): zuerst die höhere Auflösung, wenn sie bei beiden bekannt ist und sich unterscheidet, sonst die größere Datei. Behalten wird also bei gleicher oder unbekannter Auflösung immer die größere Datei. Bei von Hand abgelegten Filmen ohne Größe im Katalog zählt die Größe der Datei im Ordner. Nicht eindeutig ist es nur bei gleicher Auflösung und gleicher bzw. unbekannter Größe. Ein abgewählter Film bleibt ebenfalls liegen. Die Verschiebungen stehen im Protokoll und lassen sich rückgängig machen. Gelöscht wird nichts, die Quarantäne leert Marc selbst.
- **Entscheidung je Doublette** (ergänzt 18.10.2026): Jede Doublette hat in der Vorschau eine Spalte „Entscheidung“, vorbelegt mit dem Vorschlag:
  - identische Datei oder schlechtere Fassung (niedrigere Auflösung bzw. kleinere Datei): „In Quarantäne“
  - bessere Fassung als die in der Sammlung (höhere Auflösung bzw. größere Datei): „Ersetzen“. Die alte Videodatei und die von den neuen Namen belegten Begleitdateien (z.B. `poster.jpg`) kommen in die Quarantäne, die neue Fassung nimmt ihren Platz ein, der Katalog wird aktualisiert. Andere Dateien im Ordner bleiben.
  - nicht eindeutig vergleichbar (gleiche Auflösung, gleiche oder unbekannte Größe): „Liegen lassen“

  Wählbar sind „In Quarantäne“ und „Liegen lassen“, gegenüber einer Fassung in der Sammlung mit eindeutiger Videodatei auch „Ersetzen“. Der Hinweis zeigt Auflösung und Größe beider Fassungen. Die Bestätigung vor dem Ausführen nennt die Anzahl der Ersetzungen und der Dateien für die Quarantäne. Rückgängig stellt auch Ersetzungen wieder her.
- **Doublettenbericht:** Jeder Lauf mit Doubletten oder anderen Fassungen schreibt `Ziel\.filmorganizer\berichte\<Zeitstempel>-doubletten.txt` mit Entscheidung, Hinweis und allen Verschiebungen.
- **Kommandozeile:** `--doubletten=quarantaene` (Standard, Vorschläge wie oben) oder `--doubletten=liegen` (alle Doubletten bleiben in der Quelle) hinter `vorschau` bzw. `ausfuehren`.
- **Fassungen** (entschieden 18.10.2026): Extended, Director's Cut, Final Cut, Unrated und Uncut werden im Namen erkannt. Ohne Angabe oder mit „Kinofassung“/„Theatrical“ gilt die Kinofassung. Eine andere Fassung eines vorhandenen oder im selben Lauf einsortierten Films ist keine Doublette: Es wird nachgefragt, auch wenn sie eine niedrigere Auflösung hat. Bis dahin bleibt sie liegen. Wahlmöglichkeiten in der Spalte „Entscheidung“ bzw. auf der Kommandozeile beim `ausfuehren`: „Beide behalten“ (dann im selben Ordner als `Titel (Jahr) - Extended.<Endung>`, Begleitdateien mit diesem Namen), „In Quarantäne“ oder liegen lassen.

### 3.4 Neue Struktur
- Die Filme werden aus den Quellordnern in einen **neuen Zielordner** verschoben (entschieden 18.10.2026). Die Quellordner bleiben danach mit den nicht zugeordneten Resten (z.B. `Thumbs.db`) zurück und werden nicht automatisch gelöscht.
- Liegt der Zielordner auf einem anderen Laufwerk, wird zuerst kopiert, die Kopie geprüft (Größe und Prüfsumme) und erst dann die Quelldatei entfernt.
- Vor dem Start wird geprüft, ob auf dem Ziellaufwerk genug Platz ist.
- Zielschema (entschieden 18.10.2026): `Ziel/Titel (Jahr)/Titel (Jahr).<Endung>`, die Original-Endung bleibt erhalten (`.mkv`, `.mp4` usw.). Dazu `poster.jpg/png` und Untertitel `Titel (Jahr).de.srt`.
- Serienfolgen (ergänzt 18.10.2026): `Ziel/Serien/Titel/Staffel 01/Titel - S01E02.<Endung>`. Begleitdateien heißen wie die Folge (z.B. `Titel - S01E02.jpg`), weil sich die Folgen einen Staffelordner teilen.
- Zeichen, die unter Windows in Dateinamen verboten sind (`: ? * " < > |`), werden ersetzt, z.B. `:` durch ` -`.
- Vorschau aller geplanten Verschiebungen, dann Ausführung.
- Konflikte (Zieldatei existiert schon) werden nicht überschrieben, sondern gemeldet.

### 3.5 Rückgängig machen
- Jede Ausführung schreibt ein Protokoll (alter Pfad, neuer Pfad, Zeitpunkt).
- Ein Befehl macht die letzte Ausführung vollständig rückgängig.

### 3.6 Bedienung
- **Oberfläche** (Java Swing, ohne zusätzliche Bibliotheken): Quelle und Ziel wählen, „Titel einlesen“ liest alle Filme ein und zeigt eine Maske mit Vorschlag für Titel, Jahr und neuen Ort. Titel und Jahr lassen sich dort per Doppelklick ändern (auch für nicht erkannte Filme), einzelne Filme lassen sich abwählen. Jede Änderung aktualisiert Zielordner und Doublettenprüfung sofort. Danach nach Bestätigung einsortieren oder den letzten Lauf rückgängig machen. Start per Doppelklick auf `Filmorganizer.bat` oder `java -jar` ohne Argumente. Die zuletzt gewählten Ordner merkt sich das Programm lokal.
- **Kommandozeile** für dieselben Funktionen: `vorschau`, `ausfuehren`, `rueckgaengig`.

## 4. Nicht im Umfang (erste Version)
- Online-Metadaten, Poster-Download, Bewertungen
- Umwandeln oder Komprimieren von Videos
- Löschen von Dateien

## 5. Offene Punkte
1. ~~Wo soll die geordnete Sammlung hin?~~ Entschieden: in einen neuen Zielordner (siehe 3.4). Zum Testen: Quelle `F:\Daten-Filmorganizer`, Ziel `F:\Daten-Filmorganizer\Sammlung`. Da das Ziel innerhalb der Quelle liegt, schließt der Scan den Zielordner immer aus. Ziel und Quelle sind auf demselben Laufwerk, das Verschieben ist also ein schnelles Umbenennen ohne Kopieren.
2. ~~Zielschema?~~ Entschieden: `Titel (Jahr)/Titel (Jahr).<Endung>` (siehe 3.4).
3. ~~Sprache der Titel?~~ Entschieden: Es wird der Titel verwendet, der sich aus den Namen lesen lässt, egal in welcher Sprache. Es wird nichts übersetzt. Der Ordnername hat Vorrang vor dem Namen der Videodatei, weil diese oft nur Kürzel enthält (`lhd-kater-1080p.mkv`).
4. ~~Größe der Sammlung?~~ Wachsend, daher wiederholte Läufe mit Katalog (siehe 3.1).
