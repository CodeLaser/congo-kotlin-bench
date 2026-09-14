package bench;

import com.intellij.lang.ASTNode;
import com.intellij.lang.LighterASTNode;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.tree.LazyParseableElement;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.diff.FlyweightCapableTreeStructure;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.parsing.AstBridge;
import org.jetbrains.kotlin.parsing.KotlinLightParser;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.parsers.kotlin.KotlinParser;
import org.parsers.kotlin.Node;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parse-and-walk benchmark: CongoCC's Kotlin parser against the Kotlin compiler's parser.
 * <p>
 * Every parser gets the same in-memory text, builds its tree, and the tree is walked depth-first, visiting every
 * node, including every node inside function bodies and lambdas. The node count is the walk's result (it keeps
 * the JIT from discarding the work, and it is checked to be identical on every pass). Comments are visited but
 * never descended into: a KDoc comment is a lazily parsed element in the IntelliJ tree, and CongoCC keeps comments
 * as opaque tokens outside the tree.
 * <p>
 * Parsers:
 * <ul>
 *   <li>{@code congo}: {@code new KotlinParser(name, text).KotlinFile()}, walk {@code Node.get(i)}.</li>
 *   <li>{@code ast}: the compiler's parse, PSI features stripped (see {@link AstBridge}), walk {@code ASTNode}.</li>
 *   <li>{@code lighttree}: {@code KotlinLightParser.buildLightTree} (what kotlinc K2 runs), walk the flyweight tree.</li>
 *   <li>{@code psi}: reference only. {@code KtPsiFactory.createFile} as the IDE / Analysis API gets it: lazy blocks
 *       forced by the walk, PSI wrappers created by it.</li>
 * </ul>
 * Modes: {@code check} (correctness, parity, and writes the accepted-file list), {@code bench} (timing),
 * {@code footprint} (retained heap per character of source).
 */
public final class ParserBench {

    enum Kind {congo, ast, lighttree, psi}

    private static final TokenSet COMMENTS = KtTokens.COMMENTS;
    private static final TokenSet WHITESPACES = KtTokens.WHITESPACES;
    private static KtPsiFactory psiFactory;

    public static void main(String[] args) throws Exception {
        Map<String, String> o = options(args);
        String mode = o.getOrDefault("mode", "bench");
        Path list = Path.of(require(o, "list"));
        List<Path> paths = Files.readAllLines(list).stream().filter(s -> !s.isBlank()).map(Path::of).toList();
        String[] names = new String[paths.size()];
        String[] texts = new String[paths.size()];
        long chars = 0, bytes = 0;
        for (int i = 0; i < paths.size(); i++) {
            byte[] b = Files.readAllBytes(paths.get(i));
            String t = new String(b, StandardCharsets.UTF_8);
            if (!t.isEmpty() && t.charAt(0) == '﻿') t = t.substring(1);
            // kotlinc converts line separators before parsing (the Kotlin lexer treats a lone \r as an error).
            // Every parser gets the same converted text.
            t = t.replace("\r\n", "\n").replace('\r', '\n');
            names[i] = paths.get(i).toString();
            texts[i] = t;
            chars += t.length();
            bytes += b.length;
        }
        long totalChars = chars;
        int stackMb = Integer.parseInt(o.getOrDefault("stack-mb", "64"));
        Kind kind = o.containsKey("parser") ? Kind.valueOf(o.get("parser")) : null;
        if (!"bench".equals(mode) || kind != Kind.congo) setupKotlin();

        switch (mode) {
            case "check" -> runOnBigStack(stackMb, () -> check(names, texts, Path.of(require(o, "accepted"))));
            case "bench" -> bench(kind, names, texts, chars, bytes,
                    Integer.parseInt(o.getOrDefault("threads", "1")),
                    Integer.parseInt(o.getOrDefault("warmup", "10")),
                    Integer.parseInt(o.getOrDefault("iters", "10")), stackMb);
            case "footprint" -> runOnBigStack(stackMb, () -> footprint(kind, names, texts, totalChars));
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        System.exit(0); // the IntelliJ application leaves non-daemon threads behind
    }

    private static void setupKotlin() {
        CompilerConfiguration conf = new CompilerConfiguration();
        conf.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        conf.put(CommonConfigurationKeys.MODULE_NAME, "bench");
        KotlinCoreEnvironment env = KotlinCoreEnvironment.Companion.createForProduction(
                Disposer.newDisposable(), conf, EnvironmentConfigFiles.JVM_CONFIG_FILES);
        psiFactory = new KtPsiFactory(env.getProject(), false);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // parse + walk: the unit of work that is timed

    static long parseAndWalk(Kind kind, String name, String text) {
        return switch (kind) {
            case congo -> {
                KotlinParser parser = new KotlinParser(name, text);
                parser.KotlinFile();
                yield walkCongo(parser.rootNode());
            }
            case ast -> walkAst(AstBridge.parse(text));
            case lighttree -> {
                FlyweightCapableTreeStructure<LighterASTNode> tree =
                        KotlinLightParser.INSTANCE.buildLightTree(text, null, null);
                yield walkLight(tree, tree.getRoot());
            }
            case psi -> walkPsi(psiFactory.createFile(fileName(name), text));
        };
    }

    static Object parseAndRetain(Kind kind, String name, String text) {
        return switch (kind) {
            case congo -> {
                KotlinParser parser = new KotlinParser(name, text);
                parser.KotlinFile();
                Node root = parser.rootNode();
                walkCongo(root);
                yield root;
            }
            case ast -> {
                ASTNode root = AstBridge.parse(text);
                walkAst(root);
                yield root;
            }
            case lighttree -> {
                FlyweightCapableTreeStructure<LighterASTNode> tree =
                        KotlinLightParser.INSTANCE.buildLightTree(text, null, null);
                walkLight(tree, tree.getRoot());
                yield tree;
            }
            case psi -> {
                PsiElement file = psiFactory.createFile(fileName(name), text);
                walkPsi(file);
                yield file;
            }
        };
    }

    static long walkCongo(Node node) {
        long count = 1;
        for (int i = 0, n = node.size(); i < n; i++) count += walkCongo(node.get(i));
        return count;
    }

    static long walkAst(ASTNode node) {
        long count = 1;
        if (COMMENTS.contains(node.getElementType())) return count;
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            count += walkAst(child);
        }
        return count;
    }

    static long walkLight(FlyweightCapableTreeStructure<LighterASTNode> tree, LighterASTNode node) {
        long count = 1;
        if (COMMENTS.contains(node.getTokenType())) return count;
        Ref<LighterASTNode[]> ref = new Ref<>();
        int n = tree.getChildren(node, ref);
        if (n == 0) return count;
        LighterASTNode[] children = ref.get();
        for (int i = 0; i < n; i++) count += walkLight(tree, children[i]);
        tree.disposeChildren(children, n);
        return count;
    }

    static long walkPsi(PsiElement element) {
        long count = 1;
        if (element instanceof PsiComment) return count;
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            count += walkPsi(child);
        }
        return count;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // check: which files both parsers accept, and did both trees reach the same source

    static final class AstStats {
        long nodes, errors, whitespace, comments, significantChars, unparsedLazy, blocks, lambdaBodies, lambdas;
    }

    static final class CongoStats {
        long nodes, tokens, newlines, significantChars, blocks, lambdas;
    }

    static void statsAst(ASTNode node, AstStats s) {
        s.nodes++;
        var type = node.getElementType();
        if (type == TokenType.ERROR_ELEMENT) s.errors++;
        if (type == org.jetbrains.kotlin.KtNodeTypes.BLOCK) {
            // A lambda's statements sit in a BLOCK under FUNCTION_LITERAL; CongoCC has no node for that.
            if (node.getTreeParent() != null
                    && node.getTreeParent().getElementType() == org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL) {
                s.lambdaBodies++;
            } else {
                s.blocks++;
            }
        }
        if (type == org.jetbrains.kotlin.KtNodeTypes.LAMBDA_EXPRESSION) s.lambdas++;
        if (COMMENTS.contains(type)) {
            s.comments++;
            return;
        }
        if (node instanceof LeafElement) {
            if (WHITESPACES.contains(type)) s.whitespace++;
            else s.significantChars += node.getTextLength();
            return;
        }
        // Must be read BEFORE touching the children: getFirstChildNode() would parse a lazy element.
        if (node instanceof LazyParseableElement lazy && !lazy.isParsed()) s.unparsedLazy++;
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            statsAst(child, s);
        }
    }

    static void statsCongo(Node node, CongoStats s) {
        s.nodes++;
        String simple = node.getClass().getSimpleName();
        if (simple.equals("Block")) s.blocks++;
        if (simple.equals("LambdaLiteral")) s.lambdas++;
        if (node instanceof Node.TerminalNode) {
            s.tokens++;
            // Newlines that separate statements stay in CongoCC's tree; Kotlin files them as whitespace.
            if (node instanceof org.parsers.kotlin.ast.Newline) s.newlines++;
            else s.significantChars += node.getEndOffset() - node.getBeginOffset();
            return;
        }
        for (int i = 0, n = node.size(); i < n; i++) statsCongo(node.get(i), s);
    }

    static void check(String[] names, String[] texts, Path acceptedOut) {
        long congoFail = 0, kotlinErr = 0, bothOk = 0, charMismatch = 0, walkMismatch = 0;
        long accChars = 0;
        AstStats astTotal = new AstStats();
        CongoStats congoTotal = new CongoStats();
        long lightTotal = 0, psiTotal = 0, astWalkTotal = 0, congoWalkTotal = 0;
        List<String> accepted = new ArrayList<>();
        List<String> congoOnlyFail = new ArrayList<>(), kotlinOnlyErr = new ArrayList<>(), charDiff = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            String text = texts[i];
            CongoStats cs = new CongoStats();
            String congoError = null;
            try {
                KotlinParser p = new KotlinParser(names[i], text);
                p.KotlinFile();
                statsCongo(p.rootNode(), cs);
            } catch (Throwable t) {
                congoError = t.getClass().getSimpleName() + ": " + firstLine(t.getMessage());
            }
            AstStats as = new AstStats();
            statsAst(AstBridge.parse(text), as);
            boolean congoOk = congoError == null, kotlinOk = as.errors == 0;
            if (!congoOk) congoFail++;
            if (!kotlinOk) kotlinErr++;
            if (!congoOk && kotlinOk) congoOnlyFail.add(names[i] + "\t" + congoError);
            if (congoOk && !kotlinOk) kotlinOnlyErr.add(names[i] + "\t" + as.errors + " error element(s)");
            if (!(congoOk && kotlinOk)) continue;

            bothOk++;
            accepted.add(names[i]);
            accChars += text.length();
            add(astTotal, as);
            add(congoTotal, cs);
            if (as.significantChars != cs.significantChars) {
                charMismatch++;
                charDiff.add(names[i] + "\tkotlin=" + as.significantChars + " congo=" + cs.significantChars);
            }
            // The timed walks must agree with the stats walks.
            long light = parseAndWalk(Kind.lighttree, names[i], text);
            long psi = parseAndWalk(Kind.psi, names[i], text);
            long ast = parseAndWalk(Kind.ast, names[i], text);
            long congo = parseAndWalk(Kind.congo, names[i], text);
            if (ast != as.nodes || light != as.nodes || psi != as.nodes || congo != cs.nodes) {
                walkMismatch++;
                if (walkMismatch <= 5) {
                    System.out.printf("walk mismatch %s: ast=%d(stats %d) light=%d psi=%d congo=%d(stats %d)%n",
                            names[i], ast, as.nodes, light, psi, congo, cs.nodes);
                }
            }
            lightTotal += light;
            psiTotal += psi;
            astWalkTotal += ast;
            congoWalkTotal += congo;
        }
        try {
            Files.write(acceptedOut, accepted);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.printf("files: %d   congo rejects: %d   kotlin error nodes: %d   accepted by both: %d (%,d chars)%n",
                texts.length, congoFail, kotlinErr, bothOk, accChars);
        System.out.printf("congo-only rejects: %d   kotlin-only errors: %d%n", congoOnlyFail.size(), kotlinOnlyErr.size());
        System.out.printf("on accepted files:%n");
        System.out.printf("  significant chars (non-whitespace, non-comment leaf text)  kotlin %,d  congo %,d  files differing: %d%n",
                astTotal.significantChars, congoTotal.significantChars, charMismatch);
        System.out.printf("  kotlin AST nodes %,d  (whitespace leaves %,d, comments %,d)  unparsed lazy elements before walk: %d%n",
                astTotal.nodes, astTotal.whitespace, astTotal.comments, astTotal.unparsedLazy);
        System.out.printf("  kotlin BLOCK (not a lambda body) %,d  LAMBDA_EXPRESSION %,d  (their sum %,d; lambda-body BLOCKs %,d)%n",
                astTotal.blocks, astTotal.lambdas, astTotal.blocks + astTotal.lambdas, astTotal.lambdaBodies);
        System.out.printf("  congo Block %,d  LambdaLiteral %,d  (their sum %,d)%n",
                congoTotal.blocks, congoTotal.lambdas, congoTotal.blocks + congoTotal.lambdas);
        System.out.printf("  congo nodes %,d  (tokens %,d, of which statement-separating newlines %,d)%n",
                congoTotal.nodes, congoTotal.tokens, congoTotal.newlines);
        System.out.printf("  timed-walk node counts: ast %,d  lighttree %,d  psi %,d  congo %,d   files where a walk disagrees: %d%n",
                astWalkTotal, lightTotal, psiTotal, congoWalkTotal, walkMismatch);
        dump(acceptedOut.resolveSibling("check-congo-only-rejects.txt"), "congo-only rejects", congoOnlyFail);
        dump(acceptedOut.resolveSibling("check-kotlin-only-errors.txt"), "kotlin-only errors", kotlinOnlyErr);
        dump(acceptedOut.resolveSibling("check-char-differences.txt"), "significant-char differences", charDiff);
    }

    private static void add(AstStats t, AstStats s) {
        t.nodes += s.nodes;
        t.errors += s.errors;
        t.whitespace += s.whitespace;
        t.comments += s.comments;
        t.significantChars += s.significantChars;
        t.unparsedLazy += s.unparsedLazy;
        t.blocks += s.blocks;
        t.lambdaBodies += s.lambdaBodies;
        t.lambdas += s.lambdas;
    }

    private static void add(CongoStats t, CongoStats s) {
        t.nodes += s.nodes;
        t.tokens += s.tokens;
        t.newlines += s.newlines;
        t.significantChars += s.significantChars;
        t.blocks += s.blocks;
        t.lambdas += s.lambdas;
    }

    private static void dump(Path out, String title, List<String> lines) {
        try {
            Files.write(out, lines);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (lines.isEmpty()) return;
        System.out.println("--- " + title + " (" + lines.size() + ", all in " + out.getFileName() + ")");
        lines.stream().limit(8).forEach(l -> System.out.println("  " + l));
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // bench

    static void bench(Kind kind, String[] names, String[] texts, long chars, long bytes,
                      int threads, int warmup, int iters, int stackMb) throws Exception {
        int n = texts.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        // Largest first, so the last file to finish is a small one (keeps the multi-threaded tail short).
        Arrays.sort(order, Comparator.comparingInt((Integer i) -> texts[i].length()).reversed());

        AtomicInteger next = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(threads + 1), end = new CyclicBarrier(threads + 1);
        long[] nodes = new long[threads];
        boolean[] stop = new boolean[1];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int id = t;
            workers[t] = new Thread(null, () -> {
                try {
                    while (true) {
                        start.await();
                        if (stop[0]) return;
                        long c = 0;
                        int i;
                        while ((i = next.getAndIncrement()) < n) {
                            int f = order[i];
                            try {
                                c += parseAndWalk(kind, names[f], texts[f]);
                            } catch (Throwable e) {
                                failure.compareAndSet(null, new RuntimeException(names[f], e));
                            }
                        }
                        nodes[id] = c;
                        end.await();
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            }, "parse-" + t, stackMb * 1024L * 1024L);
            workers[t].setDaemon(true);
            workers[t].start();
        }

        var threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] tids = Arrays.stream(workers).mapToLong(Thread::threadId).toArray();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();

        double[] ms = new double[iters];
        long[] allocPerPass = new long[iters];
        long[] gcMsPerPass = new long[iters];
        long expectedNodes = -1;
        for (int pass = 0; pass < warmup + iters; pass++) {
            next.set(0);
            long alloc0 = Arrays.stream(threadBean.getThreadAllocatedBytes(tids)).sum();
            long gc0 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
            long t0 = System.nanoTime();
            start.await();
            end.await();
            long t1 = System.nanoTime();
            long alloc1 = Arrays.stream(threadBean.getThreadAllocatedBytes(tids)).sum();
            long gc1 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
            if (failure.get() != null) throw new RuntimeException("parse failed", failure.get());
            long total = Arrays.stream(nodes).sum();
            if (expectedNodes < 0) expectedNodes = total;
            else if (total != expectedNodes) {
                throw new IllegalStateException("node count changed between passes: " + expectedNodes + " -> " + total);
            }
            if (pass >= warmup) {
                int k = pass - warmup;
                ms[k] = (t1 - t0) / 1e6;
                allocPerPass[k] = alloc1 - alloc0;
                gcMsPerPass[k] = gc1 - gc0;
            }
        }
        stop[0] = true;
        start.await();

        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        double median = sorted[iters / 2];
        double alloc = Arrays.stream(allocPerPass).average().orElse(0);
        double gcMs = Arrays.stream(gcMsPerPass).average().orElse(0);
        StringBuilder passes = new StringBuilder();
        // Locale.ROOT: the default locale may use a decimal comma, which is not JSON.
        for (double d : ms) passes.append(passes.isEmpty() ? "" : ",").append(String.format(Locale.ROOT, "%.1f", d));
        System.out.printf(Locale.ROOT, "{\"parser\":\"%s\",\"threads\":%d,\"files\":%d,\"chars\":%d,\"bytes\":%d,\"nodes\":%d,"
                        + "\"median_ms\":%.1f,\"min_ms\":%.1f,\"mb_per_s\":%.2f,\"alloc_bytes_per_char\":%.1f,"
                        + "\"gc_ms_per_pass\":%.1f,\"passes_ms\":[%s]}%n",
                kind, threads, n, chars, bytes, expectedNodes, median, sorted[0],
                bytes / 1e6 / (median / 1e3), alloc / chars, gcMs, passes);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // footprint: heap retained by the trees of the whole corpus

    static volatile Object[] retained;

    static void footprint(Kind kind, String[] names, String[] texts, long chars) {
        for (int i = 0; i < Math.min(200, texts.length); i++) parseAndRetain(kind, names[i], texts[i]); // warm
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long before = usedAfterGc(mem);
        Object[] roots = new Object[texts.length];
        for (int i = 0; i < texts.length; i++) roots[i] = parseAndRetain(kind, names[i], texts[i]);
        retained = roots;
        long after = usedAfterGc(mem);
        System.out.printf(Locale.ROOT, "{\"parser\":\"%s\",\"mode\":\"footprint\",\"files\":%d,\"chars\":%d,\"retained_bytes\":%d,"
                        + "\"retained_bytes_per_char\":%.1f}%n",
                kind, texts.length, chars, after - before, (after - before) / (double) chars);
        retained = null;
    }

    private static long usedAfterGc(MemoryMXBean mem) {
        for (int i = 0; i < 4; i++) System.gc();
        return mem.getHeapMemoryUsage().getUsed();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void runOnBigStack(int stackMb, ThrowingRunnable r) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "main-big-stack", stackMb * 1024L * 1024L);
        t.start();
        t.join();
        if (failure.get() != null) throw new RuntimeException(failure.get());
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("expected --option, got " + args[i]);
            String key = args[i].substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) o.put(key.substring(0, eq), key.substring(eq + 1));
            else o.put(key, args[++i]);
        }
        return o;
    }

    private static String require(Map<String, String> o, String key) {
        String v = o.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
