import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Clean-Code-Report: liest src/main/java und src/test/java, sucht die klassischen Smells und schreibt
 * einen Bericht nach target/clean-code-report.html. Kein Gate, keine Abhängigkeiten, JDK 17 reicht.
 *
 * Aufruf im Projektordner:  java .opencode/skills/clean-code-report/CleanCodeReport.java
 */
public class CleanCodeReport {

    // ---- Die Monster ------------------------------------------------------

    enum Smell {
        LONG_METHOD("🐍", "Die Schlange", "Lange Methode", "Extract Method: jede Methode höchstens 20 Zeilen, eine Abstraktionsebene"),
        ARROW("🏹", "Der Pfeil", "Tiefe Verschachtelung", "Guard Clauses und Extract Method: höchstens drei Ebenen"),
        COMPLEX("🌀", "Der Strudel", "Hohe Komplexität", "Extract Method, Map oder Polymorphismus statt if-Ketten"),
        MAGIC("🎩", "Der Zauberer", "Magische Zahlen und Texte", "Extract Constant: der Name sagt, was der Wert bedeutet"),
        TWINS("👯", "Die Zwillinge", "Doppelter Code", "Extract Method: einmal schreiben, zweimal rufen"),
        CRYPTIC("🕵️", "Agent X", "Kryptische Namen", "Rename: der Name sagt, was das Ding ist oder tut"),
        COMMENT("🧸", "Der Erklärbär", "Kommentare, die Code erklären", "Rename oder Extract Method, dann den Kommentar löschen"),
        ZOMBIE("🧟", "Der Zombie", "Toter und auskommentierter Code", "Remove: Git merkt sich alles"),
        PRINTER("🖨️", "Der Drucker", "System.out im Produktivcode", "Weg damit, oder ein Logger mit Absicht"),
        BLACK_HOLE("🕳️", "Das Schwarze Loch", "Leerer catch-Block", "Behandeln oder weiterwerfen, nie verschlucken"),
        LUGGAGE("🎒", "Der Kofferträger", "Zu viele Parameter", "Introduce Parameter Object"),
        CASTLE("🏰", "Die Burg", "Riesige Klasse", "Extract Class: eine Verantwortung je Klasse"),
        EXHIBITIONIST("🩲", "Der Exhibitionist", "Öffentliches Feld", "Encapsulate Field"),
        TEST_GRUMP("🧪", "Der Test-Muffel", "Tests ohne Namen oder ohne Prüfung", "Rename: der Name ist die Regel; ein Test prüft eine Regel");

        final String emoji, monster, smell, fix;
        Smell(String emoji, String monster, String smell, String fix) {
            this.emoji = emoji; this.monster = monster; this.smell = smell; this.fix = fix;
        }
    }

    record Finding(Smell smell, String file, int line, String text, double penalty) {}

    record Method(String file, String name, int start, int end, int params, int depth, int complexity, boolean test, String header) {
        int lines() { return end - start + 1; }
    }

    /** Eine Quelldatei: Originalzeilen, dazu der Text ohne Kommentare und Literalinhalte (gleiche Länge). */
    static class Source {
        final String file;
        final boolean test;
        final List<String> lines;
        final String text;
        final String stripped;
        final List<int[]> commentRanges = new ArrayList<>();   // [startOffset, endOffset)
        final List<int[]> stringLiterals = new ArrayList<>();  // [startOffset, endOffset)
        final List<int[]> charLiterals = new ArrayList<>();
        final List<Method> methods = new ArrayList<>();
        int[] lineStart;

        Source(Path path, Path root, boolean test) throws IOException {
            this.file = root.relativize(path).toString().replace('\\', '/');
            this.test = test;
            this.text = Files.readString(path, StandardCharsets.UTF_8);
            this.lines = Arrays.asList(text.split("\n", -1));
            this.stripped = strip();
            indexLines();
            findMethods();
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
                    while (i < e + 2) { out.append(text.charAt(i) == '\n' ? '\n' : ' '); i++; }
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
            return lo + 1; // 1-basiert
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

        static final Pattern HEADER = Pattern.compile(
            "^(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
            + "(?:<[^>]+>\\s*)?(?:[\\w.$<>\\[\\],?]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s$]+)?$", Pattern.DOTALL);
        static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "do", "try", "super", "this");
        static final Pattern BRANCH = Pattern.compile("\\b(if|for|while|case|catch)\\b|&&|\\|\\||\\?(?!>)");

        private void findMethods() {
            int depth = 0;
            int segStart = 0;
            List<int[]> open = new ArrayList<>(); // [depth, headerOffset, nameOffset, params, isTest]
            Map<Integer, Object[]> pending = new HashMap<>();
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (c == ';' || c == '}' || c == '{') {
                    if (c == '{') {
                        String header = stripped.substring(segStart, i).trim();
                        Matcher m = HEADER.matcher(header);
                        boolean isType = header.matches("(?s).*\\b(class|record|interface|enum)\\b.*");
                        boolean isAnon = header.matches("(?s).*\\bnew\\s+[\\w.$<>]+\\s*\\([^)]*\\)\\s*$");
                        if (!isType && !isAnon && !header.contains("->") && m.matches() && !KEYWORDS.contains(m.group(1))) {
                            String params = m.group(2).trim();
                            int count = params.isEmpty() ? 0 : params.split(",").length;
                            int nameOff = segStart + header.indexOf(m.group(1) + "(") ;
                            if (nameOff < segStart) nameOff = segStart + stripped.substring(segStart, i).indexOf(m.group(1));
                            boolean isTest = test && header.contains("@Test");
                            pending.put(depth, new Object[]{m.group(1), nameOff, count, isTest, header});
                        }
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        Object[] p = pending.remove(depth);
                        if (p != null) {
                            int nameOff = (Integer) p[1];
                            int startLine = lineOf(nameOff);
                            int endLine = lineOf(i);
                            String body = stripped.substring(nameOff, i);
                            int maxDepth = 0, d = 0;
                            for (int k = 0; k < body.length(); k++) {
                                char b = body.charAt(k);
                                if (b == '{') { d++; maxDepth = Math.max(maxDepth, d); } else if (b == '}') d--;
                            }
                            int complexity = 1;
                            Matcher bm = BRANCH.matcher(body);
                            while (bm.find()) complexity++;
                            methods.add(new Method(file, (String) p[0], startLine, endLine, (Integer) p[2], maxDepth - 1, complexity, (Boolean) p[3], (String) p[4]));
                        }
                    }
                    segStart = i + 1;
                }
            }
        }

        Method methodAt(int line) {
            Method best = null;
            for (Method m : methods) {
                if (m.start <= line && line <= m.end && (best == null || m.lines() < best.lines())) best = m;
            }
            return best;
        }

        String snippet(int line) {
            String s = line >= 1 && line <= lines.size() ? lines.get(line - 1).trim() : "";
            return s.length() > 90 ? s.substring(0, 87) + "…" : s;
        }
    }

    // ---- Analyse ----------------------------------------------------------

    static final Set<String> OK_SHORT = Set.of("i", "j", "k", "e", "id", "ex", "io");
    static final Set<String> CRYPTIC_NAMES = Set.of("tmp", "temp", "foo", "bar", "baz", "stuff", "data", "obj", "val", "res", "ret", "str",
        "num", "cnt", "arr", "lst", "cur", "flag", "var", "thing", "helper", "handle", "process", "manage", "doit", "dostuff", "calc", "func", "fn");

    final List<Finding> findings = new ArrayList<>();
    final List<Source> sources = new ArrayList<>();
    final Path root;

    CleanCodeReport(Path root) { this.root = root; }

    void analyse() throws IOException {
        for (Path dir : List.of(root.resolve("src/main/java"), root.resolve("src/test/java"))) {
            if (!Files.isDirectory(dir)) continue;
            boolean test = dir.endsWith(Paths.get("src", "test", "java"));
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                    sources.add(new Source(p, root, test));
                }
            }
        }
        for (Source src : sources) {
            if (src.test) testSmells(src); else mainSmells(src);
        }
        duplicates();
    }

    void add(Smell smell, Source src, int line, String text, double penalty) {
        findings.add(new Finding(smell, src.file, line, text, penalty));
    }

    void mainSmells(Source src) {
        int fileLines = src.lines.size();
        if (fileLines > 200) add(Smell.CASTLE, src, 1, src.file + " hat " + fileLines + " Zeilen", 5 + (fileLines - 200) / 50.0);

        for (Method m : src.methods) {
            if (m.lines() > 20) add(Smell.LONG_METHOD, src, m.start, m.name + "() hat " + m.lines() + " Zeilen", Math.min(15, 2 + (m.lines() - 20) / 5.0));
            if (m.depth > 3) add(Smell.ARROW, src, m.start, m.name + "() verschachtelt " + m.depth + " Ebenen tief", Math.min(12, 3.0 * (m.depth - 3)));
            if (m.complexity > 10) add(Smell.COMPLEX, src, m.start, m.name + "() hat Komplexität " + m.complexity, Math.min(12, (double) (m.complexity - 10)));
            if (m.params > 3) add(Smell.LUGGAGE, src, m.start, m.name + "() nimmt " + m.params + " Parameter", 2);
            if (m.name.length() <= 2 || m.name.matches(".*\\d$") || CRYPTIC_NAMES.contains(m.name.toLowerCase(Locale.ROOT)))
                add(Smell.CRYPTIC, src, m.start, "Methode " + m.name + "()", 1);
        }

        // Magische Zahlen und Texte, nur in Methodenrümpfen
        Pattern number = Pattern.compile("(?<![\\w.])(-?\\d+(?:\\.\\d+)?)[lLfFdD]?(?![\\w.])");
        int magicCount = 0;
        for (Method m : src.methods) {
            int from = src.lineStart[m.start - 1], to = src.lineStart[Math.min(m.end, src.lines.size())];
            Matcher nm = number.matcher(src.stripped.substring(from, to));
            while (nm.find()) {
                String v = nm.group(1).replace("-", "");
                if (v.equals("0") || v.equals("1")) continue;
                int line = src.lineOf(from + nm.start());
                add(Smell.MAGIC, src, line, "Zahl " + nm.group(1) + " in " + m.name + "()", 1);
                magicCount++;
            }
        }
        for (int[] lit : src.stringLiterals) {
            int line = src.lineOf(lit[0]);
            Method m = src.methodAt(line);
            if (m == null) continue;
            String value = src.text.substring(lit[0], lit[1]);
            if (value.length() <= 2 || value.startsWith("\"\"\"")) continue;
            add(Smell.MAGIC, src, line, "Text " + value + " in " + m.name + "()", 0.5);
        }

        // Kommentare in Methodenrümpfen: Code-Leichen oder Erklärbär
        for (int[] cr : src.commentRanges) {
            int line = src.lineOf(cr[0]);
            if (src.methodAt(line) == null) continue;
            String c = src.text.substring(cr[0], cr[1]).replaceAll("^/[/*]+\\s*|\\s*\\*/$", "").trim();
            if (c.isEmpty()) continue;
            boolean code = c.matches(".*[;{}]\\s*$") || c.matches("^(return|if\\s*\\(|for\\s*\\(|while\\s*\\(|int |String |System\\.|throw ).*");
            if (code) add(Smell.ZOMBIE, src, line, "Auskommentierter Code: " + c, 2);
            else add(Smell.COMMENT, src, line, "Kommentar: " + c, 1);
        }

        // Kryptische Namen: Deklarationen und Parameter
        Pattern decl = Pattern.compile("\\b(?:int|long|short|byte|double|float|boolean|char|String|var|[A-Z][\\w$]*(?:<[^>]*>)?(?:\\[\\])*)\\s+([a-z][\\w$]*)\\s*(?=[=;,)\\[:])");
        Set<String> seen = new HashSet<>();
        Matcher dm = decl.matcher(src.stripped);
        while (dm.find()) {
            String name = dm.group(1);
            int line = src.lineOf(dm.start(1));
            Method m = src.methodAt(line);
            if (m == null) continue;
            boolean cryptic = (name.length() <= 2 && !OK_SHORT.contains(name)) || CRYPTIC_NAMES.contains(name.toLowerCase(Locale.ROOT)) || name.matches("[a-z]+\\d+");
            if (cryptic && seen.add(m.name + ":" + name)) add(Smell.CRYPTIC, src, line, "Variable " + name + " in " + m.name + "()", 1);
        }

        // Toter Code: private Methoden ohne Aufrufer
        String className = src.file.replaceAll(".*/", "").replace(".java", "");
        for (Method m : src.methods) {
            if (!m.header.contains("private") || m.name.equals(className)) continue; // private Konstruktoren sind Absicht
            Matcher um = Pattern.compile("\\b" + Pattern.quote(m.name) + "\\s*\\(").matcher(src.stripped);
            int uses = 0;
            while (um.find()) uses++;
            if (uses <= 1) add(Smell.ZOMBIE, src, m.start, "Private Methode " + m.name + "() ruft niemand", 3);
        }

        Matcher pm = Pattern.compile("System\\.(out|err)\\.print").matcher(src.stripped);
        while (pm.find()) add(Smell.PRINTER, src, src.lineOf(pm.start()), src.snippet(src.lineOf(pm.start())), 2);

        Matcher em = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}").matcher(src.stripped);
        while (em.find()) add(Smell.BLACK_HOLE, src, src.lineOf(em.start()), src.snippet(src.lineOf(em.start())), 4);

        Matcher fm = Pattern.compile("(?m)^\\s*public\\s+(?!static\\s+final|final\\s)[\\w<>\\[\\],. ]+\\s+(\\w+)\\s*(=[^;]*)?;").matcher(src.stripped);
        while (fm.find()) add(Smell.EXHIBITIONIST, src, src.lineOf(fm.start(1)), "Feld " + fm.group(1) + " ist öffentlich und veränderbar", 2);
    }

    void testSmells(Source src) {
        for (Method m : src.methods) {
            if (!m.test) continue;
            int from = src.lineStart[m.start - 1], to = src.lineStart[Math.min(m.end, src.lines.size())];
            String body = src.stripped.substring(from, to);
            if (m.name.matches("^(test|check|verify|it|should)\\d*$") || m.name.matches("^test[A-Z]?$"))
                add(Smell.TEST_GRUMP, src, m.start, m.name + "(): der Name sagt nicht, welche Regel geprüft wird", 1);
            Matcher am = Pattern.compile("\\b(assert\\w*|verify|fail)\\s*\\(").matcher(body);
            int asserts = 0;
            while (am.find()) asserts++;
            boolean expects = m.header.contains("expected");
            if (asserts == 0 && !expects) add(Smell.TEST_GRUMP, src, m.start, m.name + "() prüft nichts", 3);
            if (asserts > 5) add(Smell.TEST_GRUMP, src, m.start, m.name + "() prüft " + asserts + " Dinge auf einmal", 2);
        }
    }

    static final Set<String> TRIVIAL = Set.of("}", "{", "} else {", "else {", "return;", "break;", "continue;", "});", "})", "try {");

    void duplicates() {
        Map<String, List<int[]>> windows = new LinkedHashMap<>(); // hash -> [sourceIndex, firstLine(0-based), lastLine]
        int window = 4;
        for (int si = 0; si < sources.size(); si++) {
            Source src = sources.get(si);
            List<Integer> idx = new ArrayList<>();
            List<String> norm = new ArrayList<>();
            for (int l = 0; l < src.lines.size(); l++) {
                int from = src.lineStart[l], to = Math.min(src.lineStart[l + 1] - 1, src.stripped.length());
                String s = src.stripped.substring(from, Math.max(from, to)).trim().replaceAll("\\s+", " ");
                if (s.length() < 8 || TRIVIAL.contains(s) || s.startsWith("import ") || s.startsWith("package ") || s.startsWith("@")) continue;
                idx.add(l); norm.add(s);
            }
            for (int w = 0; w + window <= norm.size(); w++) {
                String key = String.join("\n", norm.subList(w, w + window));
                windows.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{si, idx.get(w), idx.get(w + window - 1)});
            }
        }
        // Fenster mit mehreren Vorkommen zu Blöcken zusammenfassen (je Quelle zusammenhängende Zeilen)
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
                String where = first == null ? "" : " von " + sources.get(first[0]).file + ":" + (first[1] + 1);
                add(Smell.TWINS, src, start + 1, "Zeilen " + (start + 1) + " bis " + l + " sind ein Zwilling" + where, 4);
            }
        }
    }

    // ---- Wertung ------------------------------------------------------------

    static final Map<Smell, Double> CAP = new EnumMap<>(Map.ofEntries(
        Map.entry(Smell.LONG_METHOD, 30.0), Map.entry(Smell.ARROW, 24.0), Map.entry(Smell.COMPLEX, 24.0), Map.entry(Smell.MAGIC, 15.0),
        Map.entry(Smell.TWINS, 16.0), Map.entry(Smell.CRYPTIC, 15.0), Map.entry(Smell.COMMENT, 6.0), Map.entry(Smell.ZOMBIE, 10.0),
        Map.entry(Smell.PRINTER, 6.0), Map.entry(Smell.BLACK_HOLE, 12.0), Map.entry(Smell.LUGGAGE, 8.0), Map.entry(Smell.CASTLE, 15.0),
        Map.entry(Smell.EXHIBITIONIST, 6.0), Map.entry(Smell.TEST_GRUMP, 15.0)));

    Map<Smell, Double> penaltyBySmell() {
        Map<Smell, Double> p = new EnumMap<>(Smell.class);
        for (Smell s : Smell.values()) {
            double sum = findings.stream().filter(f -> f.smell == s).mapToDouble(Finding::penalty).sum();
            p.put(s, Math.min(CAP.get(s), sum));
        }
        return p;
    }

    int score() {
        double total = penaltyBySmell().values().stream().mapToDouble(Double::doubleValue).sum();
        return (int) Math.max(0, Math.round(100 - total));
    }

    static String[] level(int score) {
        if (score >= 90) return new String[]{"🏆", "Clean-Code-Meister", "Hier gibt es nichts zu sehen. Weitergehen."};
        if (score >= 70) return new String[]{"🔧", "Geselle", "Solide Arbeit. Ein paar Monster lauern noch."};
        if (score >= 50) return new String[]{"🧹", "Lehrling", "Die Monster haben sich häuslich eingerichtet."};
        if (score >= 25) return new String[]{"🍝", "Spaghetti-Koch", "Al dente, aber niemand findet den Anfang."};
        return new String[]{"🦖", "Legacy-Legende", "Archäologen werden hier eines Tages graben."};
    }

    // ---- HTML -----------------------------------------------------------------

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static final Pattern KEYWORD = Pattern.compile("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|var|record|yield|sealed|permits|true|false|null)\\b");
    static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?[lLfFdD]?\\b");
    static final Pattern ANNOTATION = Pattern.compile("@\\w+");

    /** Eine Zeile mit Syntaxfarben: Kommentare und Literale aus dem Scanner, Schlüsselwörter per Regex. */
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
                String cls = range[2] == 0 ? "cm" : "st";
                out.append("<span class=\"").append(cls).append("\">").append(esc(src.text.substring(i, e))).append("</span>");
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

    /** [start, end, art] der Kommentar- oder Literalspanne an dieser Stelle, sonst null. */
    static int[] rangeAt(Source src, int offset) {
        for (int[] r : src.commentRanges) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 0};
        for (int[] r : src.stringLiterals) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 1};
        for (int[] r : src.charLiterals) if (r[0] <= offset && offset < r[1]) return new int[]{r[0], r[1], 1};
        return null;
    }

    Method boss() {
        Method boss = null; double bossPen = 0;
        for (Source src : sources) for (Method m : src.methods) {
            double p = findings.stream().filter(f -> f.file.equals(src.file) && f.line >= m.start && f.line <= m.end && f.smell != Smell.CASTLE).mapToDouble(Finding::penalty).sum();
            if (p > bossPen) { bossPen = p; boss = m; }
        }
        return bossPen >= 5 ? boss : null;
    }

    double bossPenalty(Method boss) {
        return findings.stream().filter(f -> f.file.equals(boss.file) && f.line >= boss.start && f.line <= boss.end && f.smell != Smell.CASTLE).mapToDouble(Finding::penalty).sum();
    }

    String html(List<int[]> history) {
        int score = score();
        String[] lv = level(score);
        Map<Smell, Double> pen = penaltyBySmell();
        List<Smell> order = Arrays.stream(Smell.values()).sorted(Comparator.comparingDouble((Smell s) -> -pen.get(s))).collect(Collectors.toList());
        long beaten = Arrays.stream(Smell.values()).filter(s -> pen.get(s) == 0).count();
        String project = root.toAbsolutePath().getFileName().toString();
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        Map<String, Integer> fileIndex = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) fileIndex.put(sources.get(i).file, i);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        h.append("<title>Clean-Code-Report ").append(esc(project)).append("</title><style>").append(CSS).append("</style></head><body><div class=\"app\">");

        // ---- Linke Seite: Wertung und Liste
        h.append("<aside class=\"side\"><div class=\"top\"><p class=\"kicker\">Clean-Code-Report</p><h1>").append(esc(project)).append("</h1><p class=\"meta\">")
         .append(when).append(" · ").append(sources.size()).append(" Dateien · ").append(findings.size()).append(" Funde</p>");

        double angle = Math.PI * (1 - score / 100.0);
        double x = 100 + 78 * Math.cos(angle), y = 100 - 78 * Math.sin(angle);
        String arcColor = score >= 70 ? "#38c172" : score >= 40 ? "#f6b73c" : "#e5484d";
        h.append("<div class=\"hero\"><svg viewBox=\"0 0 200 112\" width=\"170\" height=\"95\" role=\"img\" aria-label=\"Punktestand ").append(score).append("\">")
         .append("<path d=\"M22 100 A78 78 0 0 1 178 100\" fill=\"none\" stroke=\"rgba(255,255,255,.12)\" stroke-width=\"16\" stroke-linecap=\"round\"/>");
        if (score > 0) h.append("<path d=\"M22 100 A78 78 0 ").append(score > 50 ? 1 : 0).append(" 1 ").append(fmt(x)).append(" ").append(fmt(y))
         .append("\" fill=\"none\" stroke=\"").append(arcColor).append("\" stroke-width=\"16\" stroke-linecap=\"round\"/>");
        h.append("<text x=\"100\" y=\"92\" text-anchor=\"middle\" class=\"score\">").append(score).append("</text>")
         .append("<text x=\"100\" y=\"109\" text-anchor=\"middle\" class=\"of\">von 100</text></svg>");
        h.append("<div class=\"level\"><p class=\"rank\"><span class=\"big\">").append(lv[0]).append("</span> ").append(lv[1]).append("</p><p class=\"tag\">").append(lv[2]).append("</p>")
         .append("<p class=\"stat\">").append(beaten).append(" von ").append(Smell.values().length).append(" Monster besiegt</p></div></div>");
        if (!history.isEmpty()) {
            h.append("<p class=\"history\">Verlauf ");
            for (int[] hs : history) h.append("<span class=\"pill\">").append(hs[0]).append("</span>");
            h.append("<span class=\"pill now\">").append(score).append("</span></p>");
        }

        Method boss = boss();
        if (boss != null) {
            double bp = bossPenalty(boss);
            int hp = (int) Math.min(100, Math.round(bp * 2));
            h.append("<a class=\"boss\" href=\"#f").append(fileIndex.get(boss.file)).append("-L").append(boss.start).append("\"><p class=\"kicker\">Endgegner</p><p class=\"name\">").append(esc(boss.name)).append("()<span class=\"where\">")
             .append(esc(boss.file.replaceAll(".*/", ""))).append(":").append(boss.start).append("</span></p><div class=\"hp\"><div style=\"width:").append(hp).append("%\"></div></div>")
             .append("<p class=\"stats\"><span>").append(boss.lines()).append(" Zeilen</span><span>Tiefe ").append(boss.depth).append("</span><span>Komplexität ").append(boss.complexity).append("</span><span>").append(fmt(bp)).append(" Strafpunkte</span></p></a>");
        }
        h.append("</div>");

        h.append("<nav class=\"list\">");
        if (findings.isEmpty()) {
            h.append("<p class=\"empty\">").append(sources.isEmpty() ? "Unter src/ liegt noch kein Java-Code." : "Kein Monster in Sicht. Alle Regeln erfüllt.").append("</p>");
        }
        h.append("<h2>Monster</h2>");
        for (Smell s : order) {
            List<Finding> fs = findings.stream().filter(f -> f.smell == s).sorted(Comparator.comparingDouble((Finding f) -> -f.penalty).thenComparing(Finding::file).thenComparingInt(Finding::line)).collect(Collectors.toList());
            boolean beat = fs.isEmpty();
            h.append("<details class=\"monster").append(beat ? " beaten\"" : "\" open").append("><summary><span class=\"emoji\">").append(s.emoji).append("</span><span class=\"names\"><b>").append(s.monster).append("</b><small>").append(s.smell).append("</small></span>")
             .append("<span class=\"count\">").append(beat ? "besiegt" : fs.size() + "×").append("</span>").append(beat ? "" : "<span class=\"pen\">−" + fmt(pen.get(s)) + "</span>").append("</summary>");
            if (beat) {
                h.append("<p class=\"fix ok\">Kein Fund. Sauber.</p>");
            } else {
                h.append("<p class=\"fix\">").append(esc(s.fix)).append("</p><ul>");
                for (Finding f : fs) {
                    h.append("<li><a href=\"#f").append(fileIndex.get(f.file)).append("-L").append(f.line).append("\"><code>").append(esc(f.file.replaceAll(".*/", ""))).append(":").append(f.line).append("</code> ").append(esc(f.text)).append("</a><span class=\"pts\">").append(fmt(f.penalty)).append("</span></li>");
                }
                h.append("</ul>");
            }
            h.append("</details>");
        }
        h.append("<h2>Dateien</h2><ul class=\"files\">");
        for (int i = 0; i < sources.size(); i++) {
            Source src = sources.get(i);
            long n = findings.stream().filter(f -> f.file.equals(src.file)).count();
            h.append("<li><a href=\"#f").append(i).append("\"><code>").append(esc(src.file.replaceAll(".*/", ""))).append("</code><small>").append(esc(src.file.replaceAll("/[^/]*$", ""))).append("</small></a><span class=\"pts\">").append(n == 0 ? "✓" : n + " Funde").append("</span></li>");
        }
        h.append("</ul><details class=\"rules\"><summary>Die Regeln</summary><p>Methoden höchstens 20 Zeilen, höchstens drei Ebenen tief, Komplexität höchstens 10, höchstens drei Parameter, Klassen höchstens 200 Zeilen. Zahlen außer 0 und 1 und Texte in Methoden sind magisch. Vier gleiche Zeilen in Folge sind Zwillinge. Tests heißen nach der Regel, die sie prüfen, und prüfen genau eine.</p><p>Der Bericht ist kein Gate: Er zeigt, er entscheidet nicht. Erzeugt von <code>CleanCodeReport.java</code>.</p></details></nav></aside>");

        // ---- Rechte Seite: die Dateien wie im Editor
        h.append("<main class=\"editor\"><div class=\"tabs\">");
        for (int i = 0; i < sources.size(); i++) {
            h.append("<a class=\"tab\" href=\"#f").append(i).append("\" data-file=\"").append(i).append("\">").append(esc(sources.get(i).file.replaceAll(".*/", ""))).append("</a>");
        }
        h.append("<label class=\"toggle\"><input type=\"checkbox\" id=\"hints\" checked> Hinweise im Code</label></div>");
        if (sources.isEmpty()) h.append("<section class=\"file\"><p class=\"nothing\">Kein Java-Code unter src/.</p></section>");
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
        h.append("</main></div><script>").append(JS).append("</script></body></html>");
        return h.toString();
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.GERMANY, "%.1f", d);
    }

    static final String JS = """
        (function(){
          var files=[].slice.call(document.querySelectorAll('.file')),tabs=[].slice.call(document.querySelectorAll('.tab')),editor=document.querySelector('.editor');
          function show(i,line){files.forEach(function(f,k){f.hidden=(k!==i)});tabs.forEach(function(t,k){t.classList.toggle('active',k===i)});
            if(line){var el=document.getElementById('f'+i+'-L'+line);if(el){el.scrollIntoView({block:'center'});el.classList.remove('flash');void el.offsetWidth;el.classList.add('flash');}}else{editor.scrollTop=0;}}
          function route(){var m=location.hash.match(/^#f(\\d+)(?:-L(\\d+))?$/);if(m&&files[+m[1]])show(+m[1],m[2]?+m[2]:0);else if(files.length)show(0,0);}
          window.addEventListener('hashchange',route);route();
          var box=document.getElementById('hints');if(box){box.addEventListener('change',function(){document.body.classList.toggle('nohints',!box.checked)});}
        })();
        """;

    static final String CSS = """
        :root{--bg:#081533;--bg2:#0d2150;--edge:rgba(255,255,255,.16);--ink:#eef4ff;--soft:#a9bddb;--ice:#9fd8ff;--beam:#3d8dff;--paper:#fff;--text:#080808;--gutter:#999;--kw:#0033b3;--st:#067d17;--nu:#1750eb;--cm:#8c8c8c;--an:#9e880d}
        *{box-sizing:border-box}html,body{margin:0;height:100%}body{background:var(--bg);color:var(--ink);font-family:"Avenir Next","Segoe UI","Helvetica Neue",Arial,sans-serif;line-height:1.45}
        .app{display:grid;grid-template-columns:400px 1fr;height:100vh}
        .side{overflow-y:auto;background:linear-gradient(180deg,var(--bg2),var(--bg));border-right:1px solid var(--edge)}
        .top{padding:20px 18px 12px;border-bottom:1px solid var(--edge)}.kicker{margin:0;color:var(--ice);font-size:.8rem;letter-spacing:.04em}h1{margin:2px 0 2px;font-size:1.45rem}.meta{margin:0 0 10px;color:var(--soft);font-size:.85rem}
        .hero{display:flex;align-items:center;gap:12px}.score{font-size:40px;font-weight:700;fill:#fff}.of{font-size:11px;fill:var(--soft)}
        .rank{margin:0;font-size:1.15rem;font-weight:600}.big{font-size:1.5rem;vertical-align:middle}.tag{margin:2px 0 4px;color:var(--soft);font-size:.9rem}.stat{margin:0;color:var(--ice);font-size:.85rem}
        .history{margin:8px 0 0;color:var(--soft);font-size:.8rem}.pill{display:inline-block;margin:2px 3px 0 0;padding:0 8px;border-radius:999px;background:rgba(255,255,255,.1)}.pill.now{background:var(--beam);color:#fff}
        .boss{display:block;margin-top:12px;padding:10px 12px;border:1px solid var(--edge);border-radius:12px;background:rgba(255,255,255,.06);color:inherit;text-decoration:none}.boss:hover{border-color:var(--ice)}
        .boss .name{margin:0;font-size:1.05rem;font-weight:600}.where{margin-left:8px;font-size:.8rem;color:var(--soft);font-weight:400}.hp{height:10px;border-radius:999px;background:rgba(0,0,0,.4);overflow:hidden;margin:6px 0 8px}.hp div{height:100%;background:linear-gradient(90deg,#e5484d,#ff8a80)}
        .stats{display:flex;flex-wrap:wrap;gap:6px;margin:0}.stats span{padding:1px 8px;border-radius:999px;background:rgba(3,12,30,.5);border:1px solid rgba(159,216,255,.25);color:var(--ice);font-size:.78rem}
        .list{padding:10px 12px 24px}.list h2{margin:12px 6px 6px;font-size:.85rem;color:var(--ice);letter-spacing:.03em;font-weight:600}.empty{margin:8px 6px;color:var(--soft)}
        .monster{margin:4px 0;border:1px solid var(--edge);border-radius:12px;background:rgba(255,255,255,.05)}.monster.beaten{opacity:.5}.monster summary{display:flex;align-items:center;gap:10px;padding:8px 10px;cursor:pointer;list-style:none}.monster summary::-webkit-details-marker{display:none}
        .emoji{font-size:1.4rem}.names{flex:1;min-width:0}.names b{display:block;font-size:.95rem}.names small{color:var(--soft)}.count{padding:1px 8px;border-radius:999px;background:#e5484d;color:#fff;font-size:.78rem;font-weight:600}.beaten .count{background:#38c172}.pen{color:#ff8a80;font-size:.8rem;min-width:2.6em;text-align:right}
        .fix{margin:0 10px 6px;color:var(--ice);font-size:.82rem}.fix.ok{color:var(--soft);margin-bottom:8px}.monster ul{list-style:none;margin:0;padding:0 6px 6px}.monster li{display:flex;align-items:baseline;gap:6px;padding:3px 4px;border-radius:6px;font-size:.85rem}.monster li:hover{background:rgba(255,255,255,.07)}
        .monster li a{flex:1;color:inherit;text-decoration:none}.monster li a:hover{color:#fff}.pts{color:#ff8a80;font-size:.8rem;white-space:nowrap}
        .files{list-style:none;margin:0;padding:0}.files li{display:flex;align-items:center;gap:6px;padding:5px 8px;border-radius:8px;font-size:.88rem}.files li:hover{background:rgba(255,255,255,.07)}.files a{flex:1;color:inherit;text-decoration:none;display:flex;flex-direction:column}.files small{color:var(--soft);font-size:.72rem}.files .pts{color:var(--soft)}
        .rules{margin:14px 6px 0;color:var(--soft);font-size:.8rem}.rules summary{cursor:pointer;color:var(--ice)}
        code{font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,"Liberation Mono",monospace;font-size:.9em;color:var(--ice)}
        .editor{overflow-y:auto;background:var(--paper);color:var(--text)}.tabs{position:sticky;top:0;z-index:2;display:flex;align-items:center;gap:2px;padding:6px 10px 0;background:#f2f2f2;border-bottom:1px solid #d9d9d9}
        .tab{padding:6px 14px;border:1px solid transparent;border-bottom:0;border-radius:6px 6px 0 0;color:#444;text-decoration:none;font-size:.85rem;font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,monospace}.tab.active{background:#fff;border-color:#d9d9d9;color:#000;position:relative;top:1px}.toggle{margin-left:auto;font-size:.8rem;color:#555;padding-bottom:6px}
        .file{padding:14px 0 40px}.crumbs{padding:0 18px;color:#777;font-size:.8rem}.classname{margin:2px 18px 12px;font-size:1.3rem;font-weight:600;color:#000}.nothing{padding:20px;color:#666}
        .code{font-family:"JetBrains Mono","SF Mono",Menlo,Consolas,"Liberation Mono",monospace;font-size:13px;line-height:1.55;color:var(--text)}
        .line{display:flex;align-items:stretch;white-space:pre}.line.hit{background:#fff4f2}.line.flash{animation:flash 1.6s ease-out}@keyframes flash{0%{background:#ffe58f}100%{background:#fff4f2}}
        .gutter{flex:0 0 96px;display:flex;justify-content:flex-end;align-items:center;gap:6px;padding-right:8px;color:var(--gutter);background:#fafafa;border-right:1px solid #ececec;user-select:none}.no{min-width:2.6em;text-align:right}.icons{width:44px;text-align:left;font-size:12px;cursor:help}
        .depth{flex:0 0 6px;margin-right:12px}.d1{background:#eef4ff}.d2{background:#dbe7ff}.d3{background:#ffe9b8}.d4{background:#ffd27a}.d5{background:#ffb1a8}.d6{background:#ff7b6e}
        .src{flex:1;padding-right:18px}.kw{color:var(--kw);font-weight:600}.st{color:var(--st)}.nu{color:var(--nu)}.cm{color:var(--cm);font-style:italic}.an{color:var(--an)}
        .hint{margin:0 18px 2px 114px;padding:3px 10px;border-left:3px solid #f6b73c;background:#fff8e1;color:#5a4a00;font-size:12.5px;font-family:"Avenir Next","Segoe UI",Arial,sans-serif;border-radius:0 6px 6px 0}.hint i{color:#7a6a20}
        .nohints .hint{display:none}
        @media (max-width:900px){.app{grid-template-columns:1fr;height:auto}.side,.editor{overflow:visible}.editor{min-height:60vh}.tabs{position:static}}
        """;

    // ---- Start --------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
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
        System.out.println("Clean-Code-Report: " + score + " von 100, " + lv[0] + " " + lv[1]);
        Method boss = report.boss();
        if (boss != null) System.out.println("  Endgegner: " + boss.name + "() in " + boss.file.replaceAll(".*/", "") + ":" + boss.start + ", " + boss.lines() + " Zeilen, Tiefe " + boss.depth + ", Komplexität " + boss.complexity);
        Map<Smell, Double> pen = report.penaltyBySmell();
        report.findings.stream().collect(Collectors.groupingBy(Finding::smell, () -> new EnumMap<>(Smell.class), Collectors.counting()))
            .entrySet().stream().sorted(Comparator.comparingDouble(e -> -pen.get(e.getKey())))
            .forEach(e -> System.out.println("  " + e.getKey().emoji + " " + e.getKey().monster + ": " + e.getValue() + "× (−" + fmt(pen.get(e.getKey())) + ")"));
        System.out.println("Bericht: " + root.relativize(out));
    }
}
