---
name: clean-code-report
description: Erzeugt den Clean-Code-Report des Projekts (HTML mit Punktestand, Radar je Smell-Familie, Monstern und Code-Ansicht) und liest die Zusammenfassung; nutzen, wenn jemand wissen will, wie sauber der Code ist oder womit das Aufräumen anfangen soll
---
# Clean-Code-Report 2.0

Werkzeug: `java .opencode/skills/clean-code-report/CleanCodeReport.java`, im Projektordner ausführen. Braucht ein JDK 17 oder neuer (der Bericht liest den Code mit dem Java-Parser des JDK), keine Abhängigkeiten. Schreibt `target/clean-code-report.html` und druckt Punktestand, Rang, Endgegner, Strafpunkte je Familie und die Monster mit Anzahl. Mit `--alle` druckt es zusätzlich jede Fundstelle. Kein Gate: Der Bericht zeigt, er entscheidet nicht.

## Vorgehen
1. Werkzeug ausführen, Ausgabe lesen.
2. Nennen: Punkte und Rang, die Familie mit den meisten Strafpunkten, den Endgegner (die Methode mit den meisten Strafpunkten), die drei teuersten Monster mit je einem Beispiel als `Datei:Zeile`.
3. Genau einen ersten Schritt vorschlagen: das Refactoring zum teuersten Fund, mit Namen der Technik. Nichts ändern.
4. Darauf hinweisen, dass `target/clean-code-report.html` im Browser die Funde im Code zeigt und unter „Regelwerk“ jede Regel mit Schwelle, Strafpunkten und Refactoring erklärt.

## Der Katalog
Sechs Familien nach dem Smell-Katalog von Martin Fowler in der Einteilung von Mäntylä (bekannt durch refactoring.guru); Readability nach Clean Code, Test Smells nach xUnit Test Patterns. Change Preventers (Divergent Change, Shotgun Surgery) brauchen die Änderungshistorie und fehlen.

| Familie | Monster | Regel |
|---|---|---|
| 🎈 Bloaters | 🐍 Die Schlange | Long Method: mehr als 20 Codezeilen |
| | 🏰 Die Burg | Large Class: mehr als 200 Zeilen, Testklassen 400 |
| | 🎒 Der Kofferträger | Long Parameter List: mehr als drei Parameter, Konstruktoren und Fabriken vier |
| | 🤹 Der Jongleur | Too Many Temps: mehr als fünf lokale Variablen, oder eine Variable dreimal neu belegt |
| | 🪨 Der Steinzeitmensch | Primitive Obsession: String oder int als Typ-Code (type, status, kind …) |
| | 🧑‍🤝‍🧑 Die Clique | Data Clumps: dieselben drei Parameter in mehreren Methoden |
| 🧩 Object-Orientation Abusers | 🚦 Der Weichensteller | Switch Statements: ein Wert dreimal mit festen Werten verglichen, switch über Text, derselbe switch in mehreren Methoden, instanceof-Ketten |
| | 🏕️ Der Camper | Temporary Field: Feld, das nur eine Methode benutzt und erst setzt |
| | 🩲 Der Exhibitionist | öffentliches, veränderbares Feld |
| | 🪤 Die Falle | String mit == verglichen |
| 🗑️ Dispensables | 👯 Die Zwillinge | Duplicate Code: vier gleiche Zeilen in Folge |
| | 🧟 Der Zombie | Dead Code: auskommentierter Code, private Methoden und Felder ohne Nutzer, ungenutzte Imports, Code nach return |
| | 🧸 Der Erklärbär | Comments: kurzer Kommentar, der eine Zeile erklärt oder den Code wiederholt |
| | 🧴 Der Deo-Kommentar | Kommentar, der im Rumpf einen Absatz einleitet: eine Methode ohne Namen |
| | 💊 Der Beipackzettel | Kommentar über einem generischen Namen (helper, process, test5) oder einem kryptischen Feld |
| | 🔮 Der Wahrsager | Speculative Generality: Parameter, den niemand benutzt |
| | 🦥 Der Faulpelz | Lazy Class: höchstens eine Methode, ein Feld, unter 15 Zeilen |
| | 🗂️ Der Karteikasten | Data Class: nur Felder, Getter und Setter |
| 🔗 Couplers | 👀 Der Neider | Feature Envy: dreimal fremde Daten, seltener eigene |
| | ⛓️ Die Kette | Message Chains: a.getB().getC().getD() |
| | 📞 Der Vermittler | Middle Man: die Hälfte der Methoden reicht nur weiter |
| 👓 Readability | 🏹 Der Pfeil | mehr als drei Ebenen tief |
| | 🌀 Der Strudel | zyklomatische Komplexität über 10 |
| | 🎩 Der Zauberer | Zahlen außer 0 und 1; Texte, die verglichen werden oder mehrfach vorkommen |
| | 🕵️ Agent X | Namen mit ein oder zwei Zeichen, tmp, data, doIt, helper, Namen mit Zahl am Ende |
| | 🖨️ Der Drucker | System.out außerhalb von main |
| | 🕳️ Das Schwarze Loch | leerer catch, catch mit return, catch mit nur printStackTrace, catch (Exception) |
| | 🔀 Der Schalter | Flag Argument: boolean-Parameter |
| 🧪 Test Smells | 🧪 Der Test-Muffel | test1, Tests ohne Prüfung, mehr als acht Prüfungen |
| | 🎭 Der Schauspieler | if oder Schleife im Test, Thread.sleep, @Ignore, System.out im Test |

Punkte: Jede Familie startet mit 100 Punkten. Jeder Fund kostet seine Familie Punkte, die Zahl steht hinter dem Fund; unter 0 geht es nicht. Der Punktestand ist der Durchschnitt der Familien; gibt es keine Tests, wird Test Smells nicht bewertet. Die Rechnung steht im Bericht unter dem Tacho, die Kosten je Monster im Regelwerk. Rang ab 90 Clean-Code-Meister, ab 75 Geselle, ab 60 Lehrling, ab 40 Spaghetti-Koch, darunter Legacy-Legende. Jeder Lauf hängt den Stand an `.clean-code-history` an; der Bericht zeigt den Verlauf.

## Weitergeben
Der Ordner `.opencode/skills/clean-code-report/` ist in sich geschlossen. In ein anderes Projekt kopieren, dazu `.opencode/commands/clean-code-report.md` und in `opencode.json` die Bash-Freigabe `java .opencode/skills/clean-code-report/CleanCodeReport.java*`. Dateien, die der Bericht übergehen soll, stehen als Glob in `.clean-code-ignore`, eines je Zeile.
