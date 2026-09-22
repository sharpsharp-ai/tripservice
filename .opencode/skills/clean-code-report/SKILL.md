---
name: clean-code-report
description: Erzeugt den Clean-Code-Report des Projekts (HTML mit Punktestand, Monstern und Code-Ansicht) und liest die Zusammenfassung; nutzen, wenn jemand wissen will, wie sauber der Code ist oder womit das Aufräumen anfangen soll
---
# Clean-Code-Report

Werkzeug: `java .opencode/skills/clean-code-report/CleanCodeReport.java`, im Projektordner ausführen. Braucht nur ein JDK 17, keine Abhängigkeiten. Schreibt `target/clean-code-report.html` und druckt Punktestand, Rang, Endgegner und die Monster mit Anzahl und Strafpunkten. Kein Gate: Der Bericht zeigt, er entscheidet nicht.

## Vorgehen
1. Werkzeug ausführen, Ausgabe lesen.
2. Nennen: Punkte und Rang, den Endgegner (die Methode mit den meisten Strafpunkten), die drei teuersten Monster mit je einem Beispiel als `Datei:Zeile`.
3. Genau einen ersten Schritt vorschlagen: das Refactoring zum teuersten Fund, mit Namen der Technik. Nichts ändern.
4. Darauf hinweisen, dass `target/clean-code-report.html` im Browser die Funde im Code zeigt.

## Was gemessen wird
| Monster | Regel |
|---|---|
| 🐍 Die Schlange | Methode länger als 20 Zeilen |
| 🏹 Der Pfeil | mehr als drei Ebenen tief verschachtelt |
| 🌀 Der Strudel | zyklomatische Komplexität über 10 |
| 🎩 Der Zauberer | Zahlen außer 0 und 1, Texte in Methoden |
| 👯 Die Zwillinge | vier gleiche Zeilen in Folge |
| 🕵️ Agent X | Namen mit ein oder zwei Zeichen, `tmp`, `data`, `foo`, Namen mit Zahl am Ende |
| 🧸 Der Erklärbär | Kommentare in Methodenrümpfen |
| 🧟 Der Zombie | auskommentierter Code, private Methoden ohne Aufrufer |
| 🖨️ Der Drucker | `System.out` im Produktivcode |
| 🕳️ Das Schwarze Loch | leerer catch-Block |
| 🎒 Der Kofferträger | mehr als drei Parameter |
| 🏰 Die Burg | Klasse länger als 200 Zeilen |
| 🩲 Der Exhibitionist | öffentliches, veränderbares Feld |
| 🧪 Der Test-Muffel | Tests namens `test1`, Tests ohne Prüfung, Tests mit mehr als fünf Prüfungen |

Punkte: 100 minus Strafpunkte, je Monster gedeckelt. Rang ab 90 Clean-Code-Meister, ab 70 Geselle, ab 50 Lehrling, ab 25 Spaghetti-Koch, darunter Legacy-Legende. Jeder Lauf hängt den Stand an `.clean-code-history` an; der Bericht zeigt den Verlauf.

## Weitergeben
Der Ordner `.opencode/skills/clean-code-report/` ist in sich geschlossen. In ein anderes Projekt kopieren, dazu `.opencode/commands/clean-code-report.md` und in `opencode.json` die Bash-Freigabe `java .opencode/skills/clean-code-report/CleanCodeReport.java*`.
