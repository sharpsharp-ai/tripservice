import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Clean-Code-Report 2.0: liest src/main/java und src/test/java mit dem Java-Parser des JDK, sucht die
 * Code Smells aus dem Katalog (Bloaters, Object-Orientation Abusers, Dispensables, Couplers, Readability,
 * Test Smells) und schreibt einen Bericht nach target/clean-code-report.html.
 * Kein Gate, keine Abhängigkeiten, JDK 17 reicht.
 *
 * Aufruf im Projektordner:  java .opencode/skills/clean-code-report/CleanCodeReport.java
 */
public class CleanCodeReport {

    static final String VERSION = "2.0";

    // ---- Der Katalog ------------------------------------------------------------

    enum Family {
        BLOATER("🎈", "Bloaters", "Code, der so groß geworden ist, dass ihn niemand mehr auf einmal überblickt.", 30),
        OO("🧩", "Object-Orientation Abusers", "Objekte, die keine sind: Typprüfungen statt Polymorphismus, Zustand am falschen Ort, offene Daten.", 15),
        DISPENSABLE("🗑️", "Dispensables", "Alles, was weg kann, ohne dass etwas fehlt: Kommentare, Kopien, Leichen.", 25),
        COUPLER("🔗", "Couplers", "Klassen, die zu viel voneinander wissen und in fremden Daten wühlen.", 15),
        READABILITY("👓", "Readability", "Clean Code: Struktur und Namen, die man beim ersten Lesen versteht.", 30),
        TEST("🧪", "Test Smells", "Tests, die nicht sagen, was sie prüfen, oder es gar nicht tun.", 15);

        final String emoji, title, about;
        final double cap;
        Family(String emoji, String title, String about, double cap) { this.emoji = emoji; this.title = title; this.about = about; this.cap = cap; }
    }

    enum Smell {
        // Bloaters
        LONG_METHOD(Family.BLOATER, "🐍", "Die Schlange", "Long Method", "Methode mit mehr als 20 Codezeilen", "2 + 1 je weitere 5 Zeilen, höchstens 15", "Extract Method: eine Methode, eine Abstraktionsebene", 15),
        LARGE_CLASS(Family.BLOATER, "🏰", "Die Burg", "Large Class", "Klasse mit mehr als 200 Zeilen", "5 + 1 je weitere 50 Zeilen, höchstens 10", "Extract Class: jede Klasse eine Verantwortung", 10),
        LONG_PARAMS(Family.BLOATER, "🎒", "Der Kofferträger", "Long Parameter List", "mehr als drei Parameter; Konstruktoren und Fabriken dürfen vier", "2 je Methode, höchstens 8", "Introduce Parameter Object oder Preserve Whole Object", 8),
        JUGGLER(Family.BLOATER, "🤹", "Der Jongleur", "Too Many Temps", "mehr als fünf lokale Variablen in einer Methode (Schleifenvariablen zählen nicht), oder eine Variable, die dreimal neu belegt wird", "1 je Variable über fünf; 2 je Mehrzweck-Variable; höchstens 10", "Replace Temp with Query, Split Temporary Variable, Extract Method", 10),
        PRIMITIVE(Family.BLOATER, "🪨", "Der Steinzeitmensch", "Primitive Obsession", "String oder int als Typ-Code: type, kind, status, state, mode, category, role, unit, currency", "2 je Feld oder Parameter, höchstens 8", "Replace Type Code with Enum, Replace Data Value with Object", 8),
        CLUMP(Family.BLOATER, "🧑‍🤝‍🧑", "Die Clique", "Data Clumps", "dieselben drei oder mehr Parameter in mehreren Methoden", "3 je Clique, höchstens 9", "Introduce Parameter Object: die Clique wird eine Klasse", 9),
        // Object-Orientation Abusers
        SWITCH(Family.OO, "🚦", "Der Weichensteller", "Switch Statements", "ein Wert dreimal mit festen Werten verglichen, switch über Text, switch über denselben Wert in mehreren Methoden, zwei instanceof in einer Methode", "3 je Fund, 4 je wiederholtem switch, höchstens 12", "Replace Conditional with Polymorphism, Replace Type Code with State/Strategy", 12),
        CAMPER(Family.OO, "🏕️", "Der Camper", "Temporary Field", "Feld, das nur eine einzige Methode benutzt und das sie erst setzt, bevor sie es liest", "2 je Feld, höchstens 6", "Extract Class oder lokale Variable: das Feld zieht dorthin, wo es lebt", 6),
        EXHIBITIONIST(Family.OO, "🩲", "Der Exhibitionist", "Public Mutable Field", "öffentliches, veränderbares Feld", "2 je Feld, 3 wenn static, höchstens 6", "Encapsulate Field: private, Verhalten statt Getter und Setter", 6),
        TRAP(Family.OO, "🪤", "Die Falle", "Reference Comparison", "String mit == oder != verglichen", "3 je Vergleich, höchstens 9", "equals(): Objekte vergleichen, nicht Referenzen", 9),
        // Dispensables
        TWINS(Family.DISPENSABLE, "👯", "Die Zwillinge", "Duplicate Code", "vier gleiche Codezeilen in Folge, auch über Dateien hinweg", "4 je Block, höchstens 16", "Extract Method: einmal schreiben, zweimal rufen", 16),
        ZOMBIE(Family.DISPENSABLE, "🧟", "Der Zombie", "Dead Code", "auskommentierter Code, private Methoden und Felder ohne Nutzer, ungenutzte Imports, Code nach return", "3 je Methode, 2 je Feld oder Codeleiche, 0,5 je Import, höchstens 10", "Löschen. Git erinnert sich.", 10),
        COMMENT(Family.DISPENSABLE, "🧸", "Der Erklärbär", "Comments", "kurzer Kommentar im Methodenrumpf (bis acht Wörter), der eine Zeile erklärt oder den Code wiederholt; lange Warum-Kommentare sind erlaubt", "1 je Kommentar, 0,5 wenn er nur wiederholt, höchstens 6", "Extract Variable oder Rename, dann den Kommentar löschen", 6),
        DEO(Family.DISPENSABLE, "🧴", "Der Deo-Kommentar", "Comments as Deodorant", "Etikett-Kommentar im Rumpf, der einen Absatz aus drei oder mehr Zeilen einleitet (ein Prosa-Kommentar erst ab acht Zeilen)", "2 je Absatz, höchstens 8", "Extract Method: der Kommentar ist der Name der neuen Methode", 8),
        LEAFLET(Family.DISPENSABLE, "💊", "Der Beipackzettel", "Comment Instead of Name", "Kommentar über einer Methode mit generischem Namen (helper, process, test5) oder einem kryptischen Feld; kurzes Etikett (bis sechs Wörter) über einem Namen, der es nicht trägt", "2 je Fund, höchstens 8", "Rename: die Wörter aus dem Kommentar in den Namen, dann den Kommentar löschen", 8),
        SPECULATIVE(Family.DISPENSABLE, "🔮", "Der Wahrsager", "Speculative Generality", "Parameter, den die Methode nie benutzt", "2 je Parameter, höchstens 6", "Remove Parameter: bauen, was gebraucht wird, nicht, was einmal gebraucht werden könnte", 6),
        LAZY(Family.DISPENSABLE, "🦥", "Der Faulpelz", "Lazy Class", "Klasse mit höchstens einer Methode, höchstens einem Feld und unter 15 Zeilen", "2 je Klasse, höchstens 4", "Inline Class: die Klasse zieht in ihren einzigen Nutzer", 4),
        DATA_CLASS(Family.DISPENSABLE, "🗂️", "Der Karteikasten", "Data Class", "Klasse nur aus Feldern, Konstruktoren, Gettern und Settern", "2 je Klasse, höchstens 4", "Move Method: das Verhalten zu den Daten holen", 4),
        // Couplers
        ENVY(Family.COUPLER, "👀", "Der Neider", "Feature Envy", "Methode greift dreimal oder öfter auf die Daten eines fremden Objekts zu und seltener auf eigene", "3 je Methode, höchstens 12", "Move Method: die Methode zieht zu den Daten, die sie benutzt", 12),
        CHAIN(Family.COUPLER, "⛓️", "Die Kette", "Message Chains", "drei Aufrufe in Folge, davon zwei Getter: a.getB().getC().getD()", "2 je Kette, höchstens 8", "Hide Delegate: der Nachbar liefert, was du brauchst", 8),
        MIDDLE_MAN(Family.COUPLER, "📞", "Der Vermittler", "Middle Man", "mehr als die Hälfte der Methoden reicht nur an ein Feld weiter", "3 je Klasse, höchstens 6", "Remove Middle Man: direkt mit dem Objekt reden", 6),
        // Readability
        ARROW(Family.READABILITY, "🏹", "Der Pfeil", "Deep Nesting", "mehr als drei Ebenen verschachtelt", "3 je Ebene über drei, höchstens 12", "Guard Clauses, Extract Method, Decompose Conditional", 12),
        COMPLEX(Family.READABILITY, "🌀", "Der Strudel", "High Cyclomatic Complexity", "zyklomatische Komplexität über 10", "1 je Punkt über 10, höchstens 12", "Extract Method, Map statt if-Kette, Polymorphismus", 12),
        MAGIC(Family.READABILITY, "🎩", "Der Zauberer", "Magic Numbers and Strings", "Zahlen außer 0 und 1 mitten in Ausdrücken; Texte, die verglichen werden oder mehrfach vorkommen. Nicht in Tests, nicht in main, nicht als Initialwert einer benannten Variablen", "1 je Zahl, 1 je verglichenem Text, 0,5 je wiederholtem Text, höchstens 12", "Extract Constant: der Name sagt, was der Wert bedeutet", 12),
        CRYPTIC(Family.READABILITY, "🕵️", "Agent X", "Bad Names", "Namen mit ein oder zwei Zeichen, tmp, data, foo, doIt, helper, Namen mit Zahl am Ende", "1 je Variable, 2 je Methode oder Klasse, höchstens 12", "Rename: der Name sagt, was das Ding ist oder tut", 12),
        PRINTER(Family.READABILITY, "🖨️", "Der Drucker", "Console Output", "System.out oder System.err im Produktivcode außerhalb von main", "2 je Aufruf, höchstens 6", "Rückgabewert, Exception oder Logger", 6),
        BLACK_HOLE(Family.READABILITY, "🕳️", "Das Schwarze Loch", "Exception Swallowing", "leerer catch, catch mit return, catch mit nur printStackTrace, catch (Exception) oder (Throwable)", "4 leer, 3 mit return, 2 sonst, höchstens 12", "Behandeln, weiterwerfen oder gar nicht fangen. Nie in einen Rückgabewert verwandeln.", 12),
        FLAG(Family.READABILITY, "🔀", "Der Schalter", "Flag Argument", "boolean-Parameter, der die Methode umschaltet", "2 je Parameter, höchstens 6", "Zwei Methoden statt einem Schalter, oder ein Enum", 6),
        // Test Smells
        TEST_GRUMP(Family.TEST, "🧪", "Der Test-Muffel", "Anonymous Test, Missing Assertion, Eager Test", "Tests namens test1, Tests ohne Prüfung, Tests mit mehr als acht Prüfungen", "1 namenlos, 3 ohne Prüfung, 2 gierig, höchstens 12", "Ein Test, eine Regel, ein Name, der sie nennt", 12),
        ACTOR(Family.TEST, "🎭", "Der Schauspieler", "Conditional Test Logic, Sleepy Test, Ignored Test", "if oder switch im Test, Schleife im Test, Thread.sleep, @Ignore, System.out im Test", "2 je if, 1 je Schleife, 2 je sleep oder @Ignore, 1 je System.out, höchstens 8", "Logik raus, Parametrisierung rein. @Ignore heißt löschen oder fixen.", 8);

        final Family family;
        final String emoji, monster, smell, rule, points, fix;
        final double cap;
        Smell(Family family, String emoji, String monster, String smell, String rule, String points, String fix, double cap) {
            this.family = family; this.emoji = emoji; this.monster = monster; this.smell = smell; this.rule = rule; this.points = points; this.fix = fix; this.cap = cap;
        }
    }

    static final Set<Smell> CLASS_LEVEL = Set.of(Smell.LARGE_CLASS, Smell.LAZY, Smell.DATA_CLASS, Smell.MIDDLE_MAN, Smell.CLUMP, Smell.CAMPER, Smell.EXHIBITIONIST);

    record Finding(Smell smell, String file, int line, String text, double penalty) {}

    record Param(String name, String type) {}

    static class Field {
        final String name, type; final int line; final Set<Modifier> mods; final boolean enumConstant, initialised;
        Field(String name, String type, int line, Set<Modifier> mods, boolean enumConstant, boolean initialised) { this.name = name; this.type = type; this.line = line; this.mods = mods; this.enumConstant = enumConstant; this.initialised = initialised; }
        boolean is(Modifier m) { return mods.contains(m); }
    }

    static class Klass {
        String name, file, kind, extendsClause = "";
        int start, end;
        boolean test, implementsSomething, isAbstract, ignored;
        final List<Field> fields = new ArrayList<>();
        final List<Method> methods = new ArrayList<>();
        int lines() { return end - start + 1; }
        boolean isPlainClass() { return kind.equals("CLASS"); }
    }

    /** Eine Methode mit allem, was der Rumpf-Scanner über sie herausgefunden hat. */
    static class Method {
        String file, name; Klass owner;
        int declLine, nameLine, start, end, bodyStart, bodyEnd, codeLines, depth, complexity = 1;
        boolean test, ctor, isStatic, isPrivate, isAbstract, isOverride, ignored, expectsException;
        List<Param> params = new ArrayList<>();
        Body body;
        int lines() { return end - start + 1; }
        String label() { return name + "()"; }
    }

    /** Sammelt, was der Rumpf-Scanner in einer Methode sieht. */
    static class Body {
        final List<String[]> locals = new ArrayList<>();          // name, type, line, "loop" wenn Schleifenvariable
        final Map<String, Integer> assigns = new HashMap<>();
        final List<Object[]> catches = new ArrayList<>();        // line, type, kind(0 leer, 1 return, 2 print, 3 breit)
        final List<Object[]> switches = new ArrayList<>();       // line, subject, cases, overText
        final Map<String, List<Integer>> comparisons = new LinkedHashMap<>();
        final List<Integer> instanceofs = new ArrayList<>();
        final List<Integer> stringEq = new ArrayList<>();
        final Map<String, Integer> foreign = new LinkedHashMap<>();
        int own;
        final List<Object[]> chains = new ArrayList<>();         // line, text, length
        final Set<String> invoked = new HashSet<>();
        final Set<String> identifiers = new HashSet<>();
        final List<Object[]> literals = new ArrayList<>();       // line, text, isString, compared
        final List<Integer> prints = new ArrayList<>(), sleeps = new ArrayList<>(), unreachable = new ArrayList<>();
        final List<Object[]> logic = new ArrayList<>();          // line, isLoop
        int asserts;
        int statements;                                           // Anweisungen direkt im Rumpf
        Object[] delegation;                                      // [fieldName, invokedName, argsAreParams] wenn der Rumpf nur weiterreicht
    }

    /** Eine Quelldatei: Originalzeilen, dazu der Text ohne Kommentare und Literalinhalte (gleiche Länge). */
    static class Source {
        final Path path;
        final String file;
        final boolean test;
        final List<String> lines;
        final String text;
        final String stripped;
        final List<int[]> commentRanges = new ArrayList<>();
        final List<int[]> stringLiterals = new ArrayList<>();
        final List<int[]> charLiterals = new ArrayList<>();
        final List<Klass> classes = new ArrayList<>();
        final List<Method> methods = new ArrayList<>();
        final List<String[]> imports = new ArrayList<>();         // simpleName, line
        int[] lineStart;

        Source(Path path, Path root, boolean test) throws IOException {
            this.path = path.toAbsolutePath().normalize();
            this.file = root.relativize(path).toString().replace('\\', '/');
            this.test = test;
            this.text = Files.readString(path, StandardCharsets.UTF_8);
            this.lines = Arrays.asList(text.split("\n", -1));
            this.stripped = strip();
            indexLines();
        }

        private String strip() {
            StringBuilder out = new StringBuilder(text.length());
            int i = 0, n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                    int s = i;
                    while (i < n && text.charAt(i) != '\n') { out.append(' '); i++; }
                    commentRanges.add(new int[]{s, i});
                } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                    int s = i;
                    int e = text.indexOf("*/", i + 2);
                    if (e < 0) e = n - 2;
                    while (i < e + 2 && i < n) { out.append(text.charAt(i) == '\n' ? '\n' : ' '); i++; }
                    commentRanges.add(new int[]{s, i});
                } else if (c == '"') {
                    boolean block = text.startsWith("\"\"\"", i);
                    int s = i;
                    out.append('"'); i++;
                    if (block) { out.append("\"\""); i += 2; }
                    while (i < n) {
                        char d = text.charAt(i);
                        if (d == '\\') { out.append("  "); i += 2; continue; }
                        if (!block && d == '"') break;
                        if (block && text.startsWith("\"\"\"", i)) { out.append("\"\""); i += 2; break; }
                        if (!block && d == '\n') break;
                        out.append(d == '\n' ? '\n' : ' '); i++;
                    }
                    if (i < n) { out.append('"'); i++; }
                    stringLiterals.add(new int[]{s, i});
                } else if (c == '\'') {
                    int s0 = i;
                    out.append('\''); i++;
                    while (i < n && text.charAt(i) != '\'' && text.charAt(i) != '\n') {
                        if (text.charAt(i) == '\\') { out.append(' '); i++; }
                        out.append(' '); i++;
                    }
                    if (i < n) { out.append('\''); i++; }
                    charLiterals.add(new int[]{s0, i});
                } else {
                    out.append(c); i++;
                }
            }
            return out.toString();
        }

        private void indexLines() {
            lineStart = new int[lines.size() + 1];
            int off = 0;
            for (int l = 0; l < lines.size(); l++) { lineStart[l] = off; off += lines.get(l).length() + 1; }
            lineStart[lines.size()] = off;
        }

        int lineOf(int offset) {
            int lo = 0, hi = lines.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (lineStart[mid] <= offset) lo = mid; else hi = mid - 1;
            }
            return lo + 1;
        }

        /** Der Code einer Zeile ohne Kommentare und Literalinhalte. */
        String code(int line) {
            if (line < 1 || line > lines.size()) return "";
            int from = lineStart[line - 1], to = Math.min(lineStart[line] - 1, stripped.length());
            return to <= from ? "" : stripped.substring(from, to).trim();
        }

        boolean isCode(int line) { return !code(line).isEmpty(); }

        /** Die Zeile ohne Kommentare, aber mit Literalen: für die Zwillingssuche. */
        String codeWithLiterals(int line) {
            if (line < 1 || line > lines.size()) return "";
            int from = lineStart[line - 1], to = Math.min(lineStart[line] - 1, text.length());
            StringBuilder out = new StringBuilder();
            for (int i = from; i < to; i++) {
                boolean comment = false;
                for (int[] r : commentRanges) if (r[0] <= i && i < r[1]) { comment = true; break; }
                if (!comment) out.append(text.charAt(i));
            }
            return out.toString().trim();
        }

        int depthAtLineStart(int line) {
            int d = 0;
            int end = Math.min(lineStart[line - 1], stripped.length());
            for (int i = 0; i < end; i++) {
                char c = stripped.charAt(i);
                if (c == '{') d++; else if (c == '}') d--;
            }
            return d;
        }

        Method methodAt(int line) {
            Method best = null;
            for (Method m : methods) {
                if (m.bodyStart > 0 && m.bodyStart <= line && line <= m.bodyEnd && (best == null || m.lines() < best.lines())) best = m;
            }
            return best;
        }

        String snippet(int line) {
            String s = line >= 1 && line <= lines.size() ? lines.get(line - 1).trim() : "";
            return s.length() > 90 ? s.substring(0, 87) + "…" : s;
        }
    }

    // ---- Parsen mit dem JDK-Compiler ---------------------------------------------

    final List<Finding> findings = new ArrayList<>();
    final List<Source> sources = new ArrayList<>();
    final List<Object[]> stringLiterals = new ArrayList<>();      // src, method, line, text, compared
    final Path root;

    CleanCodeReport(Path root) { this.root = root; }

    /** Dateien, die der Bericht übergeht: Muster aus .clean-code-ignore, eins je Zeile, relativ zum Projektordner. */
    List<java.nio.file.PathMatcher> ignored() throws IOException {
        Path f = root.resolve(".clean-code-ignore");
        List<java.nio.file.PathMatcher> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f)) {
            String p = line.trim();
            if (p.isEmpty() || p.startsWith("#")) continue;
            out.add(f.getFileSystem().getPathMatcher("glob:" + p));
        }
        return out;
    }

    void analyse() throws IOException {
        List<java.nio.file.PathMatcher> skip = ignored();
        for (Path dir : List.of(root.resolve("src/main/java"), root.resolve("src/test/java"))) {
            if (!Files.isDirectory(dir)) continue;
            boolean test = dir.endsWith(Paths.get("src", "test", "java"));
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                    Path rel = root.relativize(p);
                    if (skip.stream().anyMatch(m -> m.matches(rel) || m.matches(rel.getFileName()))) continue;
                    sources.add(new Source(p, root, test));
                }
            }
        }
        if (sources.isEmpty()) return;
        parse();
        for (Source src : sources) {
            for (Klass k : src.classes) classSmells(src, k);
            for (Method m : src.methods) methodSmells(src, m);
            importSmells(src);
            commentSmells(src);
        }
        magicStrings();
        clumps();
        repeatedSwitches();
        duplicates();
    }

    /** Texte sind magisch, wenn sie verglichen werden oder mehrfach vorkommen. Meldungen und Formate sind es nicht. */
    void magicStrings() {
        Map<String, Long> count = stringLiterals.stream().collect(Collectors.groupingBy(o -> (String) o[3], Collectors.counting()));
        for (Object[] o : stringLiterals) {
            boolean compared = (Boolean) o[4];
            if (!compared && count.get((String) o[3]) < 2) continue;
            add(Smell.MAGIC, (Source) o[0], (Integer) o[2], "Text " + o[3] + " in " + ((Method) o[1]).label() + (compared ? " wird verglichen" : " kommt " + count.get((String) o[3]) + "-mal vor"), compared ? 1 : 0.5);
        }
    }

    void parse() throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("Kein JDK gefunden: der Bericht braucht javac (JDK 17 oder neuer), eine JRE reicht nicht.");
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<File> files = sources.stream().map(s -> s.path.toFile()).collect(Collectors.toList());
            JavacTask task = (JavacTask) jc.getTask(null, fm, d -> {}, List.of("-proc:none"), null, fm.getJavaFileObjectsFromFiles(files));
            SourcePositions sp = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree cu : task.parse()) {
                Path p = Paths.get(cu.getSourceFile().toUri()).toAbsolutePath().normalize();
                for (Source src : sources) if (src.path.equals(p)) new Collector(src, cu, sp).run();
            }
        }
    }

    /** Baut aus dem Syntaxbaum einer Datei die Klassen und Methoden. */
    class Collector {
        final Source src; final CompilationUnitTree cu; final SourcePositions sp;
        Collector(Source src, CompilationUnitTree cu, SourcePositions sp) { this.src = src; this.cu = cu; this.sp = sp; }

        int line(Tree t) { long p = sp.getStartPosition(cu, t); return p < 0 ? 1 : (int) cu.getLineMap().getLineNumber(p); }
        int endLine(Tree t) { long p = sp.getEndPosition(cu, t); return p < 0 ? line(t) : (int) cu.getLineMap().getLineNumber(Math.max(0, p - 1)); }
        int offset(Tree t) { long p = sp.getStartPosition(cu, t); return p < 0 ? 0 : (int) p; }

        void run() {
            for (ImportTree imp : cu.getImports()) {
                String q = imp.getQualifiedIdentifier().toString();
                if (q.endsWith(".*")) continue;
                src.imports.add(new String[]{q.substring(q.lastIndexOf('.') + 1), String.valueOf(line(imp))});
            }
            for (Tree t : cu.getTypeDecls()) if (t instanceof ClassTree ct) collect(ct);
        }

        void collect(ClassTree ct) {
            Klass k = new Klass();
            k.name = ct.getSimpleName().toString();
            k.file = src.file; k.kind = ct.getKind().name(); k.test = src.test;
            k.start = line(ct); k.end = endLine(ct);
            if (ct.getExtendsClause() != null) k.extendsClause = ct.getExtendsClause().toString();
            k.implementsSomething = !ct.getImplementsClause().isEmpty();
            k.isAbstract = ct.getModifiers().getFlags().contains(Modifier.ABSTRACT);
            for (AnnotationTree a : ct.getModifiers().getAnnotations()) { String an = a.getAnnotationType().toString().replaceAll(".*\\.", ""); if (an.equals("Ignore") || an.equals("Disabled")) k.ignored = true; }
            classTrees.put(k, ct);
            src.classes.add(k);
            for (Tree member : ct.getMembers()) {
                if (member instanceof VariableTree v) {
                    boolean enumConst = k.kind.equals("ENUM") && v.getType().toString().equals(k.name);
                    k.fields.add(new Field(v.getName().toString(), v.getType().toString(), line(v), v.getModifiers().getFlags(), enumConst, v.getInitializer() != null));
                } else if (member instanceof MethodTree mt) {
                    Method m = method(mt, k);
                    k.methods.add(m); src.methods.add(m);
                } else if (member instanceof ClassTree inner) {
                    collect(inner);
                }
            }
        }

        Method method(MethodTree mt, Klass k) {
            Method m = new Method();
            m.file = src.file; m.owner = k;
            m.ctor = mt.getName().toString().equals("<init>");
            m.name = m.ctor ? k.name : mt.getName().toString();
            m.declLine = line(mt); m.start = m.declLine; m.end = endLine(mt);
            Set<Modifier> flags = mt.getModifiers().getFlags();
            m.isStatic = flags.contains(Modifier.STATIC); m.isPrivate = flags.contains(Modifier.PRIVATE);
            m.isAbstract = flags.contains(Modifier.ABSTRACT) || mt.getBody() == null;
            for (AnnotationTree a : mt.getModifiers().getAnnotations()) {
                String an = a.getAnnotationType().toString().replaceAll(".*\\.", "");
                if (an.equals("Override")) m.isOverride = true;
                if (an.equals("Test") || an.endsWith("Test")) { m.test = true; if (a.getArguments().toString().contains("expected")) m.expectsException = true; }
                if (an.equals("Ignore") || an.equals("Disabled")) m.ignored = true;
            }
            for (VariableTree p : mt.getParameters()) m.params.add(new Param(p.getName().toString(), p.getType().toString()));
            Matcher nm = Pattern.compile("\\b" + Pattern.quote(m.name) + "\\s*\\(").matcher(src.stripped);
            m.nameLine = nm.find(offset(mt)) ? src.lineOf(nm.start()) : m.declLine;
            m.start = m.nameLine;
            if (mt.getBody() != null) {
                m.bodyStart = line(mt.getBody()); m.bodyEnd = endLine(mt.getBody());
                if (m.bodyStart == m.bodyEnd) m.codeLines = 1;
                else for (int l = m.bodyStart + 1; l < m.bodyEnd; l++) if (src.isCode(l)) m.codeLines++;
                BodyScanner bs = new BodyScanner(m);
                bs.scan(mt.getBody(), null);
                m.body = bs.body; m.depth = bs.maxDepth; m.complexity = bs.complexity;
                List<? extends StatementTree> st = mt.getBody().getStatements();
                m.body.statements = st.size();
                if (st.size() == 1) m.body.delegation = delegation(st.get(0), k, m);
            }
            return m;
        }

        /** Reicht die einzige Anweisung nur an ein Feld weiter? Dann Feldname, gerufene Methode und ob nur die Parameter durchgereicht werden. */
        Object[] delegation(StatementTree st, Klass k, Method m) {
            ExpressionTree e = st instanceof ReturnTree r ? r.getExpression() : st instanceof ExpressionStatementTree es ? es.getExpression() : null;
            if (!(e instanceof MethodInvocationTree inv) || !(inv.getMethodSelect() instanceof MemberSelectTree ms)) return null;
            ExpressionTree base = ms.getExpression();
            String name = base instanceof IdentifierTree id ? id.getName().toString() : base instanceof MemberSelectTree th && th.getExpression().toString().equals("this") ? th.getIdentifier().toString() : null;
            if (name == null) return null;
            Set<String> params = m.params.stream().map(Param::name).collect(Collectors.toSet());
            boolean argsAreParams = inv.getArguments().stream().allMatch(a -> a instanceof IdentifierTree id && params.contains(id.getName().toString()));
            for (Field f : k.fields) if (f.name.equals(name)) return new Object[]{name, ms.getIdentifier().toString(), argsAreParams, f.type};
            return null;
        }

        /** Läuft durch einen Methodenrumpf und zählt, was die Regeln brauchen. */
        class BodyScanner extends TreeScanner<Void, Void> {
            final Method m; final Body body = new Body();
            final Set<String> ownFields = new HashSet<>(), ownMethods = new HashSet<>(), paramNames = new HashSet<>();
            final Set<Tree> handled = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Tree> chainSeen = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Tree> skipOwn = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Tree> named = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Tree> compared = Collections.newSetFromMap(new IdentityHashMap<>());
            int depth, maxDepth, complexity = 1;
            boolean loopHeader;

            BodyScanner(Method m) {
                this.m = m;
                for (Field f : m.owner.fields) ownFields.add(f.name);
                for (Tree t : classTrees.get(m.owner).getMembers()) if (t instanceof MethodTree mt) ownMethods.add(mt.getName().toString());
                for (Param p : m.params) paramNames.add(p.name());
            }

            void nested(Tree t) { if (t == null) return; depth++; maxDepth = Math.max(maxDepth, depth); scan(t, null); depth--; }
            boolean shadowed(String name) { if (paramNames.contains(name)) return true; for (String[] l : body.locals) if (l[0].equals(name)) return true; return false; }

            @Override public Void visitIf(IfTree t, Void p) {
                complexity++; body.logic.add(new Object[]{line(t), false});
                scan(t.getCondition(), p); nested(t.getThenStatement());
                StatementTree e = t.getElseStatement();
                if (e instanceof IfTree) scan(e, p); else nested(e);
                return null;
            }
            @Override public Void visitForLoop(ForLoopTree t, Void p) { complexity++; body.logic.add(new Object[]{line(t), true}); loopHeader = true; scan(t.getInitializer(), p); loopHeader = false; scan(t.getCondition(), p); scan(t.getUpdate(), p); nested(t.getStatement()); return null; }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void p) { complexity++; body.logic.add(new Object[]{line(t), true}); loopHeader = true; scan(t.getVariable(), p); loopHeader = false; scan(t.getExpression(), p); nested(t.getStatement()); return null; }
            @Override public Void visitWhileLoop(WhileLoopTree t, Void p) { complexity++; body.logic.add(new Object[]{line(t), true}); scan(t.getCondition(), p); nested(t.getStatement()); return null; }
            @Override public Void visitDoWhileLoop(DoWhileLoopTree t, Void p) { complexity++; body.logic.add(new Object[]{line(t), true}); nested(t.getStatement()); scan(t.getCondition(), p); return null; }
            @Override public Void visitSwitch(SwitchTree t, Void p) { switchLike(t, t.getExpression(), t.getCases()); return null; }
            @Override public Void visitSwitchExpression(SwitchExpressionTree t, Void p) { switchLike(t, t.getExpression(), t.getCases()); return null; }
            void switchLike(Tree t, ExpressionTree subject, List<? extends CaseTree> cases) {
                body.logic.add(new Object[]{line(t), false});
                scan(subject, null);
                boolean overText = false; int labels = 0;
                for (CaseTree c : cases) {
                    List<? extends ExpressionTree> ex = c.getExpressions();
                    if (!ex.isEmpty()) { complexity++; labels++; }
                    for (ExpressionTree e : ex) if (e instanceof LiteralTree lt && lt.getKind() == Tree.Kind.STRING_LITERAL) overText = true;
                    compared.addAll(ex);
                    scan(ex, null);
                    depth++; maxDepth = Math.max(maxDepth, depth);
                    scan(c.getStatements(), null); scan(c.getBody(), null);
                    depth--;
                }
                body.switches.add(new Object[]{line(t), unparen(subject).toString().replaceAll("\\s+", ""), labels, overText});
            }
            @Override public Void visitTry(TryTree t, Void p) { scan(t.getResources(), p); nested(t.getBlock()); scan(t.getCatches(), p); nested(t.getFinallyBlock()); return null; }
            @Override public Void visitCatch(CatchTree t, Void p) {
                complexity++;
                String type = t.getParameter().getType().toString();
                List<? extends StatementTree> st = t.getBlock().getStatements();
                int kind = -1;
                if (st.isEmpty()) kind = 0;
                else if (st.size() == 1 && st.get(0) instanceof ReturnTree) kind = 1;
                else if (st.stream().allMatch(s -> s.toString().matches("(?s).*(printStackTrace|System\\.(out|err)\\.print).*"))) kind = 2;
                else if (type.matches("(java\\.lang\\.)?(Exception|Throwable|RuntimeException)")) kind = 3;
                body.catches.add(new Object[]{line(t), type, kind});
                depth++; maxDepth = Math.max(maxDepth, depth); scan(t.getBlock(), p); depth--;
                return null;
            }
            @Override public Void visitConditionalExpression(ConditionalExpressionTree t, Void p) { complexity++; return super.visitConditionalExpression(t, p); }
            @Override public Void visitLambdaExpression(LambdaExpressionTree t, Void p) { scan(t.getBody(), p); return null; }
            @Override public Void visitAnnotation(AnnotationTree t, Void p) { return null; }
            @Override public Void visitBlock(BlockTree t, Void p) {
                boolean dead = false;
                for (StatementTree s : t.getStatements()) {
                    if (dead) { body.unreachable.add(line(s)); dead = false; }
                    if (s instanceof ReturnTree || s instanceof ThrowTree || s instanceof BreakTree || s instanceof ContinueTree) dead = true;
                }
                return super.visitBlock(t, p);
            }
            @Override public Void visitVariable(VariableTree t, Void p) {
                body.locals.add(new String[]{t.getName().toString(), t.getType().toString(), String.valueOf(line(t)), loopHeader ? "loop" : ""});
                if (t.getInitializer() != null) named.add(unparen(t.getInitializer()));
                scan(t.getInitializer(), p);
                return null;
            }
            @Override public Void visitAssignment(AssignmentTree t, Void p) {
                if (t.getVariable() instanceof IdentifierTree id) {
                    String n = id.getName().toString();
                    boolean self = Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(t.getExpression().toString()).find();
                    if (!self) body.assigns.merge(n, 1, Integer::sum);
                    if (ownFields.contains(n) && !shadowed(n)) { body.identifiers.add(n); body.own++; }
                    scan(t.getExpression(), p);
                    return null;
                }
                return super.visitAssignment(t, p);
            }
            @Override public Void visitBinary(BinaryTree t, Void p) {
                Tree.Kind k = t.getKind();
                if (k == Tree.Kind.CONDITIONAL_AND || k == Tree.Kind.CONDITIONAL_OR) complexity++;
                if (k == Tree.Kind.EQUAL_TO || k == Tree.Kind.NOT_EQUAL_TO) {
                    ExpressionTree l = unparen(t.getLeftOperand()), r = unparen(t.getRightOperand());
                    if (isStringLiteral(l) || isStringLiteral(r)) body.stringEq.add(line(t));
                    compared.add(l); compared.add(r);
                    if (isConstantLike(r) && !isConstantLike(l)) compared(l, t); else if (isConstantLike(l) && !isConstantLike(r)) compared(r, t);
                }
                return super.visitBinary(t, p);
            }
            void compared(ExpressionTree subject, Tree at) {
                String key = subject.toString().replaceAll("\\s+", "");
                if (key.equals("null") || key.matches("-?\\d+")) return;
                body.comparisons.computeIfAbsent(key, x -> new ArrayList<>()).add(line(at));
            }
            @Override public Void visitInstanceOf(InstanceOfTree t, Void p) { body.instanceofs.add(line(t)); return super.visitInstanceOf(t, p); }
            @Override public Void visitLiteral(LiteralTree t, Void p) {
                if (named.contains(t)) return null;
                Object v = t.getValue();
                switch (t.getKind()) {
                    case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> {
                        String s = String.valueOf(v);
                        if (!s.matches("0|1|0\\.0|1\\.0")) body.literals.add(new Object[]{line(t), s, false, compared.contains(t)});
                    }
                    case STRING_LITERAL -> { if (v != null && v.toString().length() > 2) body.literals.add(new Object[]{line(t), "\"" + v + "\"", true, compared.contains(t)}); }
                    default -> { }
                }
                return null;
            }
            @Override public Void visitMemberReference(MemberReferenceTree t, Void p) { body.invoked.add(t.getName().toString()); return super.visitMemberReference(t, p); }
            @Override public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                ExpressionTree sel = t.getMethodSelect();
                if (sel instanceof MemberSelectTree ms) {
                    handled.add(ms);
                    String id = ms.getIdentifier().toString();
                    ExpressionTree base = unparen(ms.getExpression());
                    String baseText = base.toString().replaceAll("\\s+", "");
                    body.invoked.add(id);
                    if (baseText.equals("System.out") || baseText.equals("System.err")) body.prints.add(line(t));
                    else if (baseText.equals("Thread") && id.equals("sleep")) body.sleeps.add(line(t));
                    if (id.startsWith("assert") || id.equals("verify") || id.equals("fail")) body.asserts++;
                    if ((id.equals("equals") || id.equals("equalsIgnoreCase") || id.equals("startsWith") || id.equals("endsWith") || id.equals("contains") || id.equals("matches")) && t.getArguments().size() == 1) {
                        ExpressionTree arg = unparen(t.getArguments().get(0));
                        compared.add(arg); compared.add(base);
                        if (id.startsWith("equals")) { if (isConstantLike(arg)) compared(base, t); else if (isConstantLike(base)) compared(arg, t); }
                    }
                    if (baseText.equals("this")) body.own++;
                    else if (isGetterLike(id) && isForeignBase(base)) foreignAccess(base, baseText);
                    chain(t);
                    scan(base, p);
                } else {
                    scan(sel, p);
                    if (sel instanceof IdentifierTree id) {
                        String n = id.getName().toString();
                        body.invoked.add(n);
                        if (ownMethods.contains(n)) body.own++;
                        if (n.startsWith("assert") || n.equals("verify") || n.equals("fail")) body.asserts++;
                    }
                }
                scan(t.getTypeArguments(), p); scan(t.getArguments(), p);
                return null;
            }
            void chain(MethodInvocationTree t) {
                if (chainSeen.contains(t)) return;
                int n = 0, getters = 0; ExpressionTree cur = t;
                while (cur instanceof MethodInvocationTree inv && inv.getMethodSelect() instanceof MemberSelectTree ms) {
                    chainSeen.add(inv); n++;
                    if (isGetterLike(ms.getIdentifier().toString())) getters++;
                    cur = unparen(ms.getExpression());
                }
                if (n >= 3 && getters >= 2) {
                    String text = t.toString().replaceAll("\\s+", " ");
                    body.chains.add(new Object[]{line(t), text.length() > 70 ? text.substring(0, 67) + "…" : text, n});
                }
            }
            @Override public Void visitMemberSelect(MemberSelectTree t, Void p) {
                if (!handled.contains(t)) {
                    String id = t.getIdentifier().toString();
                    ExpressionTree base = unparen(t.getExpression());
                    String baseText = base.toString().replaceAll("\\s+", "");
                    if (baseText.equals("this")) { body.own++; body.identifiers.add(id); }
                    else if (Character.isLowerCase(id.charAt(0)) && !id.equals("length") && !id.equals("class") && isForeignBase(base)) foreignAccess(base, baseText);
                }
                scan(t.getExpression(), p);
                return null;
            }
            void foreignAccess(ExpressionTree base, String key) {
                body.foreign.merge(key, 1, Integer::sum);
                ExpressionTree rootId = base;
                while (true) {
                    if (rootId instanceof MemberSelectTree ms) rootId = unparen(ms.getExpression());
                    else if (rootId instanceof MethodInvocationTree inv && inv.getMethodSelect() instanceof MemberSelectTree ms) rootId = unparen(ms.getExpression());
                    else if (rootId instanceof ArrayAccessTree aa) rootId = unparen(aa.getExpression());
                    else break;
                }
                if (rootId instanceof IdentifierTree) skipOwn.add(rootId);
            }
            boolean isForeignBase(ExpressionTree base) {
                if (base instanceof IdentifierTree id) { String n = id.getName().toString(); return !Character.isUpperCase(n.charAt(0)) && !n.equals("this") && !n.equals("super"); }
                if (base instanceof MemberSelectTree ms) return !ms.toString().matches("[A-Z][\\w.]*") && !ms.getExpression().toString().equals("this");
                return base instanceof MethodInvocationTree || base instanceof ArrayAccessTree;
            }
            @Override public Void visitIdentifier(IdentifierTree t, Void p) {
                String n = t.getName().toString();
                body.identifiers.add(n);
                if (ownFields.contains(n) && !shadowed(n) && !skipOwn.contains(t)) body.own++;
                return null;
            }
        }

        final Map<Klass, ClassTree> classTrees = new IdentityHashMap<>();
    }

    static ExpressionTree unparen(ExpressionTree e) { while (e instanceof ParenthesizedTree p) e = p.getExpression(); return e; }
    static boolean isStringLiteral(ExpressionTree e) { return e instanceof LiteralTree l && l.getKind() == Tree.Kind.STRING_LITERAL; }
    static boolean isConstantLike(ExpressionTree e) {
        if (e instanceof LiteralTree l) return l.getKind() != Tree.Kind.NULL_LITERAL && l.getKind() != Tree.Kind.BOOLEAN_LITERAL && l.getKind() != Tree.Kind.CHAR_LITERAL;
        String s = e.toString();
        return s.matches("[A-Z][A-Z0-9_]+") || s.matches("[A-Z]\\w*\\.[A-Z][A-Z0-9_]*");
    }
    static boolean isGetterLike(String id) { return id.matches("(get|is|has|set)[A-Z]\\w*"); }

    // ---- Die Regeln ---------------------------------------------------------------

    static final Set<String> OK_SHORT = Set.of("i", "j", "k", "e", "id", "ex", "io", "x", "y", "to");
    static final Set<String> CRYPTIC_NAMES = Set.of("tmp", "temp", "foo", "bar", "baz", "stuff", "data", "obj", "val", "res", "ret", "str",
        "num", "cnt", "arr", "lst", "cur", "flag", "var", "thing", "info", "misc", "helper", "handle", "process", "manage", "doit", "dostuff",
        "dosomething", "calc", "func", "fn", "method", "work", "util", "utils");
    static final Set<String> CRYPTIC_CLASSES = Set.of("Util", "Utils", "Helper", "Helpers", "Manager", "Data", "Info", "Misc", "Stuff", "Foo", "Bar", "Baz", "Thing", "Things", "Common");
    static final Pattern TYPE_CODE = Pattern.compile("^(type|kind|status|state|mode|category|role|unit|currency)$|^[a-z]+(Type|Kind|Status|State|Mode|Category|Role)$");
    static final Set<String> STOP = Set.of("the", "and", "for", "with", "this", "that", "from", "into", "are", "not", "but", "has", "have", "its", "all", "any", "get", "set", "gets", "sets", "returns", "return",
        "der", "die", "das", "und", "oder", "ein", "eine", "einen", "einem", "einer", "des", "dem", "den", "von", "zum", "zur", "für", "mit", "ist", "sind", "wird", "werden", "auf", "aus", "bei", "nicht", "wenn", "dann", "als",
        "auch", "hier", "sich", "alle", "alles", "gibt", "zurück", "liefert", "wieder", "nur", "noch", "kann", "soll", "muss", "wir", "man", "method", "methode", "function", "funktion", "class", "klasse", "value", "wert",
        "param", "params", "throws", "see", "todo", "fixme", "xxx", "note", "hinweis", "author", "since", "version", "override", "just", "sure", "then", "when", "given", "should", "test", "tests", "case", "old", "new", "alte", "neue");
    static final Set<String> TEST_STAGE = Set.of("given", "when", "then", "arrange", "act", "assert", "setup", "verify", "expect", "cleanup");

    void add(Smell smell, Source src, int line, String text, double penalty) { findings.add(new Finding(smell, src.file, line, text, penalty)); }

    static boolean crypticVar(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return (name.length() <= 2 && !OK_SHORT.contains(n)) || CRYPTIC_NAMES.contains(n) || name.matches("[a-z]+\\d+");
    }
    static boolean genericMethod(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return name.length() <= 2 || CRYPTIC_NAMES.contains(n) || n.matches("(do|handle|process|manage|calc|compute|run|execute|check|make)\\d*")
            || n.matches("(do|handle|process|calc)(it|stuff|something|things|all|work)\\d*") || name.matches("[a-z]+\\d+");
    }

    void methodSmells(Source src, Method m) {
        Body b = m.body;
        if (b == null) return;
        boolean testCode = src.test || m.test;

        if (m.codeLines > 20) add(Smell.LONG_METHOD, src, m.start, m.label() + " hat " + m.codeLines + " Codezeilen", Math.min(Smell.LONG_METHOD.cap, 2 + (m.codeLines - 20) / 5.0));
        if (m.depth > 3) add(Smell.ARROW, src, m.start, m.label() + " verschachtelt " + m.depth + " Ebenen tief", Math.min(Smell.ARROW.cap, 3.0 * (m.depth - 3)));
        if (m.complexity > 10) add(Smell.COMPLEX, src, m.start, m.label() + " hat Komplexität " + m.complexity, Math.min(Smell.COMPLEX.cap, (double) (m.complexity - 10)));
        boolean builder = m.ctor || (m.isStatic && m.name.matches("(create|of|with|from|build|new)\\w*"));
        if (!src.test && m.params.size() > (builder ? 4 : 3)) add(Smell.LONG_PARAMS, src, m.start, m.label() + " nimmt " + m.params.size() + " Parameter", 2);

        List<String[]> temps = b.locals.stream().filter(l -> !l[3].equals("loop")).collect(Collectors.toList());
        if (temps.size() > 5) {
            String names = temps.stream().map(l -> l[0]).limit(6).collect(Collectors.joining(", ")) + (temps.size() > 6 ? " …" : "");
            add(Smell.JUGGLER, src, m.start, m.label() + " jongliert mit " + temps.size() + " Variablen: " + names, Math.min(Smell.JUGGLER.cap, 1.0 + (temps.size() - 5)));
        }
        for (Map.Entry<String, Integer> e : b.assigns.entrySet()) {
            if (e.getValue() >= 3 && b.locals.stream().anyMatch(l -> l[0].equals(e.getKey())))
                add(Smell.JUGGLER, src, lineOfLocal(b, e.getKey(), m.start), "Variable " + e.getKey() + " in " + m.label() + " wird " + e.getValue() + "-mal neu belegt: eine Variable, ein Zweck", 2);
        }

        if (!m.ctor && !m.test && !m.isOverride && genericMethod(m.name)) add(Smell.CRYPTIC, src, m.start, "Methode " + m.label() + " sagt nicht, was sie tut", 2);
        for (Param p : m.params) if (crypticVar(p.name())) add(Smell.CRYPTIC, src, m.start, "Parameter " + p.name() + " in " + m.label(), 1);
        Set<String> seen = new HashSet<>();
        for (String[] l : b.locals) if (crypticVar(l[0]) && seen.add(l[0])) add(Smell.CRYPTIC, src, Integer.parseInt(l[2]), "Variable " + l[0] + " in " + m.label(), 1);

        if (!testCode) {
            if (!m.ctor && !m.isOverride && !m.name.startsWith("set"))
                for (Param p : m.params) if (p.type().matches("boolean|Boolean")) add(Smell.FLAG, src, m.start, "Parameter boolean " + p.name() + " in " + m.label() + ": die Methode tut zwei Dinge", 2);
            for (Param p : m.params) if (p.type().matches("String|int|long|char|Integer") && TYPE_CODE.matcher(p.name()).find())
                add(Smell.PRIMITIVE, src, m.start, "Parameter " + p.type() + " " + p.name() + " in " + m.label() + ": ein Enum sagt, welche Werte erlaubt sind", 2);
            if (!m.name.equals("main")) for (Object[] lit : b.literals) {
                if ((Boolean) lit[2]) stringLiterals.add(new Object[]{src, m, lit[0], lit[1], lit[3]});
                else add(Smell.MAGIC, src, (Integer) lit[0], "Zahl " + lit[1] + " in " + m.label(), 1);
            }
            for (Object[] c : b.catches) {
                int kind = (Integer) c[2]; String type = (String) c[1];
                if (kind == 0) add(Smell.BLACK_HOLE, src, (Integer) c[0], "catch (" + type + ") schluckt den Fehler: leerer Block", 4);
                else if (kind == 1) add(Smell.BLACK_HOLE, src, (Integer) c[0], "catch (" + type + ") verwandelt den Fehler in einen Rückgabewert", 3);
                else if (kind == 2) add(Smell.BLACK_HOLE, src, (Integer) c[0], "catch (" + type + ") druckt den Fehler nur aus", 2);
                else if (kind == 3) add(Smell.BLACK_HOLE, src, (Integer) c[0], "catch (" + type + ") fängt alles, auch Programmierfehler", 2);
            }
            if (!m.name.equals("main")) for (Integer l : b.prints) add(Smell.PRINTER, src, l, src.snippet(l), 2);
            for (Integer l : b.stringEq) add(Smell.TRAP, src, l, "String mit == verglichen: " + src.snippet(l), 3);
            for (Map.Entry<String, List<Integer>> e : b.comparisons.entrySet())
                if (e.getValue().size() >= 3) add(Smell.SWITCH, src, e.getValue().get(0), e.getKey() + " wird in " + m.label() + " " + e.getValue().size() + "-mal mit festen Werten verglichen", 3);
            for (Object[] s : b.switches) if ((Boolean) s[3]) add(Smell.SWITCH, src, (Integer) s[0], "switch über Text in " + m.label() + ": ein Enum oder Polymorphismus", 3);
            if (b.instanceofs.size() >= 2) add(Smell.SWITCH, src, b.instanceofs.get(0), m.label() + " prüft " + b.instanceofs.size() + "-mal instanceof", 3);
            if (!m.ctor && !m.name.matches("equals|hashCode|toString|compareTo")) {
                Map.Entry<String, Integer> top = b.foreign.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
                if (top != null && top.getValue() >= 3 && top.getValue() > b.own)
                    add(Smell.ENVY, src, m.start, m.label() + " greift " + top.getValue() + "-mal auf Daten von " + top.getKey() + " zu, " + b.own + "-mal auf eigene", 3);
            }
            for (Object[] ch : b.chains) add(Smell.CHAIN, src, (Integer) ch[0], "Kette mit " + ch[2] + " Gliedern: " + ch[1], 2);
            if (!m.isOverride && !m.isAbstract && !m.ctor && b.statements > 0 && !m.name.equals("main"))
                for (Param p : m.params) if (!b.identifiers.contains(p.name())) add(Smell.SPECULATIVE, src, m.start, "Parameter " + p.name() + " in " + m.label() + " wird nie benutzt", 2);
        }
        for (Integer l : b.unreachable) add(Smell.ZOMBIE, src, l, "Code nach return, throw oder break läuft nie: " + src.snippet(l), 2);

        if (m.test) {
            if (m.name.matches("^(test|check|verify|it|should|case)\\d*$") || m.name.matches("^test[A-Z]?$"))
                add(Smell.TEST_GRUMP, src, m.start, m.label() + ": der Name sagt nicht, welche Regel geprüft wird", 1);
            int asserts = b.asserts;
            for (Method other : m.owner.methods) if (other != m && other.body != null && b.invoked.contains(other.name)) asserts += other.body.asserts;
            if (asserts == 0 && !m.expectsException) add(Smell.TEST_GRUMP, src, m.start, m.label() + " prüft nichts", 3);
            if (asserts > 8) add(Smell.TEST_GRUMP, src, m.start, m.label() + " prüft " + asserts + " Dinge auf einmal", 2);
            if (m.ignored) add(Smell.ACTOR, src, m.declLine, m.label() + " ist abgeschaltet: der Test läuft nicht", 2);
            Object[] branch = b.logic.stream().filter(l -> !(Boolean) l[1]).findFirst().orElse(null);
            Object[] loop = b.logic.stream().filter(l -> (Boolean) l[1]).findFirst().orElse(null);
            if (branch != null) add(Smell.ACTOR, src, (Integer) branch[0], m.label() + " verzweigt: ein Test, ein Pfad", 2);
            else if (loop != null) add(Smell.ACTOR, src, (Integer) loop[0], m.label() + " enthält eine Schleife: jeder Fall ein eigener Test, oder parametrisiert", 1);
            for (Integer l : b.sleeps) add(Smell.ACTOR, src, l, m.label() + " wartet mit Thread.sleep", 2);
            for (Integer l : b.prints) add(Smell.ACTOR, src, l, "System.out in " + m.label() + ": ein Test prüft, er druckt nicht", 1);
        } else if (src.test) {
            for (Integer l : b.prints) add(Smell.ACTOR, src, l, "System.out in " + m.label() + ": ein Test prüft, er druckt nicht", 1);
        }
    }

    static int lineOfLocal(Body b, String name, int fallback) {
        for (String[] l : b.locals) if (l[0].equals(name)) return Integer.parseInt(l[2]);
        return fallback;
    }

    void classSmells(Source src, Klass k) {
        int limit = k.test ? 400 : 200;
        if (k.lines() > limit) add(Smell.LARGE_CLASS, src, k.start, k.name + " hat " + k.lines() + " Zeilen", Math.min(Smell.LARGE_CLASS.cap, 5 + (k.lines() - limit) / 50.0));
        if (CRYPTIC_CLASSES.contains(k.name) || k.name.matches("[A-Za-z]+\\d+")) add(Smell.CRYPTIC, src, k.start, "Klasse " + k.name + " sagt nicht, wofür sie da ist", 2);

        // Private Methoden ohne Aufrufer
        Set<String> invoked = new HashSet<>();
        for (Method m : src.methods) if (m.body != null) invoked.addAll(m.body.invoked);
        for (Method m : k.methods) if (m.isPrivate && !m.ctor && !invoked.contains(m.name)) add(Smell.ZOMBIE, src, m.start, "Private Methode " + m.label() + " ruft niemand", 3);

        if (k.test) {
            if (k.ignored) add(Smell.ACTOR, src, k.start, "Klasse " + k.name + " ist abgeschaltet: kein Test darin läuft", 3);
            return;
        }
        List<Method> real = k.methods.stream().filter(m -> !m.ctor).collect(Collectors.toList());
        List<Field> plain = k.fields.stream().filter(f -> !f.enumConstant && !f.is(Modifier.STATIC)).collect(Collectors.toList());
        boolean fieldsMatter = k.isPlainClass() || k.kind.equals("ENUM");

        if (fieldsMatter) for (Field f : k.fields) {
            if (f.enumConstant || f.name.equals("serialVersionUID")) continue;
            if (f.is(Modifier.PUBLIC) && !f.is(Modifier.FINAL)) add(Smell.EXHIBITIONIST, src, f.line, "Feld " + f.name + " ist öffentlich und veränderbar", f.is(Modifier.STATIC) ? 3 : 2);
            if (crypticVar(f.name)) add(Smell.CRYPTIC, src, f.line, "Feld " + f.name + " in " + k.name, 1);
            if (f.type.matches("String|int|long|char|Integer") && TYPE_CODE.matcher(f.name).find()) add(Smell.PRIMITIVE, src, f.line, "Feld " + f.type + " " + f.name + " in " + k.name + ": ein Enum sagt, welche Werte erlaubt sind", 2);
        }

        // Nutzung der privaten Felder: Zombie oder Camper
        if (fieldsMatter) for (Field f : k.fields) {
            if (!f.is(Modifier.PRIVATE) || f.enumConstant || f.name.equals("serialVersionUID")) continue;
            List<Method> users = k.methods.stream().filter(m -> m.body != null && m.body.identifiers.contains(f.name)).collect(Collectors.toList());
            int textual = 0;
            Matcher um = Pattern.compile("\\b" + Pattern.quote(f.name) + "\\b").matcher(src.stripped);
            while (um.find()) textual++;
            if (users.isEmpty() && textual <= 1) add(Smell.ZOMBIE, src, f.line, "Feld " + f.name + " benutzt niemand", 2);
            else if (users.size() == 1 && !users.get(0).ctor && !f.is(Modifier.STATIC) && !f.initialised && textual <= 1 + countIn(src, users.get(0), f.name) && writtenFirst(src, users.get(0), f.name))
                add(Smell.CAMPER, src, f.line, "Feld " + f.name + " braucht nur " + users.get(0).label() + ", die es erst setzt: als lokale Variable wäre es zu Hause", 2);
        }

        if (!k.isPlainClass()) return;
        boolean hasMain = k.methods.stream().anyMatch(m -> m.name.equals("main"));
        boolean onlyStatic = !real.isEmpty() && real.stream().allMatch(m -> m.isStatic);
        if (real.size() <= 1 && plain.size() <= 1 && k.lines() < 15 && !hasMain && !onlyStatic && k.extendsClause.isEmpty() && !k.implementsSomething && !k.isAbstract)
            add(Smell.LAZY, src, k.start, k.name + " hat " + real.size() + " Methode" + (real.size() == 1 ? "" : "n") + " und " + k.lines() + " Zeilen", 2);
        if (!plain.isEmpty() && !real.isEmpty() && real.stream().allMatch(m -> isAccessor(m, k)))
            add(Smell.DATA_CLASS, src, k.start, k.name + " hat nur Daten und keine Regeln: " + real.size() + " Getter und Setter", 2);
        List<Method> candidates = real.stream().filter(m -> !m.isStatic && !m.isAbstract).collect(Collectors.toList());
        List<Method> forwarding = candidates.stream().filter(m -> m.body != null && m.body.delegation != null && isForwarding(m)).collect(Collectors.toList());
        if (candidates.size() >= 3 && forwarding.size() * 2 > candidates.size()) {
            String to = forwarding.stream().map(m -> (String) m.body.delegation[0]).distinct().collect(Collectors.joining(", "));
            add(Smell.MIDDLE_MAN, src, k.start, k.name + " reicht " + forwarding.size() + " von " + candidates.size() + " Methoden nur an " + to + " weiter", 3);
        }
    }

    /** Weiterreichen heißt: gleicher Methodenname, oder ein Objekt (keine Collection) bekommt genau die Parameter. */
    static boolean isForwarding(Method m) {
        Object[] d = m.body.delegation;
        String type = ((String) d[3]).replaceAll("<.*", "");
        boolean collection = type.matches("(java\\.util\\.)?(Map|List|Set|Collection|Deque|Queue|Optional|ArrayList|HashMap|LinkedList|HashSet|TreeMap|EnumMap|StringBuilder|Iterable|Stream)") || type.endsWith("[]");
        return d[1].equals(m.name) || (!collection && (Boolean) d[2]);
    }

    static int countIn(Source src, Method m, String name) {
        int n = 0;
        for (int l = m.declLine; l <= m.end; l++) { Matcher x = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(src.code(l)); while (x.find()) n++; }
        return n;
    }

    /** Ist die erste Verwendung in der Methode eine schlichte Zuweisung? Dann lebt das Feld nur dort. */
    static boolean writtenFirst(Source src, Method m, String name) {
        Pattern use = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b\\s*(=(?!=))?");
        for (int l = m.bodyStart; l <= m.bodyEnd; l++) {
            Matcher x = use.matcher(src.code(l));
            if (x.find()) return x.group(1) != null;
        }
        return false;
    }

    static boolean isAccessor(Method m, Klass k) {
        if (m.name.matches("equals|hashCode|toString|compareTo")) return true;
        if (m.body == null || m.body.statements != 1) return false;
        if (m.name.matches("(get|is|has)[A-Z]\\w*") && m.params.isEmpty()) return true;
        return m.name.matches("set[A-Z]\\w*") && m.params.size() == 1;
    }

    void importSmells(Source src) {
        for (String[] imp : src.imports) {
            Matcher um = Pattern.compile("\\b" + Pattern.quote(imp[0]) + "\\b").matcher(src.stripped);
            int uses = 0;
            while (um.find()) uses++;
            if (uses <= 1) add(Smell.ZOMBIE, src, Integer.parseInt(imp[1]), "Import " + imp[0] + " benutzt niemand", 0.5);
        }
    }

    // ---- Kommentare: Erklärbär, Deo, Beipackzettel, Zombie ------------------------

    static String commentText(String raw) {
        return raw.replace("*/", " ").replaceAll("(?m)^\\s*(/\\*\\*?|\\*|//)\\s?", " ").replaceAll("\\s+", " ").trim();
    }
    static boolean looksLikeCode(String raw) {
        String c = raw.replaceAll("\\{@\\w+[^}]*\\}", "").trim();
        if (c.isEmpty()) return false;
        if (c.matches("\\}|\\}\\);?|\\} else \\{")) return true;
        if (c.endsWith(";") && (c.contains("(") || c.contains("=") || c.matches("^(return|break|continue|throw)\\b.*") || c.matches("^[\\w.<>\\[\\]]+\\s+\\w+\\s*;$"))) return true;
        if (c.endsWith("{") && c.matches("^(if|for|while|else|try|catch|do|switch|synchronized|public|private|protected|static|class|void|\\w+\\s*\\().*")) return true;
        return c.matches("^(return\\b|if\\s*\\(|for\\s*\\(|while\\s*\\(|else\\b|try\\b|catch\\s*\\(|(int|long|double|boolean|String|var|final)\\s+\\w+\\s*=|System\\.|throw\\s+new\\b|import\\s+[\\w.]+;?$|package\\s+[\\w.]+;?$|@Override).*")
            || c.matches("^[A-Za-z_][\\w.]*\\s*\\([^()]*\\)\\s*$") || c.matches("^[A-Za-z_]\\w*(\\+\\+|--)$");
    }
    static int wordCount(String s) { return s.isBlank() ? 0 : s.trim().split("\\s+").length; }

    /** Ein zusammenhängender Kommentar: Zeilen, Text, Art. */
    static class CBlock {
        int first, last; String text; boolean javadoc, fullLine; final List<String> code = new ArrayList<>();
    }
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-zäöüß]+")) if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        return out;
    }
    static List<String> nameWords(String name) {
        List<String> out = new ArrayList<>();
        for (String w : name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[_$]", " ").toLowerCase(Locale.ROOT).split("[^a-zäöüß]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }
    static boolean covered(String word, List<String> names) {
        for (String n : names) if (n.equals(word) || (Math.min(n.length(), word.length()) >= 4 && (n.startsWith(word) || word.startsWith(n)))) return true;
        return false;
    }

    void commentSmells(Source src) {
        // Aufeinanderfolgende //-Zeilen sind ein Block
        List<CBlock> blocks = new ArrayList<>();
        for (int[] cr : src.commentRanges) {
            CBlock b = new CBlock();
            b.first = src.lineOf(cr[0]); b.last = src.lineOf(Math.max(cr[0], cr[1] - 1));
            String raw = src.text.substring(cr[0], cr[1]);
            b.javadoc = raw.startsWith("/**");
            b.fullLine = src.text.substring(src.lineStart[b.first - 1], cr[0]).isBlank();
            b.text = commentText(raw);
            for (String l : raw.split("\n")) { String c = commentText(l); if (!c.isEmpty() && looksLikeCode(c)) b.code.add(c); }
            CBlock prev = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
            if (prev != null && !b.javadoc && !prev.javadoc && b.fullLine && prev.fullLine && prev.last == b.first - 1 && raw.startsWith("//")) {
                prev.last = b.last; prev.text = (prev.text + " " + b.text).trim(); prev.code.addAll(b.code);
            } else blocks.add(b);
        }
        for (CBlock b : blocks) {
            int first = b.first, last = b.last;
            String text = b.text;
            boolean javadoc = b.javadoc, fullLine = b.fullLine;
            List<String> code = b.code;
            if (text.isEmpty()) continue;
            if (!code.isEmpty()) {
                add(Smell.ZOMBIE, src, first, "Auskommentierter Code: " + shorten(code.get(0)), 2);
                if (words(text).size() <= words(String.join(" ", code)).size() + 2) continue;
            }
            Method above = null; Field fieldAbove = null;
            for (Method m : src.methods) if (m.declLine == last + 1) above = m;
            for (Klass k : src.classes) for (Field f : k.fields) if (f.line == last + 1) fieldAbove = f;
            boolean label = wordCount(text) <= 8;   // ein Etikett, kein Absatz Prosa
            if (fullLine && above != null) {
                // Beipackzettel: ein Etikett über einem Namen, der es nicht trägt. Lange Warum-Kommentare sind erlaubt.
                List<String> names = new ArrayList<>(nameWords(above.name));
                for (Param p : above.params) names.addAll(nameWords(p.name()));
                names.addAll(nameWords(above.owner.name));
                List<String> uncovered = words(text).stream().filter(w -> !covered(w, names)).collect(Collectors.toList());
                boolean generic = !above.ctor && (genericMethod(above.name) || (above.test && above.name.matches("^(test|check|verify|it|should|case)\\d*$")));
                if (generic) add(Smell.LEAFLET, src, above.start, above.label() + " braucht einen Beipackzettel: der Kommentar sagt „" + shorten(text) + "“, der Name nichts", 2);
                else if (wordCount(text) <= 6 && !uncovered.isEmpty() && !(javadoc && !above.isPrivate))
                    add(Smell.LEAFLET, src, above.start, above.label() + ": das Etikett „" + shorten(text) + "“ gehört in den Namen", 2);
                continue;
            }
            if (fullLine && fieldAbove != null) {
                if (crypticVar(fieldAbove.name)) add(Smell.LEAFLET, src, fieldAbove.line, "Feld " + fieldAbove.name + ": der Kommentar sagt „" + shorten(text) + "“, der Name nicht", 2);
                continue;
            }
            Method in = src.methodAt(first);
            if (in == null || first <= in.bodyStart) continue;
            String lower = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (in.test && TEST_STAGE.contains(lower)) continue;
            if (text.matches("(?i)^(todo|fixme|xxx|hack)\\b.*")) { add(Smell.COMMENT, src, first, "„" + shorten(text) + "“: tun oder löschen", 1); continue; }
            if (!fullLine) { if (label) add(Smell.COMMENT, src, first, "„" + shorten(text) + "“ hängt an einer Zeile: der Code sollte das selbst sagen", 1); continue; }
            int following = 0;
            for (int l = last + 1; l <= in.bodyEnd; l++) {
                String c = src.code(l);
                boolean isComment = c.isEmpty() && !src.lines.get(l - 1).isBlank();
                if (c.isEmpty() || isComment) break;
                if (c.matches("}( else \\{)?")) break;
                following++;
            }
            // Ein Etikett vor drei Zeilen ist eine Methode ohne Namen; ein Absatz Prosa erst vor acht Zeilen.
            if (following >= (label ? 3 : 8)) { add(Smell.DEO, src, first, "„" + shorten(text) + "“ leitet " + following + " Zeilen ein: das ist eine Methode", 2); continue; }
            if (!label) continue;
            List<String> next = new ArrayList<>();
            for (int l = last + 1; l <= Math.min(last + 1, in.bodyEnd); l++) next.addAll(nameWords(src.code(l).replaceAll("[^A-Za-z_$]+", " ")));
            List<String> ws = words(text);
            boolean repeats = !ws.isEmpty() && ws.stream().allMatch(w -> covered(w, next));
            add(Smell.COMMENT, src, first, "„" + shorten(text) + "“ " + (repeats ? "wiederholt den Code: löschen" : "erklärt eine Zeile: Extract Variable oder Rename"), repeats ? 0.5 : 1);
        }
    }

    static String shorten(String s) { return s.length() > 60 ? s.substring(0, 57) + "…" : s; }

    // ---- Projektweit: Cliquen, Weichen, Zwillinge --------------------------------

    void clumps() {
        Map<String, List<Method>> groups = new LinkedHashMap<>();
        for (Source src : sources) { if (src.test) continue; for (Method m : src.methods) if (m.params.size() >= 3 && !m.ctor && !m.isStatic) {
            List<String> names = m.params.stream().map(Param::name).sorted().collect(Collectors.toList());
            groups.computeIfAbsent(String.join(", ", names), x -> new ArrayList<>()).add(m);
        } }
        for (Map.Entry<String, List<Method>> e : groups.entrySet()) {
            List<Method> ms = e.getValue();
            if (ms.size() < 2) continue;
            Method first = ms.get(0);
            Source src = sources.stream().filter(s -> s.file.equals(first.file)).findFirst().orElseThrow();
            add(Smell.CLUMP, src, first.start, e.getKey() + " reisen zusammen durch " + ms.stream().map(Method::label).distinct().collect(Collectors.joining(", ")), 3);
        }
    }

    void repeatedSwitches() {
        Map<String, List<Object[]>> bySubject = new LinkedHashMap<>();   // subject -> [src, method, line]
        for (Source src : sources) { if (src.test) continue; for (Method m : src.methods) if (m.body != null) for (Object[] s : m.body.switches)
            bySubject.computeIfAbsent(((String) s[1]).replace("this.", ""), x -> new ArrayList<>()).add(new Object[]{src, m, s[0]}); }
        for (Map.Entry<String, List<Object[]>> e : bySubject.entrySet()) {
            long methods = e.getValue().stream().map(o -> o[1]).distinct().count();
            if (methods < 2) continue;
            Object[] first = e.getValue().get(0);
            add(Smell.SWITCH, (Source) first[0], (Integer) first[2], "switch über " + e.getKey() + " in " + methods + " Methoden: " + e.getValue().stream().map(o -> ((Method) o[1]).label()).distinct().collect(Collectors.joining(", ")), 4);
        }
    }

    static final Set<String> TRIVIAL = Set.of("}", "{", "} else {", "else {", "return;", "break;", "continue;", "});", "})", "try {");

    void duplicates() {
        Map<String, List<int[]>> windows = new LinkedHashMap<>();
        int window = 4;
        for (int si = 0; si < sources.size(); si++) {
            Source src = sources.get(si);
            List<Integer> idx = new ArrayList<>();
            List<String> norm = new ArrayList<>();
            for (int l = 1; l <= src.lines.size(); l++) {
                String s = src.codeWithLiterals(l).replaceAll("\\s+", " ");
                if (s.length() < 8 || TRIVIAL.contains(s) || s.startsWith("import ") || s.startsWith("package ") || s.startsWith("@")) continue;
                idx.add(l - 1); norm.add(s);
            }
            for (int w = 0; w + window <= norm.size(); w++) {
                String key = String.join("\n", norm.subList(w, w + window));
                if (!key.contains(";") && !key.contains("{")) continue;   // Annotationen und Argumentlisten sind keine Zwillinge
                windows.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{si, idx.get(w), idx.get(w + window - 1)});
            }
        }
        Map<Integer, boolean[]> marked = new HashMap<>();
        Map<Integer, int[][]> firstOf = new HashMap<>();
        for (List<int[]> occ : windows.values()) {
            if (occ.size() < 2) continue;
            for (int k = 1; k < occ.size(); k++) {
                int[] o = occ.get(k);
                boolean[] mk = marked.computeIfAbsent(o[0], s -> new boolean[sources.get(s).lines.size()]);
                for (int l = o[1]; l <= o[2]; l++) mk[l] = true;
                firstOf.computeIfAbsent(o[0], s -> new int[sources.get(s).lines.size()][])[o[1]] = occ.get(0);
            }
        }
        for (Map.Entry<Integer, boolean[]> e : marked.entrySet()) {
            Source src = sources.get(e.getKey());
            boolean[] mk = e.getValue();
            int l = 0;
            while (l < mk.length) {
                if (!mk[l]) { l++; continue; }
                int start = l;
                while (l < mk.length && mk[l]) l++;
                int[] first = firstOf.get(e.getKey())[start];
                String where = first == null ? "" : " von " + sources.get(first[0]).file.replaceAll(".*/", "") + ":" + (first[1] + 1);
                add(Smell.TWINS, src, start + 1, "Zeilen " + (start + 1) + " bis " + l + " sind ein Zwilling" + where, 4);
            }
        }
    }

    // ---- Wertung ------------------------------------------------------------------

    Map<Smell, Double> penaltyBySmell() {
        Map<Smell, Double> p = new EnumMap<>(Smell.class);
        for (Smell s : Smell.values()) p.put(s, Math.min(s.cap, findings.stream().filter(f -> f.smell == s).mapToDouble(Finding::penalty).sum()));
        return p;
    }

    Map<Family, Double> penaltyByFamily() {
        Map<Smell, Double> p = penaltyBySmell();
        Map<Family, Double> f = new EnumMap<>(Family.class);
        for (Family fam : Family.values()) f.put(fam, Math.min(fam.cap, Arrays.stream(Smell.values()).filter(s -> s.family == fam).mapToDouble(p::get).sum()));
        return f;
    }

    int score() {
        double total = penaltyByFamily().values().stream().mapToDouble(Double::doubleValue).sum();
        return (int) Math.max(0, Math.round(100 - total));
    }

    static String[] level(int score) {
        if (score >= 90) return new String[]{"🏆", "Clean-Code-Meister", "Hier gibt es nichts zu sehen. Weitergehen."};
        if (score >= 70) return new String[]{"🔧", "Geselle", "Solide Arbeit. Ein paar Monster lauern noch."};
        if (score >= 50) return new String[]{"🧹", "Lehrling", "Die Monster haben sich häuslich eingerichtet."};
        if (score >= 25) return new String[]{"🍝", "Spaghetti-Koch", "Al dente, aber niemand findet den Anfang."};
        return new String[]{"🦖", "Legacy-Legende", "Archäologen werden hier eines Tages graben."};
    }

    Method boss() {
        Method boss = null; double bossPen = 0;
        for (Source src : sources) for (Method m : src.methods) {
            double p = bossPenalty(m);
            if (p > bossPen) { bossPen = p; boss = m; }
        }
        return bossPen >= 5 ? boss : null;
    }

    double bossPenalty(Method m) {
        return findings.stream().filter(f -> f.file.equals(m.file) && f.line >= m.declLine && f.line <= m.end && !CLASS_LEVEL.contains(f.smell)).mapToDouble(Finding::penalty).sum();
    }

    // ---- HTML ---------------------------------------------------------------------

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static final Pattern KEYWORD = Pattern.compile("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|var|record|yield|sealed|permits|true|false|null)\\b");
    static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?[lLfFdD]?\\b");
    static final Pattern ANNOTATION = Pattern.compile("@\\w+");

    static String highlight(Source src, int line) {
        int from = src.lineStart[line - 1];
        int to = Math.min(src.lineStart[line] - 1, src.text.length());
        if (to < from) to = from;
        StringBuilder out = new StringBuilder();
        int i = from;
        while (i < to) {
            int[] range = rangeAt(src, i);
            if (range != null) {
                int e = Math.min(range[1], to);
                out.append("<span class=\"").append(range[2] == 0 ? "cm" : "st").append("\">").append(esc(src.text.substring(i, e))).append("</span>");
                i = e;
            } else {
                int e = i;
                while (e < to && rangeAt(src, e) == null) e++;
                String code = esc(src.text.substring(i, e));
                code = ANNOTATION.matcher(code).replaceAll("<span class=\"an\">$0</span>");
                code = KEYWORD.matcher(code).replaceAll("<span class=\"kw\">$1</span>");
                code = NUMBER.matcher(code).replaceAll("<span class=\"nu\">$0</span>");
                out.append(code);
                i = e;
            }
        }
        return out.toString();
    }

    static int[] rangeAt(Source src, int offset) {
        for (int[] r : src.commentRanges) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 0};
        for (int[] r : src.stringLiterals) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 1};
        for (int[] r : src.charLiterals) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 1};
        return null;
    }

    String html(List<int[]> history) {
        int score = score();
        String[] lv = level(score);
        Map<Smell, Double> pen = penaltyBySmell();
        Map<Family, Double> fpen = penaltyByFamily();
        long beaten = Arrays.stream(Smell.values()).filter(s -> pen.get(s) == 0).count();
        String project = root.toAbsolutePath().getFileName().toString();
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        Map<String, Integer> fileIndex = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) fileIndex.put(sources.get(i).file, i);
        int rulesIndex = sources.size();

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        h.append("<title>Clean-Code-Report ").append(esc(project)).append("</title><style>").append(CSS).append("</style></head><body><div class=\"app\">");

        // ---- Linke Seite
        h.append("<aside class=\"side\"><div class=\"top\"><p class=\"kicker\">Clean-Code-Report ").append(VERSION).append("</p><h1>").append(esc(project)).append("</h1><p class=\"meta\">")
         .append(when).append(" · ").append(sources.size()).append(" Dateien · ").append(findings.size()).append(" Funde</p>");

        double angle = Math.PI * (1 - score / 100.0);
        double x = 100 + 78 * Math.cos(angle), y = 100 - 78 * Math.sin(angle);
        String arcColor = score >= 70 ? "#38c172" : score >= 40 ? "#f6b73c" : "#e5484d";
        h.append("<div class=\"hero\"><svg viewBox=\"0 0 200 112\" width=\"170\" height=\"95\" role=\"img\" aria-label=\"Punktestand ").append(score).append("\">")
         .append("<path d=\"M22 100 A78 78 0 0 1 178 100\" fill=\"none\" stroke=\"rgba(255,255,255,.12)\" stroke-width=\"16\" stroke-linecap=\"round\"/>");
        if (score >= 100) h.append("<path d=\"M22 100 A78 78 0 0 1 178 100\" fill=\"none\" stroke=\"").append(arcColor).append("\" stroke-width=\"16\" stroke-linecap=\"round\"/>");
        else if (score > 0) h.append("<path d=\"M22 100 A78 78 0 0 1 ").append(svg(x)).append(" ").append(svg(y)).append("\" fill=\"none\" stroke=\"").append(arcColor).append("\" stroke-width=\"16\" stroke-linecap=\"round\"/>");
        h.append("<text x=\"100\" y=\"92\" text-anchor=\"middle\" class=\"score\">").append(score).append("</text><text x=\"100\" y=\"109\" text-anchor=\"middle\" class=\"of\">von 100</text></svg>");
        h.append("<div class=\"level\"><p class=\"rank\"><span class=\"big\">").append(lv[0]).append("</span> ").append(lv[1]).append("</p><p class=\"tag\">").append(lv[2]).append("</p>")
         .append("<p class=\"stat\">").append(beaten).append(" von ").append(Smell.values().length).append(" Monster besiegt</p></div></div>");
        if (!history.isEmpty()) {
            h.append("<p class=\"history\">Verlauf ");
            for (int[] hs : history) h.append("<span class=\"pill\">").append(hs[0]).append("</span>");
            h.append("<span class=\"pill now\">").append(score).append("</span></p>");
        }
        h.append(radar(fpen));

        Method boss = boss();
        if (boss != null) {
            double bp = bossPenalty(boss);
            int hp = (int) Math.min(100, Math.round(bp * 2));
            h.append("<a class=\"boss\" href=\"#f").append(fileIndex.get(boss.file)).append("-L").append(boss.start).append("\"><p class=\"kicker\">Endgegner</p><p class=\"name\">").append(esc(boss.label())).append("<span class=\"where\">")
             .append(esc(boss.file.replaceAll(".*/", ""))).append(":").append(boss.start).append("</span></p><div class=\"hp\"><div style=\"width:").append(hp).append("%\"></div></div>")
             .append("<p class=\"stats\"><span>").append(boss.codeLines).append(" Zeilen</span><span>Tiefe ").append(boss.depth).append("</span><span>Komplexität ").append(boss.complexity).append("</span><span>")
             .append(boss.body == null ? 0 : boss.body.locals.size()).append(" Variablen</span><span>").append(fmt(bp)).append(" Strafpunkte</span></p></a>");
        }
        h.append("</div>");

        h.append("<nav class=\"list\">");
        if (findings.isEmpty()) h.append("<p class=\"empty\">").append(sources.isEmpty() ? "Unter src/ liegt noch kein Java-Code." : "Kein Monster in Sicht. Alle Regeln erfüllt.").append("</p>");
        for (Family fam : Family.values()) {
            List<Smell> smells = Arrays.stream(Smell.values()).filter(s -> s.family == fam).sorted(Comparator.comparingDouble((Smell s) -> -pen.get(s))).collect(Collectors.toList());
            h.append("<section class=\"family\"><h2><span class=\"femoji\">").append(fam.emoji).append("</span><span class=\"ftitle\">").append(fam.title).append("</span><span class=\"fpen").append(fpen.get(fam) == 0 ? " ok" : "").append("\">")
             .append(fpen.get(fam) == 0 ? "sauber" : "−" + fmt(fpen.get(fam)) + " von " + fmt(fam.cap)).append("</span></h2>");
            StringBuilder beatenChips = new StringBuilder();
            for (Smell s : smells) {
                List<Finding> fs = findings.stream().filter(f -> f.smell == s).sorted(Comparator.comparingDouble((Finding f) -> -f.penalty).thenComparing(Finding::file).thenComparingInt(Finding::line)).collect(Collectors.toList());
                if (fs.isEmpty()) { beatenChips.append("<span title=\"").append(esc(s.monster + " (" + s.smell + "): " + s.rule)).append("\">").append(s.emoji).append("</span>"); continue; }
                h.append("<details class=\"monster\" open><summary><span class=\"emoji\">").append(s.emoji).append("</span><span class=\"names\"><b>").append(s.monster).append("</b><small>").append(esc(s.smell)).append("</small></span>")
                 .append("<span class=\"count\">").append(fs.size()).append("×</span><span class=\"pen\">−").append(fmt(pen.get(s))).append("</span></summary>");
                h.append("<p class=\"fix\">").append(esc(s.fix)).append("</p><ul>");
                for (Finding f : fs) h.append("<li><a href=\"#f").append(fileIndex.get(f.file)).append("-L").append(f.line).append("\"><code>").append(esc(f.file.replaceAll(".*/", ""))).append(":").append(f.line).append("</code> ").append(esc(f.text)).append("</a><span class=\"pts\">").append(fmt(f.penalty)).append("</span></li>");
                h.append("</ul></details>");
            }
            if (beatenChips.length() > 0) h.append("<p class=\"beaten\">Besiegt ").append(beatenChips).append("</p>");
            h.append("</section>");
        }
        h.append("<h2 class=\"plain\">Dateien</h2><ul class=\"files\">");
        for (int i = 0; i < sources.size(); i++) {
            Source src = sources.get(i);
            long n = findings.stream().filter(f -> f.file.equals(src.file)).count();
            h.append("<li><a href=\"#f").append(i).append("\"><code>").append(esc(src.file.replaceAll(".*/", ""))).append("</code><small>").append(esc(src.file.replaceAll("/[^/]*$", ""))).append("</small></a><span class=\"pts\">").append(n == 0 ? "✓" : n + " Funde").append("</span></li>");
        }
        h.append("</ul><p class=\"ruleslink\"><a href=\"#rules\">📖 Regelwerk: alle Monster, Schwellen und Refactorings</a></p>");
        h.append("<p class=\"foot\">Der Bericht ist kein Gate: Er zeigt, er entscheidet nicht. Erzeugt von <code>CleanCodeReport.java</code> ").append(VERSION).append(".</p></nav></aside>");

        // ---- Rechte Seite
        h.append("<main class=\"editor\"><div class=\"tabs\">");
        for (int i = 0; i < sources.size(); i++) h.append("<a class=\"tab\" href=\"#f").append(i).append("\">").append(esc(sources.get(i).file.replaceAll(".*/", ""))).append("</a>");
        h.append("<a class=\"tab rulestab\" href=\"#rules\">📖 Regelwerk</a>");
        h.append("<label class=\"toggle\"><input type=\"checkbox\" id=\"hints\" checked> Hinweise im Code</label></div>");
        for (int i = 0; i < sources.size(); i++) {
            Source src = sources.get(i);
            String cls = src.file.replaceAll(".*/", "").replace(".java", "");
            String pkg = src.file.replaceAll("^src/(main|test)/java/", "").replaceAll("/[^/]*$", "").replace('/', '.');
            Map<Integer, List<Finding>> byLine = findings.stream().filter(f -> f.file.equals(src.file)).collect(Collectors.groupingBy(Finding::line));
            h.append("<section class=\"file\" id=\"f").append(i).append("\"").append(i == 0 ? "" : " hidden").append("><div class=\"crumbs\">").append(esc(pkg)).append(" · ").append(src.test ? "Tests" : "Produktivcode").append(" · ").append(src.lines.size()).append(" Zeilen · ").append(src.methods.size()).append(" Methoden</div><h2 class=\"classname\">").append(esc(cls)).append("</h2><div class=\"code\">");
            for (int l = 1; l <= src.lines.size(); l++) {
                int d = Math.max(0, src.depthAtLineStart(l) - 1);
                List<Finding> fl = byLine.getOrDefault(l, List.of());
                h.append("<div class=\"line").append(fl.isEmpty() ? "" : " hit").append("\" id=\"f").append(i).append("-L").append(l).append("\"><span class=\"gutter\"><span class=\"no\">").append(l).append("</span><span class=\"icons\">");
                for (Finding f : fl) h.append("<span title=\"").append(esc(f.smell.monster + ": " + f.text)).append("\">").append(f.smell.emoji).append("</span>");
                h.append("</span></span><span class=\"depth d").append(Math.min(d, 6)).append("\"></span><span class=\"src\">").append(highlight(src, l)).append("</span></div>");
                for (Finding f : fl) h.append("<div class=\"hint\"><span>").append(f.smell.emoji).append("</span> <b>").append(f.smell.monster).append("</b> ").append(esc(f.text)).append(" <i>").append(esc(f.smell.fix)).append("</i></div>");
            }
            h.append("</div></section>");
        }
        h.append(rulesPage(rulesIndex, sources.isEmpty()));
        h.append("</main></div><script>").append(JS).append("</script></body></html>");
        return h.toString();
    }

    String radar(Map<Family, Double> fpen) {
        Family[] fams = Family.values();
        int n = fams.length; double cx = 130, cy = 94, r = 62;
        StringBuilder s = new StringBuilder("<div class=\"radar\"><svg viewBox=\"0 0 260 192\" width=\"100%\" role=\"img\" aria-label=\"Gesundheit je Familie\">");
        for (int ring = 1; ring <= 4; ring++) s.append("<polygon points=\"").append(poly(n, cx, cy, r * ring / 4.0, null)).append("\" fill=\"none\" stroke=\"rgba(255,255,255,.14)\"/>");
        for (int i = 0; i < n; i++) { double a = angle(i, n); s.append("<line x1=\"").append(svg(cx)).append("\" y1=\"").append(svg(cy)).append("\" x2=\"").append(svg(cx + r * Math.cos(a))).append("\" y2=\"").append(svg(cy + r * Math.sin(a))).append("\" stroke=\"rgba(255,255,255,.14)\"/>"); }
        double[] health = new double[n];
        for (int i = 0; i < n; i++) health[i] = Math.max(0, 1 - fpen.get(fams[i]) / fams[i].cap);
        s.append("<polygon points=\"").append(poly(n, cx, cy, r, health)).append("\" fill=\"rgba(61,141,255,.35)\" stroke=\"#9fd8ff\" stroke-width=\"2\" stroke-linejoin=\"round\"/>");
        for (int i = 0; i < n; i++) {
            double a = angle(i, n), px = cx + r * health[i] * Math.cos(a), py = cy + r * health[i] * Math.sin(a);
            String col = health[i] >= .7 ? "#38c172" : health[i] >= .4 ? "#f6b73c" : "#e5484d";
            s.append("<circle cx=\"").append(svg(px)).append("\" cy=\"").append(svg(py)).append("\" r=\"3.5\" fill=\"").append(col).append("\"/>");
            double lx = cx + (r + 12) * Math.cos(a), ly = cy + (r + 12) * Math.sin(a);
            String anchor = Math.abs(Math.cos(a)) < .2 ? "middle" : Math.cos(a) > 0 ? "start" : "end";
            double dy = Math.sin(a) < -.2 ? -6 : Math.sin(a) > .2 ? 6 : 0;
            String label = fams[i].title.replace("Object-Orientation Abusers", "OO Abusers").replace("Test Smells", "Tests");
            s.append("<text x=\"").append(svg(lx)).append("\" y=\"").append(svg(ly + dy)).append("\" text-anchor=\"").append(anchor).append("\" class=\"axis\">").append(esc(label))
             .append("<tspan x=\"").append(svg(lx)).append("\" dy=\"10\" fill=\"").append(col).append("\" font-weight=\"600\">").append(Math.round(health[i] * 100)).append("</tspan></text>");
        }
        return s.append("</svg></div>").toString();
    }

    static double angle(int i, int n) { return -Math.PI / 2 + 2 * Math.PI * i / n; }
    static String poly(int n, double cx, double cy, double r, double[] scale) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < n; i++) { double a = angle(i, n), k = scale == null ? 1 : scale[i]; p.append(svg(cx + r * k * Math.cos(a))).append(",").append(svg(cy + r * k * Math.sin(a))).append(" "); }
        return p.toString().trim();
    }

    String rulesPage(int index, boolean only) {
        StringBuilder h = new StringBuilder();
        h.append("<section class=\"file rules\" id=\"f").append(index).append("\"").append(only ? "" : " hidden").append("><div class=\"crumbs\">Clean-Code-Report ").append(VERSION).append(" · Regelwerk</div><h2 class=\"classname\">Regelwerk</h2><div class=\"prose\">");
        h.append("<p>Die Familien folgen der Einteilung von Mäntylä und Lassenius, die refactoring.guru bekannt gemacht hat: <b>Bloaters</b>, <b>Object-Orientation Abusers</b>, <b>Change Preventers</b>, <b>Dispensables</b> und <b>Couplers</b>. Dazu kommen <b>Readability</b> nach <i>Clean Code</i> (Robert C. Martin) und <b>Test Smells</b> nach <i>xUnit Test Patterns</i> (Gerard Meszaros). Die Refactorings heißen wie bei Martin Fowler.</p>");
        h.append("<p>Change Preventers (Divergent Change, Shotgun Surgery, Parallel Inheritance Hierarchies) zeigen sich erst in der Änderungshistorie. Der Bericht misst sie nicht.</p>");
        h.append("<p>Punkte: 100 minus Strafpunkte. Jedes Monster ist gedeckelt, jede Familie noch einmal, damit keine Familie allein den Rest erdrückt. Der Radar links zeigt je Familie, wie viel vom Deckel noch frei ist. Ränge: ab 90 Clean-Code-Meister, ab 70 Geselle, ab 50 Lehrling, ab 25 Spaghetti-Koch, darunter Legacy-Legende.</p>");
        for (Family fam : Family.values()) {
            h.append("<h3>").append(fam.emoji).append(" ").append(esc(fam.title)).append(" <small>Deckel ").append(fmt(fam.cap)).append("</small></h3><p class=\"about\">").append(esc(fam.about)).append("</p>");
            h.append("<table><thead><tr><th>Monster</th><th>Smell</th><th>Regel</th><th>Strafpunkte</th><th>Refactoring</th></tr></thead><tbody>");
            for (Smell s : Smell.values()) if (s.family == fam)
                h.append("<tr><td class=\"mon\">").append(s.emoji).append(" ").append(esc(s.monster)).append("</td><td>").append(esc(s.smell)).append("</td><td>").append(esc(s.rule)).append("</td><td>").append(esc(s.points)).append("</td><td>").append(esc(s.fix)).append("</td></tr>");
            h.append("</tbody></table>");
        }
        h.append("<p class=\"about\">Gemessen wird mit dem Java-Parser des JDK, nicht mit Textmustern. Was der Bericht nicht sieht: Typen aus anderen Dateien, Vererbung über Dateigrenzen, Laufzeitverhalten. Ein Fund ist ein Hinweis, kein Urteil.</p>");
        return h.append("</div></section>").toString();
    }

    static String svg(double d) { return String.format(Locale.ROOT, "%.2f", d); }
    static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.GERMANY, "%.1f", d); }

    static final String JS = """
        (function(){
          var files=[].slice.call(document.querySelectorAll('.file')),tabs=[].slice.call(document.querySelectorAll('.tab')),editor=document.querySelector('.editor');
          function show(i,line){files.forEach(function(f,k){f.hidden=(k!==i)});tabs.forEach(function(t,k){t.classList.toggle('active',k===i)});
            if(line){var el=document.getElementById('f'+i+'-L'+line);if(el){el.scrollIntoView({block:'center'});el.classList.remove('flash');void el.offsetWidth;el.classList.add('flash');}}else{editor.scrollTop=0;}}
          function route(){if(location.hash==='#rules'){show(files.length-1,0);return;}var m=location.hash.match(/^#f(\\d+)(?:-L(\\d+))?$/);if(m&&files[+m[1]])show(+m[1],m[2]?+m[2]:0);else if(files.length)show(0,0);}
          window.addEventListener('hashchange',route);route();
          var box=document.getElementById('hints');if(box){box.addEventListener('change',function(){document.body.classList.toggle('nohints',!box.checked)});}
        })();
        """;

    static final String CSS = """
        :root{--bg:#081533;--bg2:#0d2150;--edge:rgba(255,255,255,.16);--ink:#eef4ff;--soft:#a9bddb;--ice:#9fd8ff;--beam:#3d8dff;--paper:#fff;--text:#080808;--gutter:#999;--kw:#0033b3;--st:#067d17;--nu:#1750eb;--cm:#8c8c8c;--an:#9e880d}
        *{box-sizing:border-box}html,body{margin:0;height:100%}body{background:var(--bg);color:var(--ink);font-family:"Avenir Next","Segoe UI","Helvetica Neue",Arial,sans-serif;line-height:1.45}
        .app{display:grid;grid-template-columns:420px 1fr;height:100vh}
        .side{overflow-y:auto;background:linear-gradient(180deg,var(--bg2),var(--bg));border-right:1px solid var(--edge)}
        .top{padding:20px 18px 12px;border-bottom:1px solid var(--edge)}.kicker{margin:0;color:var(--ice);font-size:.8rem;letter-spacing:.04em}h1{margin:2px 0 2px;font-size:1.45rem}.meta{margin:0 0 10px;color:var(--soft);font-size:.85rem}
        .hero{display:flex;align-items:center;gap:12px}.score{font-size:40px;font-weight:700;fill:#fff}.of{font-size:11px;fill:var(--soft)}
        .rank{margin:0;font-size:1.15rem;font-weight:600}.big{font-size:1.5rem;vertical-align:middle}.tag{margin:2px 0 4px;color:var(--soft);font-size:.9rem}.stat{margin:0;color:var(--ice);font-size:.85rem}
        .history{margin:8px 0 0;color:var(--soft);font-size:.8rem}.pill{display:inline-block;margin:2px 3px 0 0;padding:0 8px;border-radius:999px;background:rgba(255,255,255,.1)}.pill.now{background:var(--beam);color:#fff}
        .radar{margin:10px auto 0;max-width:300px}.axis{font-size:9.5px;fill:var(--soft);font-family:"Avenir Next","Segoe UI",Arial,sans-serif}
        .boss{display:block;margin-top:12px;padding:10px 12px;border:1px solid var(--edge);border-radius:12px;background:rgba(255,255,255,.06);color:inherit;text-decoration:none}.boss:hover{border-color:var(--ice)}
        .boss .name{margin:0;font-size:1.05rem;font-weight:600}.where{margin-left:8px;font-size:.8rem;color:var(--soft);font-weight:400}.hp{height:10px;border-radius:999px;background:rgba(0,0,0,.4);overflow:hidden;margin:6px 0 8px}.hp div{height:100%;background:linear-gradient(90deg,#e5484d,#ff8a80)}
        .stats{display:flex;flex-wrap:wrap;gap:6px;margin:0}.stats span{padding:1px 8px;border-radius:999px;background:rgba(3,12,30,.5);border:1px solid rgba(159,216,255,.25);color:var(--ice);font-size:.78rem}
        .list{padding:10px 12px 24px}.list h2{margin:12px 6px 6px;font-size:.85rem;color:var(--ice);letter-spacing:.03em;font-weight:600}.empty{margin:8px 6px;color:var(--soft)}
        .family h2{display:flex;align-items:center;gap:8px;margin:16px 6px 6px}.femoji{font-size:1.1rem}.ftitle{flex:1;color:#fff;font-size:.9rem}.fpen{color:#ff8a80;font-weight:500;font-size:.8rem}.fpen.ok{color:#38c172}
        .beaten{margin:2px 6px 0;color:var(--soft);font-size:.78rem}.beaten span{font-size:1rem;margin-left:3px;cursor:help;opacity:.7}
        .monster{margin:4px 0;border:1px solid var(--edge);border-radius:12px;background:rgba(255,255,255,.05)}.monster summary{display:flex;align-items:center;gap:10px;padding:8px 10px;cursor:pointer;list-style:none}.monster summary::-webkit-details-marker{display:none}
        .emoji{font-size:1.4rem}.names{flex:1;min-width:0}.names b{display:block;font-size:.95rem}.names small{color:var(--soft)}.count{padding:1px 8px;border-radius:999px;background:#e5484d;color:#fff;font-size:.78rem;font-weight:600}.pen{color:#ff8a80;font-size:.8rem;min-width:2.6em;text-align:right}
        .fix{margin:0 10px 6px;color:var(--ice);font-size:.82rem}.monster ul{list-style:none;margin:0;padding:0 6px 6px}.monster li{display:flex;align-items:baseline;gap:6px;padding:3px 4px;border-radius:6px;font-size:.85rem}.monster li:hover{background:rgba(255,255,255,.07)}
        .monster li a{flex:1;color:inherit;text-decoration:none}.monster li a:hover{color:#fff}.pts{color:#ff8a80;font-size:.8rem;white-space:nowrap}
        .files{list-style:none;margin:0;padding:0}.files li{display:flex;align-items:center;gap:6px;padding:5px 8px;border-radius:8px;font-size:.88rem}.files li:hover{background:rgba(255,255,255,.07)}.files a{flex:1;color:inherit;text-decoration:none;display:flex;flex-direction:column}.files small{color:var(--soft);font-size:.72rem}.files .pts{color:var(--soft)}
        .ruleslink{margin:14px 6px 0;font-size:.85rem}.ruleslink a{color:var(--ice);text-decoration:none}.ruleslink a:hover{text-decoration:underline}.foot{margin:10px 6px 0;color:var(--soft);font-size:.75rem}
        code{font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,"Liberation Mono",monospace;font-size:.9em;color:var(--ice)}
        .editor{overflow-y:auto;background:var(--paper);color:var(--text)}.tabs{position:sticky;top:0;z-index:2;display:flex;align-items:center;flex-wrap:wrap;gap:2px;padding:6px 10px 0;background:#f2f2f2;border-bottom:1px solid #d9d9d9}
        .tab{padding:6px 14px;border:1px solid transparent;border-bottom:0;border-radius:6px 6px 0 0;color:#444;text-decoration:none;font-size:.85rem;font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,monospace}.tab.active{background:#fff;border-color:#d9d9d9;color:#000;position:relative;top:1px}.rulestab{font-family:"Avenir Next","Segoe UI",Arial,sans-serif}.toggle{margin-left:auto;font-size:.8rem;color:#555;padding-bottom:6px}
        .file{padding:14px 0 40px}.crumbs{padding:0 18px;color:#777;font-size:.8rem}.classname{margin:2px 18px 12px;font-size:1.3rem;font-weight:600;color:#000}
        .code{font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,"Liberation Mono",monospace;font-size:13px;line-height:1.55;color:var(--text)}
        .line{display:flex;align-items:stretch;white-space:pre}.line.hit{background:#fff4f2}.line.flash{animation:flash 1.6s ease-out}@keyframes flash{0%{background:#ffe58f}100%{background:#fff4f2}}
        .gutter{flex:0 0 96px;display:flex;justify-content:flex-end;align-items:center;gap:6px;padding-right:8px;color:var(--gutter);background:#fafafa;border-right:1px solid #ececec;user-select:none}.no{min-width:2.6em;text-align:right}.icons{width:44px;text-align:left;font-size:12px;cursor:help}
        .depth{flex:0 0 6px;margin-right:12px}.d1{background:#eef4ff}.d2{background:#dbe7ff}.d3{background:#ffe9b8}.d4{background:#ffd27a}.d5{background:#ffb1a8}.d6{background:#ff7b6e}
        .src{flex:1;padding-right:18px}.kw{color:var(--kw);font-weight:600}.st{color:var(--st)}.nu{color:var(--nu)}.cm{color:var(--cm);font-style:italic}.an{color:var(--an)}
        .hint{margin:0 18px 2px 114px;padding:3px 10px;border-left:3px solid #f6b73c;background:#fff8e1;color:#5a4a00;font-size:12.5px;font-family:"Avenir Next","Segoe UI",Arial,sans-serif;border-radius:0 6px 6px 0}.hint i{color:#7a6a20}
        .nohints .hint{display:none}
        .prose{max-width:1100px;padding:0 18px;font-size:.95rem;color:#222}.prose h3{margin:26px 0 4px;font-size:1.1rem}.prose h3 small{font-weight:400;color:#777;font-size:.8rem;margin-left:8px}.prose .about{color:#555;margin:0 0 8px}
        .prose table{width:100%;border-collapse:collapse;font-size:.85rem}.prose th{text-align:left;color:#666;font-weight:600;border-bottom:1px solid #ddd;padding:6px 8px}.prose td{vertical-align:top;border-bottom:1px solid #eee;padding:6px 8px}.prose td.mon{white-space:nowrap;font-weight:600}
        @media (max-width:900px){.app{grid-template-columns:1fr;height:auto}.side,.editor{overflow:visible}.editor{min-height:60vh}.tabs{position:static}}
        """;

    // ---- Start ----------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        boolean all = Arrays.asList(args).contains("--alle");
        String dir = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse(".");
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        CleanCodeReport report = new CleanCodeReport(root);
        report.analyse();
        int score = report.score();

        Path historyFile = root.resolve(".clean-code-history");
        List<int[]> history = new ArrayList<>();
        if (Files.exists(historyFile)) {
            for (String line : Files.readAllLines(historyFile)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[1].matches("\\d+")) history.add(new int[]{Integer.parseInt(parts[1])});
            }
        }
        Path out = root.resolve("target/clean-code-report.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.html(history), StandardCharsets.UTF_8);
        Files.writeString(historyFile, (Files.exists(historyFile) ? Files.readString(historyFile) : "")
            + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + score + "\n", StandardCharsets.UTF_8);

        String[] lv = level(score);
        System.out.println("Clean-Code-Report " + VERSION + ": " + score + " von 100, " + lv[0] + " " + lv[1]);
        Method boss = report.boss();
        if (boss != null) System.out.println("  Endgegner: " + boss.label() + " in " + boss.file.replaceAll(".*/", "") + ":" + boss.start + ", " + boss.codeLines + " Zeilen, Tiefe " + boss.depth + ", Komplexität " + boss.complexity + ", " + (boss.body == null ? 0 : boss.body.locals.size()) + " Variablen");
        Map<Family, Double> fpen = report.penaltyByFamily();
        System.out.println("  Familien: " + Arrays.stream(Family.values()).map(f -> f.emoji + " " + f.title + " −" + fmt(fpen.get(f)) + "/" + fmt(f.cap)).collect(Collectors.joining(" · ")));
        Map<Smell, Double> pen = report.penaltyBySmell();
        report.findings.stream().collect(Collectors.groupingBy(Finding::smell, () -> new EnumMap<>(Smell.class), Collectors.counting()))
            .entrySet().stream().sorted(Comparator.comparingDouble(e -> -pen.get(e.getKey())))
            .forEach(e -> System.out.println("  " + e.getKey().emoji + " " + e.getKey().monster + " (" + e.getKey().smell + "): " + e.getValue() + "× (−" + fmt(pen.get(e.getKey())) + ")"));
        if (all) {
            System.out.println("Alle Funde:");
            report.findings.stream().sorted(Comparator.comparing(Finding::file).thenComparingInt(Finding::line))
                .forEach(f -> System.out.println("  " + f.file.replaceAll(".*/", "") + ":" + f.line + " " + f.smell.emoji + " " + f.text + " (" + fmt(f.penalty) + ")"));
        }
        System.out.println("Bericht: " + root.relativize(out));
    }
}
