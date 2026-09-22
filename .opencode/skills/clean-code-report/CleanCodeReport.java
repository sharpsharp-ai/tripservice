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
 * Aufruf im Projektordner:  java tools/CleanCodeReport.java
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
                    out.append('\''); i++;
                    while (i < n && text.charAt(i) != '\'' && text.charAt(i) != '\n') {
                        if (text.charAt(i) == '\\') { out.append(' '); i++; }
                        out.append(' '); i++;
                    }
                    if (i < n) { out.append('\''); i++; }
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

    String html(List<int[]> history) {
        int score = score();
        String[] lv = level(score);
        Map<Smell, Double> pen = penaltyBySmell();
        List<Smell> order = Arrays.stream(Smell.values()).sorted(Comparator.comparingDouble((Smell s) -> -pen.get(s))).collect(Collectors.toList());
        long beaten = Arrays.stream(Smell.values()).filter(s -> pen.get(s) == 0).count();
        String project = root.toAbsolutePath().getFileName().toString();
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        h.append("<title>Clean-Code-Report ").append(esc(project)).append("</title><style>").append(CSS).append("</style></head><body>");
        h.append("<header><div class=\"wrap\"><p class=\"kicker\">Clean-Code-Report</p><h1>").append(esc(project)).append("</h1><p class=\"meta\">")
         .append(when).append(" · ").append(sources.size()).append(" Dateien · ").append(findings.size()).append(" Funde</p></div></header>");

        // Hero: Gauge + Level
        double angle = Math.PI * (1 - score / 100.0);
        double x = 150 + 120 * Math.cos(angle), y = 150 - 120 * Math.sin(angle);
        String arcColor = score >= 70 ? "#38c172" : score >= 40 ? "#f6b73c" : "#e5484d";
        h.append("<section class=\"hero wrap\"><div class=\"gauge\"><svg viewBox=\"0 0 300 170\" width=\"300\" height=\"170\" role=\"img\" aria-label=\"Punktestand ").append(score).append("\">")
         .append("<path d=\"M30 150 A120 120 0 0 1 270 150\" fill=\"none\" stroke=\"rgba(255,255,255,.12)\" stroke-width=\"22\" stroke-linecap=\"round\"/>");
        if (score > 0) h.append("<path d=\"M30 150 A120 120 0 ").append(score > 50 ? 1 : 0).append(" 1 ").append(fmt(x)).append(" ").append(fmt(y))
         .append("\" fill=\"none\" stroke=\"").append(arcColor).append("\" stroke-width=\"22\" stroke-linecap=\"round\"/>");
        h.append("<text x=\"150\" y=\"140\" text-anchor=\"middle\" class=\"score\">").append(score).append("</text>")
         .append("<text x=\"150\" y=\"165\" text-anchor=\"middle\" class=\"of\">von 100</text></svg></div>");
        h.append("<div class=\"level\"><p class=\"rank\"><span class=\"big\">").append(lv[0]).append("</span> ").append(lv[1]).append("</p><p class=\"tag\">").append(lv[2]).append("</p>")
         .append("<p class=\"stat\">").append(beaten).append(" von ").append(Smell.values().length).append(" Monster besiegt</p>");
        if (!history.isEmpty()) {
            h.append("<p class=\"history\">Verlauf: ");
            for (int[] hs : history) h.append("<span class=\"pill\" title=\"").append(hs[0]).append(" Punkte\">").append(hs[0]).append("</span>");
            h.append("<span class=\"pill now\">").append(score).append("</span></p>");
        }
        h.append("</div></section>");

        if (findings.isEmpty()) {
            h.append("<section class=\"wrap card empty\"><h2>Kein Monster in Sicht</h2><p>")
             .append(sources.isEmpty() ? "Unter src/ liegt noch kein Java-Code." : "Alle Regeln erfüllt. Jetzt nicht nachlassen.").append("</p></section>");
        }

        // Boss
        Method boss = null; double bossPen = 0;
        for (Source src : sources) for (Method m : src.methods) {
            double p = findings.stream().filter(f -> f.file.equals(src.file) && f.line >= m.start && f.line <= m.end && f.smell != Smell.CASTLE).mapToDouble(Finding::penalty).sum();
            if (p > bossPen) { bossPen = p; boss = m; }
        }
        if (boss != null && bossPen >= 5) {
            int hp = (int) Math.min(100, Math.round(bossPen * 2));
            h.append("<section class=\"wrap card boss\"><p class=\"kicker\">Endgegner</p><h2>").append(esc(boss.name)).append("()<span class=\"where\">").append(esc(boss.file)).append(":").append(boss.start).append("</span></h2>")
             .append("<div class=\"hp\"><div style=\"width:").append(hp).append("%\"></div></div>")
             .append("<p class=\"stats\"><span>").append(boss.lines()).append(" Zeilen</span><span>Tiefe ").append(boss.depth).append("</span><span>Komplexität ").append(boss.complexity).append("</span><span>").append(boss.params).append(" Parameter</span><span>").append(fmt(bossPen)).append(" Strafpunkte</span></p>")
             .append("<p class=\"tip\">Schwachstelle: Extract Method. Jede Ebene, die eine eigene Aufgabe hat, wird eine Methode mit Namen. Der Rest schrumpft von allein.</p></section>");
        }

        // Monster-Galerie
        h.append("<section class=\"wrap\"><h2>Monster-Galerie</h2><div class=\"grid\">");
        for (Smell s : order) {
            List<Finding> fs = findings.stream().filter(f -> f.smell == s).sorted(Comparator.comparingDouble((Finding f) -> -f.penalty)).collect(Collectors.toList());
            boolean beat = fs.isEmpty();
            h.append("<article class=\"monster").append(beat ? " beaten" : "").append("\"><div class=\"head\"><span class=\"emoji\">").append(s.emoji).append("</span><div><h3>").append(s.monster).append("</h3><p>").append(s.smell).append("</p></div>")
             .append("<span class=\"count\">").append(beat ? "besiegt" : fs.size() + "×").append("</span></div>");
            if (!beat) {
                h.append("<ul>");
                for (Finding f : fs.subList(0, Math.min(3, fs.size()))) h.append("<li><code>").append(esc(f.file.replaceAll(".*/", ""))).append(":").append(f.line).append("</code> ").append(esc(f.text)).append("</li>");
                if (fs.size() > 3) h.append("<li class=\"more\">und ").append(fs.size() - 3).append(" weitere</li>");
                h.append("</ul><p class=\"fix\">").append(esc(s.fix)).append("</p><p class=\"pen\">−").append(fmt(pen.get(s))).append(" Punkte</p>");
            } else {
                h.append("<p class=\"fix ok\">Kein Fund. Sauber.</p>");
            }
            h.append("</article>");
        }
        h.append("</div></section>");

        // Heatmap
        h.append("<section class=\"wrap\"><h2>Karte</h2><p class=\"lead\">Je dunkler die Zeile, desto tiefer verschachtelt. Die Symbole markieren die Funde; der Mauszeiger verrät sie.</p>");
        for (Source src : sources) {
            if (src.test && findings.stream().noneMatch(f -> f.file.equals(src.file))) continue;
            h.append("<details").append(src.test ? "" : " open").append("><summary><code>").append(esc(src.file)).append("</code> · ").append(src.lines.size()).append(" Zeilen · ").append(src.methods.size()).append(" Methoden</summary><pre class=\"map\">");
            Map<Integer, List<Finding>> byLine = findings.stream().filter(f -> f.file.equals(src.file)).collect(Collectors.groupingBy(Finding::line));
            for (int l = 1; l <= src.lines.size(); l++) {
                int d = Math.max(0, src.depthAtLineStart(l) - 1);
                List<Finding> fl = byLine.getOrDefault(l, List.of());
                h.append("<span class=\"ln d").append(Math.min(d, 6)).append(fl.isEmpty() ? "" : " hit").append("\"><span class=\"no\">").append(l).append("</span>").append(esc(src.lines.get(l - 1)));
                for (Finding f : fl) h.append(" <span class=\"mark\" title=\"").append(esc(f.smell.monster + ": " + f.text)).append("\">").append(f.smell.emoji).append("</span>");
                h.append("</span>");
            }
            h.append("</pre></details>");
        }
        h.append("</section>");

        // Quest-Log
        if (!findings.isEmpty()) {
            h.append("<section class=\"wrap\"><h2>Quest-Log</h2><p class=\"lead\">Jede Zeile ein Auftrag. Die Punkte kommen zurück, sobald das Monster weg ist.</p><table><thead><tr><th>Punkte</th><th>Monster</th><th>Wo</th><th>Was</th><th>Refactoring</th></tr></thead><tbody>");
            for (Finding f : findings.stream().sorted(Comparator.comparingDouble((Finding f) -> -f.penalty).thenComparing(Finding::file).thenComparingInt(Finding::line)).collect(Collectors.toList())) {
                h.append("<tr><td class=\"num\">").append(fmt(f.penalty)).append("</td><td>").append(f.smell.emoji).append(" ").append(f.smell.monster).append("</td><td><code>").append(esc(f.file.replaceAll(".*/", ""))).append(":").append(f.line).append("</code></td><td>").append(esc(f.text)).append("</td><td>").append(esc(f.smell.fix.split(":")[0])).append("</td></tr>");
            }
            h.append("</tbody></table></section>");
        }

        h.append("<footer class=\"wrap\"><p>Regeln: Methoden höchstens 20 Zeilen, höchstens drei Ebenen tief, Komplexität höchstens 10, höchstens drei Parameter, Klassen höchstens 200 Zeilen. Zahlen außer 0 und 1 und Texte in Methoden sind magisch. Vier gleiche Zeilen in Folge sind Zwillinge. Tests heißen nach der Regel, die sie prüfen, und prüfen genau eine.</p><p>Erzeugt von <code>tools/CleanCodeReport.java</code>. Der Bericht ist kein Gate: Er zeigt, er entscheidet nicht.</p></footer>");
        h.append("</body></html>");
        return h.toString();
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.GERMANY, "%.1f", d);
    }

    static final String CSS = """
        :root{--bg:#081533;--bg2:#0d2150;--card:rgba(255,255,255,.07);--edge:rgba(255,255,255,.16);--ink:#eef4ff;--soft:#a9bddb;--ice:#9fd8ff;--beam:#3d8dff}
        *{box-sizing:border-box}html{background:var(--bg)}body{margin:0;min-height:100vh;background:var(--bg);background-image:radial-gradient(60% 50% at 15% 0%,rgba(61,141,255,.45),transparent 70%),linear-gradient(180deg,var(--bg2),var(--bg));color:var(--ink);font-family:"Avenir Next","Segoe UI","Helvetica Neue",Arial,sans-serif;line-height:1.45;padding:0 16px 48px}
        .wrap{max-width:1000px;margin:0 auto}header{padding:36px 0 8px}.kicker{margin:0;color:var(--ice);font-size:.85rem;letter-spacing:.04em}h1{margin:2px 0 4px;font-size:2rem}.meta{margin:0;color:var(--soft)}
        h2{font-size:1.35rem;margin:0 0 10px}section{margin-top:28px}.card{padding:20px 22px;border:1px solid var(--edge);border-top-color:rgba(255,255,255,.35);border-radius:18px;background:linear-gradient(160deg,rgba(255,255,255,.12),rgba(255,255,255,.04))}
        .hero{display:flex;flex-wrap:wrap;align-items:center;gap:24px;padding:16px 22px;border:1px solid var(--edge);border-radius:22px;background:linear-gradient(160deg,rgba(255,255,255,.12),rgba(255,255,255,.04))}
        .gauge svg{max-width:100%;height:auto}.score{font-size:64px;font-weight:700;fill:#fff}.of{font-size:14px;fill:var(--soft)}
        .level{flex:1 1 260px}.rank{margin:0;font-size:1.6rem;font-weight:600}.big{font-size:2.2rem;vertical-align:middle}.tag{margin:4px 0 8px;color:var(--soft);font-size:1.05rem}.stat{margin:0;color:var(--ice)}
        .history{margin:10px 0 0;color:var(--soft)}.pill{display:inline-block;margin:2px 4px 0 0;padding:1px 9px;border-radius:999px;background:rgba(255,255,255,.1);font-size:.85rem}.pill.now{background:var(--beam);color:#fff}
        .boss h2{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.where{font-size:.85rem;color:var(--soft);font-weight:400}.hp{height:14px;border-radius:999px;background:rgba(0,0,0,.4);overflow:hidden;margin:8px 0 12px}.hp div{height:100%;background:linear-gradient(90deg,#e5484d,#ff8a80)}
        .stats{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 10px}.stats span{padding:3px 10px;border-radius:999px;background:rgba(3,12,30,.5);border:1px solid rgba(159,216,255,.25);color:var(--ice);font-size:.9rem}.tip{margin:0;color:var(--soft)}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}.monster{padding:14px 16px;border:1px solid var(--edge);border-top-color:rgba(255,255,255,.3);border-radius:16px;background:linear-gradient(180deg,rgba(255,255,255,.1),rgba(255,255,255,.04))}
        .monster.beaten{opacity:.55}.head{display:flex;align-items:center;gap:12px}.emoji{font-size:2rem}.head h3{margin:0;font-size:1.05rem}.head p{margin:0;color:var(--soft);font-size:.9rem}.count{margin-left:auto;padding:2px 10px;border-radius:999px;background:#e5484d;color:#fff;font-weight:600;font-size:.85rem}
        .beaten .count{background:#38c172}.monster ul{margin:10px 0 6px;padding-left:18px;font-size:.92rem}.monster li{margin:2px 0}.more{color:var(--soft);list-style:none;margin-left:-18px}
        .fix{margin:6px 0 0;color:var(--ice);font-size:.9rem}.fix.ok{color:var(--soft)}.pen{margin:4px 0 0;color:#ff8a80;font-size:.85rem}
        .lead{margin:0 0 10px;color:var(--soft)}details{margin:10px 0;border:1px solid var(--edge);border-radius:14px;background:rgba(3,12,30,.5)}summary{padding:10px 14px;cursor:pointer;color:var(--ice)}
        .map{margin:0;padding:8px 0 12px;overflow-x:auto;font:12.5px/1.5 "SF Mono",Menlo,Consolas,"Liberation Mono",monospace;color:#dbe6f7}.ln{display:block;padding:0 14px;white-space:pre}.no{display:inline-block;width:3.2em;color:rgba(169,189,219,.5);text-align:right;margin-right:1.2em;user-select:none}
        .d1{background:rgba(61,141,255,.06)}.d2{background:rgba(61,141,255,.14)}.d3{background:rgba(246,183,60,.16)}.d4{background:rgba(246,183,60,.28)}.d5{background:rgba(229,72,77,.3)}.d6{background:rgba(229,72,77,.45)}
        .hit{outline:1px solid rgba(255,255,255,.18)}.mark{cursor:help}
        table{width:100%;border-collapse:collapse;font-size:.92rem}th,td{text-align:left;padding:7px 8px;border-bottom:1px solid var(--edge);vertical-align:top}th{color:var(--ice);font-weight:600}.num{text-align:right;color:#ff8a80;white-space:nowrap}
        code{font-family:"SF Mono",Menlo,Consolas,monospace;font-size:.9em;color:var(--ice)}.empty{text-align:center}footer{margin-top:36px;color:var(--soft);font-size:.85rem}
        @media (max-width:640px){h1{font-size:1.5rem}.hero{padding:12px}.score{font-size:52px}}
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
        Map<Smell, Double> pen = report.penaltyBySmell();
        report.findings.stream().collect(Collectors.groupingBy(Finding::smell, () -> new EnumMap<>(Smell.class), Collectors.counting()))
            .entrySet().stream().sorted(Comparator.comparingDouble(e -> -pen.get(e.getKey())))
            .forEach(e -> System.out.println("  " + e.getKey().emoji + " " + e.getKey().monster + ": " + e.getValue() + "× (−" + fmt(pen.get(e.getKey())) + ")"));
        System.out.println("Bericht: " + root.relativize(out));
    }
}
