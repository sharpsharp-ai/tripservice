# Trip Service

## Befehle
- `mvn -q verify`: das einzige Fertig-Kriterium. Keine Ausgabe und Exit-Code 0 heißt grün. Schreibt den Abdeckungsbericht nach `target/site/jacoco/`.
- `scripts/unabgedeckt.sh TripService`: Zeilen und Verzweigungen, die die Tests nicht erreichen. Nach jedem `mvn -q verify` neu.
- Verboten: Tests löschen oder mit `@Ignore` abschalten, `-DskipTests`, Änderungen an `pom.xml`, `.opencode/`, `AGENTS.md`, `opencode.json`, `TripService_Original.java`.

## Struktur
- `src/main/java/de/sharpsharp/tripservice/trip/TripService.java`: der Legacy-Code, `getTripsByUser(User)`.
- `trip/TripDAO.java`: statische `findTripsByUser`, wirft im Test. `user/UserSession.java`: Singleton, `getLoggedUser()` wirft im Test.
- `user/User.java`: Freunde und Reisen. `trip/Trip.java`: leer. `exception/`: `UserNotLoggedInException`, `CollaboratorCallException`.
- `TripService_Original.java`: die Ausgangsfassung zum Vergleich. Bleibt unverändert.
- `src/test/java/de/sharpsharp/tripservice/`: JUnit 4, Hamcrest, Mockito. `trip/TripServiceTest.java` ist der Einstieg.

## Die Regel der Kata
- Produktivcode wird nur geändert, wenn er unter Test ist.
- Ausnahme: automatische Refactorings (Extract Method, Rename, Introduce Parameter), die nötig sind, um einen Test schreiben zu können. Sonst nichts.
- Erst unter Test bringen, dann umbauen. Nie beides in einem Schritt.

## Arbeitsweise
- Ein Schritt je Runde: ein Test oder ein Refactoring. Nie ein großer Umbau auf einmal.
- Vor jeder Änderung nennt der Coach den Schritt und die Technik, stellt eine Frage und ändert erst nach dem OK des Teilnehmers.
- Nach jeder Änderung `mvn -q verify`. Rot wird sofort behoben oder der Schritt zurückgenommen.
- Am Ende jeder Runde: geändert, Ergebnis von `mvn -q verify`, nächster Schritt als Vorschlag, eine Frage.

## Code-Regeln
- Java 17. Tests auf Englisch benannt, Name = Verhalten: `throwsWhenUserIsNotLoggedIn`.
- Hamcrest `assertThat(..., is(...))`. Mockito erst, wenn eine Abhängigkeit hereinreichbar ist; vorher Subclass-and-Override im Test.
- Methoden höchstens 20 Zeilen, eine Abstraktionsebene je Methode. Guard Clauses statt verschachtelter `if`.
