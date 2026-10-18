# Film-Organizer – Arbeitsweise

## Commits
- Commits werden direkt auf dem Branch `master` gemacht, keine Feature-Branches, Worktrees oder Patch-Dateien.
- Eine neue Funktion = ein Commit. Vor dem Commit `mvn -q test` ausführen, nur bei grünen Tests committen.
- Autor ist Marcs Git-Identität (die konfigurierte `user.name`/`user.email`).
- Nie pushen, das macht Marc selbst nach dem Testen.

## Grenzen
- Strikt offline: keine Netzwerkzugriffe, keine Online-Dienste (z. B. TMDb), keine Cloud-APIs, keine Telemetrie.
- Keine neuen Abhängigkeiten ohne Rückfrage.
- Keine Filmordner (`F:\...`) lesen oder verändern, außer Marc verlangt es ausdrücklich.

## Spezifikation
Siehe `docs/Funktionsumfang.md`.
