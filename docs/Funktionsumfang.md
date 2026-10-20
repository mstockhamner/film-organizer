# Film-Organizer: Funktionsumfang (Entwurf)

Stand: 18.10.2026 · Projekt: `E:\claude\work\film-organizer` (at.mycroft.filmorganizer, Java 25, Maven)

## 1. Grundsätze

1. **Alles lokal.** Das Programm überträgt keine Daten ins Internet: keine Online-Metadaten (TMDb, OMDb o.ä.), keine Telemetrie, keine Update-Prüfung, keine Cloud-Dienste. Es baut keine Netzwerkverbindung auf.
2. **Nichts ohne Bestätigung.** Jede Änderung im Dateisystem wird zuerst als Vorschau gezeigt und erst nach ausdrücklicher Bestätigung ausgeführt.
3. **Nichts geht verloren.** Es wird verschoben und umbenannt, keine Datei der Sammlung wird gelöscht. Entfernt werden nur Ordner, die durch das Verschieben leer geworden sind, samt Systemresten darin (`Thumbs.db`, `desktop.ini`, `.DS_Store`, `@eaDir`). Doubletten werden in einen Quarantäne-Ordner verschoben, nicht entfernt. Jede Aktion landet in einem Protokoll, mit dem sie rückgängig gemacht werden kann.

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
- Die Sammlung liegt auf einem Synology-NAS (ergänzt 18.10.2026). Beim Durchsuchen werden der Papierkorb `#recycle` und die Metadaten-Ordner `@eaDir` (Vorschaubilder der Synology) übersprungen, egal in welcher Tiefe und Schreibweise. Dateien darin zählen weder als Film noch als Doublette noch als Cover.
- Videodateien erkennen (`.mkv`, `.mp4`, `.avi`, `.m4v`, `.ts`), Begleitdateien zuordnen (Cover, `.nfo`, Untertitel `.srt/.sub/.idx`).
- Ergebnis wird in einem lokalen Katalog gespeichert (Datei im Projekt- oder Benutzerordner, z.B. SQLite oder JSON).
- **Wachsende Sammlung:** Das Programm wird wiederholt eingesetzt. Bereits einsortierte Filme stehen im Katalog und werden nicht erneut verarbeitet. Neue Filme in den Quellordnern werden erkannt und vor dem Einsortieren gegen den bestehenden Zielordner auf Doubletten geprüft. Folgescans lesen nur neue oder geänderte Dateien.

### 3.2 Titel und Jahr erkennen
- Aus Ordner- und Dateinamen, ergänzt um lokal vorhandene Infos: `.nfo`-Dateien und eingebettete MKV-Tags.
- **Sat-Aufnahmen** (ergänzt 18.10.2026): Namen eines Sat-Receivers wie `20200612 2226 - ATV HD - The Greatest Showman` (Datum, Uhrzeit, Sender, Titel; auch mit `_` statt Leerzeichen und Teilnummer `_001`) ergeben den Titel „The Greatest Showman“. Das Datum ist der Aufnahmetag, nicht das Erscheinungsjahr. Das Jahr kommt aus dem Ordner, wenn er denselben Film mit Jahr nennt, sonst aus den lokalen Receiver-Dateien `.ts.meta` und `.eit` (z.B. „Musical, USA 2017“). Fehlt es, steht der Titel als Vorschlag in der Vorschau und nur das Jahr ist nachzutragen.
  - Metadaten (ergänzt 18.10.2026, ausgewertet an `z:\4sort`): `.ts.meta` ist Text (UTF-8): Zeile 1 Dienst und Sender, Zeile 2 Titel, Zeile 3 Kurzbeschreibung (`\n` als Zeilentrenner), Zeile 4 Startzeit (Unix-Sekunden), dann Länge, Dateigröße, PIDs. `.eit` ist ein DVB-Event: 12 Byte Kopf, Deskriptor 0x4D (Titel, Kurzbeschreibung), 0x4E (Langbeschreibung mit Regie, Drehbuch, Darstellern, über mehrere Deskriptoren verteilt), 0x50 (Bild/Ton, z.B. „HD Video“); Zeichensatz je Text (0x15 UTF-8, 0x0B ISO-8859-15 …). `.ap`, `.sc`, `.cuts` sind Receiver-Indizes und Schnittmarken ohne Inhaltsangaben.
  - Der Titel kommt aus `.meta`/`.eit`, nicht aus dem Dateinamen (der Receiver ersetzt dort `:` und `/`, z.B. „Die Nackte Kanone 33 13“ statt „33 1/3“). Das Jahr wird in Formen wie „Thriller, USA 2017“, „(Zeichentrickfilm, DNK/FRA 2006)“ oder „Großbritannien 2003“ gefunden. Manche Sender (VOX, RTLZWEI, SUPER RTL) liefern kein Jahr.
  - Folgen einer Sendereihe (z.B. Terra X, Ninjago, zeit.geschichte, „Inside …“ als Doku-Reihe, „Teil 4“) werden an der Kurzbeschreibung erkannt (eigener kurzer Folgentitel, „Doku-Reihe“, „Teil n“, „(1/4)“). Sie sind keine Filme und werden nie als Doubletten desselben „Films“ behandelt.
  - Ablage (entschieden 18.10.2026): `Ziel\Serien\<Titel>\<Titel> - <Folgentitel>.<Endung>`, z.B. `Serien\Terra X\Terra X - Eine Erde - viele Welten - Inseln.ts`. Eine Doublette ist nur dieselbe Reihe mit demselben Folgentitel (z.B. eine doppelt aufgenommene Folge). Von Hand so abgelegte Folgen zählen als vorhanden.
  - .nfo (entschieden 18.10.2026): Beim Einsortieren einer Aufnahme mit `.meta`/`.eit` wird `Titel (Jahr).nfo` im Kodi-Format angelegt (Titel, Jahr, Inhalt, Genre, Regie, Drehbuch, Darsteller mit Rolle; bei Folgen `<episodedetails>` mit Reihe, Folgentitel und Sendedatum). Kodi, Jellyfin und Plex lesen sie lokal. Eine vorhandene oder mitgebrachte .nfo wird nie überschrieben. Die angelegte Datei steht im Protokoll und wird beim Rückgängigmachen wieder entfernt.
  - Receiver-Dateien (entschieden 18.10.2026): `.ts.meta`, `.ts.ap`, `.ts.sc`, `.ts.cuts` und `.eit` einer Aufnahme wandern mit ihr mit (auch in die Quarantäne) und heißen danach wie die Videodatei, z.B. `The Greatest Showman (2017).ts.meta`. Sie gehören über den Namen eindeutig zur Aufnahme, auch wenn viele Aufnahmen in einem Ordner liegen.
  - Auflösung von Aufnahmen (entschieden 18.10.2026): HD = 720p (Bildangabe „HD Video“ im EIT oder „HD“ im Sendernamen), sonst SD = 576p. Damit verdrängt eine große Aufnahme mit Werbung keine echte 1080p-Fassung bei der Doublettenprüfung.
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

### 3.3a Sammlungen abgleichen (ergänzt 20.10.2026)
- Zwei schon organisierte Sammlungen A und B auf Doubletten abgleichen; A ist die Hauptsammlung. Auf Wunsch wird jede Sammlung zusätzlich in sich geprüft.
- Grundlage sind die Kataloge samt von Hand abgelegter Ordner (Filme, Filmreihen, Serienfolgen, Sendungen). Gleich ist derselbe Film (Titel und Jahr bzw. Serie und Folge); dazu identische Dateien unter anderem Titel (gleiche Größe und Prüfsumme).
- Welche Fassung bleibt, folgt denselben Regeln wie beim Einsortieren: identisch → A behalten; sonst höhere Auflösung, dann größere Datei; nicht eindeutig → bitte wählen; andere Schnittfassung → beide behalten.
- Die nicht behaltene Fassung kommt mit ihren Begleitdateien in die Quarantäne ihrer eigenen Sammlung (`A\_Doubletten` bzw. `B\_Doubletten`), zwischen den Sammlungen wird nichts kopiert. Zusammenführen (Filme nur in B nach A) gehört nicht dazu.
- Kommandozeile: `abgleichen <A> <B> [--auch-in-sich]` zeigt die Funde mit Vorschlag, ändert nichts. `abgleichen-ausfuehren <A> <B>` führt die Vorschläge nach „ja“ aus, `abgleich-rueckgaengig <A> <B>` nimmt den letzten Abgleich zurück.
- Oberfläche: Knopf „Sammlungen abgleichen …“ im Hauptfenster öffnet ein eigenes Fenster (A ist mit dem Ziel vorbelegt). Tabelle je Fund: Status, Titel, Fassung 1 und 2 mit Pfad, Vergleich (Auflösung, Größe), Entscheidung „1 behalten“ / „2 behalten“ / „Beide behalten“ (Vorschlag gekennzeichnet), per Rechtsklick auch für mehrere markierte Zeilen. Entscheidungen werden sofort gespeichert (`%USERPROFILE%\.filmorganizer\arbeitsstaende\…-abgleich.tsv`) und beim nächsten Abgleich derselben Sammlungen wiederhergestellt. Vor dem Ausführen nennt eine Rückfrage, wie viele Fassungen aus A und aus B in die Quarantäne kommen.
- Ausführen: Eine Fassung, die ein anderer Fund behalten will, wird nie verschoben. Jede Sammlung bekommt ihr eigenes Protokoll `<Zeitstempel>-abgleich.tsv`, ihr Katalog wird aktualisiert, leer gewordene Ordner verschwinden. Rückgängig nimmt den Abgleich nur in den Sammlungen zurück, in denen er noch der letzte Lauf ist; ein späteres Einsortieren bleibt unberührt.
- Bericht: Jeder ausgeführte Abgleich schreibt in beide Sammlungen `.filmorganizer\berichte\<Zeitstempel>-abgleich.txt` mit allen Funden, Entscheidung (abweichender Vorschlag in Klammern), Vergleich und Verschiebungen.

### 3.4 Neue Struktur
- Die Filme werden aus den Quellordnern in einen **neuen Zielordner** verschoben (entschieden 18.10.2026). Ordner unter der Quelle, die dadurch ganz leer werden (auch mit leeren Unterordnern), werden danach entfernt (ergänzt 18.10.2026). Als leer gilt auch ein Ordner, in dem nur noch Systemreste liegen (`Thumbs.db`, `ehthumbs.db`, `desktop.ini`, `.DS_Store`, `@eaDir` der Synology); die Reste werden direkt gelöscht (entschieden 18.10.2026). Die Quelle selbst, Ordner oberhalb der Quelle und Ordner mit anderen Dateien bleiben stehen. Rückgängig legt die Ordner wieder an.
- Liegt der Zielordner auf einem anderen Laufwerk, wird zuerst kopiert, die Kopie geprüft (Größe und Prüfsumme) und erst dann die Quelldatei entfernt.
- Vor dem Start wird geprüft, ob auf dem Ziellaufwerk genug Platz ist.
- Zielschema (entschieden 18.10.2026): `Ziel/Titel (Jahr)/Titel (Jahr).<Endung>`, die Original-Endung bleibt erhalten (`.mkv`, `.mp4` usw.). Dazu `poster.jpg/png` und Untertitel `Titel (Jahr).de.srt`.
- **Filmreihen** (ergänzt 18.10.2026): Filme einer Reihe (z.B. Harry Potter, Gregs Tagebuch, Zurück in die Zukunft) kommen in einen gemeinsamen Ordner: `Ziel/Reihe/Titel (Jahr)/Titel (Jahr).<Endung>`. Erkannt wird rein lokal am gemeinsamen Titelanfang mehrerer Filme, wenn danach eine Nummer (2, II), ein Trenner (` - `, `:`, „Teil“) oder „und“ folgt oder der Titel endet. Gleiche Titel (Neuverfilmungen) allein bilden keine Reihe, ein einzelnes Wort nur mit Nummer (Shrek, Shrek 2). Die Reihe steht in der Vorschau in der Spalte „Filmreihe“ und lässt sich ändern oder leeren. Filme, die schon direkt im Ziel liegen und jetzt zu einer Reihe gehören, werden samt Ordner in den Reihen-Ordner umsortiert (Vorschau, abwählbar, rückgängig zu machen). Ein vorhandener, auch von Hand benannter Reihen-Ordner wird weiterverwendet.
  - Reihe und Nummer im Namen (ergänzt 20.10.2026): `Reihe - Nummer - Titel` oder `Reihe Nummer - Titel`, z.B. `Star Trek - 04 - Zurück in die Gegenwart` bzw. `Star Trek 04 - Zurück in die Gegenwart`, ergibt den Titel „Zurück in die Gegenwart“ in der Filmreihe „Star Trek“ (Datei- oder Ordnername). Ein Jahr am Ende („(1986)“) wird übernommen; ohne Jahr stehen Titel und Reihe vorausgefüllt in der Vorschau und nur das Jahr ist nachzutragen. Die Nummer bleibt im Ordnernamen (entschieden 20.10.2026): `Ziel\Star Trek\04 - Zurück in die Gegenwart (1986)\Zurück in die Gegenwart (1986).mkv`, damit die Reihenfolge auch im Explorer stimmt; die Datei heißt ohne Nummer. Der Katalog liest Titel und Nummer aus solchen Ordnern zurück, auch bei von Hand angelegten; weitere Fassungen und Ersetzungen landen im nummerierten Ordner.
  - Ein in der Vorschau eingetragenes Jahr gilt auch für alle anderen eingelesenen Dateien mit demselben Titel (und derselben Reihe), denen das Jahr noch fehlt, z.B. derselbe Film als .m4v und .mkv. Danach greift die Doublettenprüfung.
  - Filme, deren Titel mit dem Namen einer bekannten Reihe beginnt (aus nummerierten Namen oder aus der Sammlung, ganze Wörter), kommen in deren Ordner, z.B. „Star Trek Beyond (2016)“ nach `Star Trek\`. Die Reihe aus dem Namen gilt vor der automatischen Erkennung, eine von Hand gesetzte Reihe vor beiden.
  - Mehrere Filme auf einmal: in der Vorschau Zeilen markieren (Strg-/Umschalt-Klick), dann „Filmreihe zuweisen …“ (Schaltfläche oder Rechtsklick). Auswahl aus den bekannten Reihen oder neuer Name. „Aus Filmreihe entfernen“ nimmt die markierten Filme aus ihrer Reihe. Serienfolgen und Filme ohne Titel und Jahr werden übergangen.
  - Gemerkt (ergänzt 18.10.2026): Von Hand gesetzte Reihen, auch „keine Reihe“, werden nach dem Einsortieren in `Ziel\.filmorganizer\filmreihen.tsv` gespeichert (Titel, Jahr, Filmreihe; per Hand bearbeitbar, `#` = Kommentar). Sie gelten bei jedem Lauf vor der automatischen Erkennung, auch auf der Kommandozeile.
  - Filme der Sammlung ziehen mit ihrem Ordner um, wenn ihre Reihe von Hand geändert wird: in eine andere Reihe oder ohne Reihe zurück direkt ins Ziel. Leere Reihen-Ordner werden entfernt. Ohne Änderung von Hand bleibt ein Film in seiner Reihe.
  - Fenster „Filmreihen verwalten …“ (nach „Titel einlesen“): links die Reihen der Sammlung und „(ohne Filmreihe)“, rechts ihre Filme. Reihe umbenennen, Reihe auflösen (Filme kommen direkt ins Ziel), markierte Filme in eine Reihe aufnehmen oder aus ihr nehmen. Die Änderungen erscheinen in der Vorschau als „Wird umsortiert“ und werden erst mit „Einsortieren …“ ausgeführt und dann gemerkt.
- Serienfolgen (ergänzt 18.10.2026): `Ziel/Serien/Titel/Staffel 01/Titel - S01E02.<Endung>`. Begleitdateien heißen wie die Folge (z.B. `Titel - S01E02.jpg`), weil sich die Folgen einen Staffelordner teilen.
- Zeichen, die unter Windows in Dateinamen verboten sind (`: ? * " < > |`), werden ersetzt, z.B. `:` durch ` -`.
- Vorschau aller geplanten Verschiebungen, dann Ausführung.
- Konflikte (Zieldatei existiert schon) werden nicht überschrieben, sondern gemeldet.

### 3.5 Rückgängig machen
- Jede Ausführung schreibt ein Protokoll (alter Pfad, neuer Pfad, Zeitpunkt).
- Ein Befehl macht die letzte Ausführung vollständig rückgängig.

### 3.6 Bedienung
- **Oberfläche** (Java Swing, ohne zusätzliche Bibliotheken): Quelle und Ziel wählen, „Titel einlesen“ liest alle Filme ein und zeigt eine Maske mit Vorschlag für Titel, Jahr und neuen Ort. Titel und Jahr lassen sich dort per Doppelklick ändern (auch für nicht erkannte Filme), einzelne Filme lassen sich abwählen. Jede Änderung aktualisiert Zielordner und Doublettenprüfung sofort. Danach nach Bestätigung einsortieren oder den letzten Lauf rückgängig machen. Start per Doppelklick auf `Filmorganizer.bat` oder `java -jar` ohne Argumente. Die zuletzt gewählten Ordner merkt sich das Programm lokal.
- **Mehrere Zeilen übernehmen/abwählen** (ergänzt 19.10.2026): Zeilen markieren (Strg-/Umschalt-Klick, Strg+A), dann Rechtsklick „Markierte übernehmen“ / „Markierte nicht übernehmen“ oder Leertaste (sind alle markierten übernommen, werden alle abgewählt, sonst alle übernommen). Die Markierung bleibt danach erhalten.
- **Arbeitsstand** (ergänzt 19.10.2026): Jede Änderung in der Liste (Titel, Jahr, Abwahl, Entscheidung, Filmreihe) wird sofort automatisch gespeichert, lokal in `%USERPROFILE%\.filmorganizer\arbeitsstaende\` (eine Datei je Quelle und Ziel, neben dem Logfile; Quelle und Ziel bleiben unberührt). Beim nächsten „Titel einlesen“ mit derselben Quelle und demselben Ziel ist alles wiederhergestellt, die Liste lässt sich also über mehrere Sitzungen vervollständigen. Änderungen zu gerade nicht erreichbaren Filmen (z.B. NAS aus) bleiben erhalten. Nach dem Einsortieren fallen die erledigten Filme heraus, Unvollständiges bleibt. „Änderungen verwerfen …“ setzt die Liste nach Rückfrage auf die Vorschläge zurück.
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
