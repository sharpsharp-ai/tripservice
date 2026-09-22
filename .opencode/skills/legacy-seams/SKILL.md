---
name: legacy-seams
description: Techniken, um fest verdrahteten Legacy-Code unter Test zu bringen und danach unter grünen Tests umzubauen, mit Reihenfolge und Coaching-Regeln
---
# Nähte im Legacy-Code

Eine Naht (Seam, Michael Feathers) ist eine Stelle, an der sich das Verhalten ändern lässt, ohne den Code an dieser Stelle zu editieren. Im Test ersetzt man an der Naht die echte Abhängigkeit.

| Technik | Wann | Wie |
|---|---|---|
| Extract and Override Call | ein Aufruf in der Methode geht in Singleton, Static oder `new` | Aufruf per Extract Method in eine `protected` Methode ziehen; im Test eine Subklasse, die sie überschreibt |
| Subclass and Override Method | die Klasse hat schon eine überschreibbare Methode | Testsubklasse (`TestableTripService`) liefert feste Werte |
| Introduce Instance Delegator | statische Methode ohne Naht | Instanzmethode daneben, die an die statische delegiert; Aufrufer auf die Instanz umstellen |
| Parameterize Constructor | die Abhängigkeit ist eine Instanz | Konstruktor bekommt sie herein; Standardkonstruktor baut die echte |
| Parameterize Method | der Wert kommt aus der Umgebung (angemeldeter Benutzer) | Wert als Parameter hereinreichen; der alte Aufruf wird ein Einzeiler |
| Guard Clause | verschachteltes `if` mit `else` am Ende | Sonderfall zuerst, `throw` oder `return`, der Rest rückt eine Ebene hoch |
| Move Method | eine Frage über fremde Daten (`user.getFriends()` durchsuchen) | Methode dorthin, wo die Daten sind: `User.isFriendsWith(User)` |
| Replace Subclass by Mock | die Abhängigkeit ist hereinreichbar | Mockito-Mock statt Testsubklasse; Subklasse löschen |

## Reihenfolge
1. Kürzester Weg zuerst, tiefster zuletzt: der Zweig mit den wenigsten Abhängigkeiten bekommt den ersten Test.
2. Je Zweig: Test schreiben, rot sehen, Naht bauen (nur automatische Refactorings), grün sehen.
3. Erst wenn jede Zeile erreicht ist (`scripts/unabgedeckt.sh TripService`), beginnt der Umbau.
4. Beim Umbau von innen nach außen: zuerst die Struktur der Methode (Guard Clause, Move Method), dann die Abhängigkeiten (Konstruktor, Mock), zuletzt die Nähte entfernen.
5. Jeder Schritt einzeln, danach `mvn -q verify`. Rot heißt zurück, nicht weiter.

## Die Regel der Kata
Produktivcode wird nur geändert, wenn er unter Test ist. Ausnahme: automatische Refactorings der IDE, die nötig sind, um einen Test schreiben zu können (Extract Method, Rename, Introduce Parameter). Sonst nichts.

## Coaching
- Frage vor Antwort. Der Teilnehmer soll den Schritt selbst finden; der Coach gibt erst einen Hinweis, dann die Lösung.
- Jede Technik beim Namen nennen und in einem Satz sagen, warum sie hier passt.
- Nicht perfekt machen. Fertig ist, wenn `TripService` ohne Nähte testbar ist. Der Rest ist Bilanz.
