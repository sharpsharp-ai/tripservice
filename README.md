# Trip Service Kata

Legacy-Code mit fest verdrahteten Abhängigkeiten: `TripService.getTripsByUser()` holt den angemeldeten
Benutzer aus einem Singleton und die Reisen aus einer statischen Methode. Beide werfen im Test eine Exception.
Die Regel der Kata (Sandro Mancuso): Kein Produktivcode wird geändert, der nicht unter Test ist. Ausnahme sind
automatische Refactorings der IDE, die nötig sind, um einen Test schreiben zu können.

Fertig ist eine Änderung, wenn `mvn -q verify` ohne Ausgabe und mit Exit-Code 0 endet.

## Loslegen

Voraussetzung: JDK 17 oder neuer und Maven (IntelliJ bringt Maven mit).

```bash
git clone https://github.com/sharpsharp-ai/tripservice.git
cd tripservice
mvn -q verify                      # keine Ausgabe heißt grün; schreibt den Abdeckungsbericht
scripts/unabgedeckt.sh TripService # welche Zeilen und Verzweigungen die Tests nicht erreichen
```

IntelliJ: File → New → Project from Version Control, die URL einfügen. IntelliJ erkennt die `pom.xml`
und lädt die Bibliotheken. Rechtsklick auf `src/test/java` → Run 'All Tests'.

## Die Aufgabe

Bring `TripService` unter Test, ohne die Regel zu brechen. Dann bau ihn sauber um: Abhängigkeiten
hereinreichen statt festverdrahten. Einstieg sind `TripServiceTest.java` und `TripService.java`.
`TripService_Original.java` bleibt zum Vergleich unverändert.

Hintergrund, erst nach der Kata lesen: Sandro Mancuso, [Testing legacy code: Hard-wired dependencies](https://codurance.com/2011/07/16/testing-legacy-hard-wired-dependencies/),
dazu sein [Video](https://www.youtube.com/watch?v=_NnElPO5BU0). Die Aufgabe als PDF: `TripServiceKata.pdf`.

## Mit opencode arbeiten

Das Repo bringt opencode eine Rolle mit: `refactoring-coach`. Er erklärt die Lage, stellt eine Frage, schlägt
genau einen Schritt vor, macht ihn nach dem OK selbst und lässt `mvn -q verify` laufen. Eine Session für den
ganzen Weg. Nichts installieren: im Projektordner `opencode --agent refactoring-coach` starten, dann bleibt
die Rolle für alle Antworten im Dialog. In IntelliJ (ACP) den Session-Modus `refactoring-coach` wählen.

```text
/coach                 Einstieg: Lage, Frage, erster Schritt als Vorschlag
ja                     der Coach macht den Schritt und schlägt den nächsten vor
was wäre, wenn …       eigene Idee, der Coach spielt sie durch
```

| Datei | Wirkung |
|---|---|
| `AGENTS.md` | Befehle, Struktur, die Regel der Kata, Arbeitsweise. Liest jede Rolle in jeder Session |
| `opencode.json` | die Rolle `refactoring-coach` mit ihren Rechten, die Bash-Whitelist |
| `.opencode/commands/coach.md` | der Command; lesbares Markdown, das ist der Prompt |
| `.opencode/skills/legacy-seams/SKILL.md` | die Techniken: Nähte, Reihenfolge, Coaching |
| `scripts/unabgedeckt.sh` | nicht erreichte Zeilen und Verzweigungen aus dem JaCoCo-Bericht |

| Rolle | Darf ändern | Bash |
|---|---|---|
| `refactoring-coach` | `src/`, außer `TripService_Original.java` | mvn, `scripts/unabgedeckt.sh`, ls, cat, grep, git status/diff/log |

## Struktur

| Ort | Inhalt |
|---|---|
| `src/main/java/de/sharpsharp/tripservice/trip/TripService.java` | der Legacy-Code |
| `src/main/java/de/sharpsharp/tripservice/trip/TripDAO.java` | statische `findTripsByUser`, wirft im Test |
| `src/main/java/de/sharpsharp/tripservice/user/UserSession.java` | Singleton, `getLoggedUser()` wirft im Test |
| `src/main/java/de/sharpsharp/tripservice/user/User.java` | Freunde und Reisen |
| `src/main/java/de/sharpsharp/tripservice/TripService_Original.java` | die Ausgangsfassung, zum Vergleich |
| `src/test/java/de/sharpsharp/tripservice/` | die Tests, noch leer |
