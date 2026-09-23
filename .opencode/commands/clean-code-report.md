---
description: Clean-Code-Report erzeugen (target/clean-code-report.html) und die wichtigsten Funde nennen
---
Erzeuge den Clean-Code-Report und fasse ihn zusammen. $ARGUMENTS

Skill clean-code-report, hier eingefügt:
@.opencode/skills/clean-code-report/SKILL.md

Ergebnis des Laufs über das ganze Projekt, mit allen Fundstellen:
!`java .opencode/skills/clean-code-report/CleanCodeReport.java --alle`

Nennt der Auftrag oben einen Ausschnitt (ein Package, eine Klasse, eine Methode oder eine Beschreibung wie „alle Domain-Klassen“), dann gilt der Ausschnitt: Führe `java .opencode/skills/clean-code-report/CleanCodeReport.java --ziele` aus, wähle die passenden Ziele und rufe das Werkzeug mit `--nur Ziel --alle` auf; berichte über diesen Lauf und sage, welchen Ausschnitt du gewählt hast. Ohne Ausschnitt steht alles, was du brauchst, in der Ausgabe oben: Lies nicht den HTML-Bericht und starte das Werkzeug nicht noch einmal.

Antworte in dieser Reihenfolge: Punkte und Rang; die Familie mit den wenigsten Punkten; Endgegner (oder „keiner“); die drei teuersten Monster mit je einem Beispiel als `Datei:Zeile` aus der Liste; ein erster Schritt mit Namen der Technik. Dann der Hinweis auf `target/clean-code-report.html` und die Regelwerk-Seite darin. Ändere nichts am Code.
