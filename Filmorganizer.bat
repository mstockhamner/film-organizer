@echo off
rem Startet die Oberfläche des Filmorganizers (vorher einmal "mvn package" ausführen).
start "" javaw -jar "%~dp0target\filmorganizer-0.1.0-SNAPSHOT.jar"
