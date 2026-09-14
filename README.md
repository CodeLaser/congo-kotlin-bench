# CongoCC Kotlin parser vs the Kotlin compiler's parser

Measures **tree creation + full navigation**: each parser gets the same in-memory text, builds its tree, and the
tree is walked depth-first visiting every node, including everything inside function bodies and lambdas.

## Parsers

| name | what | walk |
|---|---|---|
| `congo` | `new KotlinParser(name, text).KotlinFile()` (congo-grammars `kotlin/`, default settings) | `Node.size()` / `Node.get(i)` |
| `ast` | **headline comparison.** The compiler's parser (`KotlinParsing` + JFlex `KotlinLexer` via `PsiBuilder`) with the PSI features stripped, see below | `ASTNode.getFirstChildNode()` / `getTreeNext()` |
| `lighttree` | `KotlinLightParser.buildLightTree`, the parse `kotlinc` (K2) runs (kotlinc also walks the result once to report errors). Flyweight tree, no node objects | `FlyweightCapableTreeStructure.getChildren` |
| `psi` | reference: `KtPsiFactory.createFile`, as the IDE / Analysis API gets it | `PsiElement.getFirstChild()` / `getNextSibling()` |

Kotlin compiler 2.4.0, run on JDK 21.

### PSI features disabled in `ast` (see `AstBridge.java`)

`ast` is `buildLightTree` with one call changed: `getTreeBuilt()` instead of `getLightTree()`.

- **No PsiFile, FileViewProvider, LightVirtualFile or Document**: the AST is built straight from `PsiBuilder`.
- **No PSI wrapper layer** (`KtNamedFunction`, ...): the walk is over `ASTNode`, which never creates them.
- **No lazy parsing**: `createForTopLevelNonLazy`, so function bodies and lambdas are parsed in the same pass. (The
  PSI path makes `BLOCK` and `LAMBDA_EXPRESSION` lazily reparseable elements, which re-lexes their text when first
  touched.) Check mode verifies that zero lazy elements are left unparsed.
- **KDoc is not parsed**: comments are visited but never descended into, in every walk (a KDoc is itself a lazily
  parsed element; CongoCC keeps comments as opaque tokens outside the tree).

What could not be disabled, and so remains in `ast`'s numbers:

- whitespace leaves: the IntelliJ AST is lossless (about 2.0 M of its 12.4 M nodes on this corpus); CongoCC skips
  whitespace;
- `PsiBuilder`'s marker bookkeeping and error-recovery machinery;
- the tree shape: CongoCC's smart node creation omits single-child nodes (7.4 M nodes vs 12.4 M).

## Methodology

- Files are read into memory up front (UTF-8, BOM stripped, line separators converted to `\n` as kotlinc does).
  Every parser gets the same `String`.
- Each `(parser, threads)` runs in its **own JVM** (`-Xms8g -Xmx8g -XX:+AlwaysPreTouch`, default G1), repeated in
  3 interleaved forks. Single-thread: 5 warm-up passes + 10 measured; multi-thread: 10 + 20. A pass parses and walks
  the whole corpus. Reported: median pass, median over forks.
- Threading: the same fixed pool of N platform threads (64 MB stacks) for every parser; files are handed out from
  a shared counter, largest first.
- The walk's node count must be identical on every pass (so the JIT cannot drop the work, and a thread-safety
  problem would show up as a changed count).
- `alloc B/char`: bytes allocated by the worker threads per character of source.
- `footprint` mode: heap retained by the trees of the whole corpus, measured after GC.

## Corpus

Only files **both** parsers accept go into the timed runs (`corpus-accepted.txt`): CongoCC must not throw, and the
Kotlin tree must contain no error elements. `check` mode writes the list and verifies parity on it:

- the amount of non-whitespace, non-comment source text reachable from each tree is compared per file. This is a
  character count, so it does not see differences in tokenization: see string templates below. 24 files differ
  (`check-char-differences.txt`), all explained under "Observations" below;
- the three Kotlin walks must give identical node counts.

| project | revision | files accepted | bytes |
|---|---|---|---|
| ktor | 88c0026 | 2375 | 10.1 M |
| Exposed | 2155404 | 790 | 5.4 M |
| detekt | 6fa04a63c8 | 1085 | 4.4 M |
| kotlinx.coroutines | 7e8b5a4 | 1018 | 3.8 M |
| kotlinx.serialization | 397bb56 | 676 | 2.5 M |
| arrow | 6ab9df9 | 722 | 2.4 M |
| okio | e8dad1a | 311 | 1.7 M |
| coil | ec724ef5 | 439 | 1.3 M |
| kotlinpoet | b7f6d40 | 125 | 1.3 M |
| congo-grammars `kotlin/testfiles` | f1443fe | 90 | 1.2 M |
| spring-framework (Kotlin sources) | 99b991b6f3 | 382 | 1.1 M |

8013 files, 35.2 MB. `.kt` only (no `.kts`), `build/` directories excluded, identical files deduplicated.
The revisions are pinned in `scripts/fetch-corpus.sh`; `corpus-all.txt` and `corpus-accepted.txt` are committed.

## Reproduce

Needs a JDK 21 (`JAVA_HOME`), `ant`, and `git`.

```bash
scripts/fetch-corpus.sh      # the .kt files of each project at its pinned revision, into corpus/ (~30 s)
scripts/build-congo.sh       # congo-grammars at a pinned revision -> libs/congo-kotlin.jar
scripts/make-corpus-list.sh  # -> corpus-all.txt
./gradlew installDist

# correctness and parity; writes corpus-accepted.txt, check-*.txt
java -Xmx8g -cp 'build/install/bench/lib/*' bench.ParserBench --mode check \
     --list corpus-all.txt --accepted corpus-accepted.txt

# the benchmark (about 15 minutes; refuses to start while another process uses half a core or more), then the table
OUT=results/$(date +%F)-$(hostname -s).jsonl ./run.sh
./summarize.py results/<that file>.jsonl
```

`run.sh` uses `$JAVA`, else `$JAVA_HOME/bin/java`, else on macOS the JDK 21 that `/usr/libexec/java_home -v 21`
finds. It also takes `FORKS`, `THREADS`, `PARSERS`, and `BUSY_OK=1` to skip the idle check.

On macOS, `build-congo.sh` builds on a case-sensitive disk image (`.congo/`): the generated `ast/` directory holds both
`Annotation.java` and `ANNOTATION.java` (and `Operator`/`OPERATOR`), which overwrite each other on the default
case-insensitive filesystem.

## Results

`results/2026-09-14-m2ultra-intellij-busy.jsonl`: Apple M2 Ultra (16 performance + 8 efficiency cores), JDK 21.0.12.
**Not clean**: IntelliJ was using a full core throughout, so forks spread 5–18% at 16 threads. The ratios held on
every fork: paired by fork, ast stayed 3.5–4.6× ahead of congo at every thread count.

`results/2026-09-14-m2ultra-intellij-busy.html` shows the same run as charts, including every measured pass. Download
it and open it in a browser; the data is embedded.

| parser | 1 thread | 16 threads | 24 threads | allocated / char | retained heap / char |
|---|---|---|---|---|---|
| congo | 4.1 MB/s | 54 MB/s | 70 MB/s | 380 B | 20.2 B |
| ast | 15.5 MB/s (3.8×) | 223 MB/s (4.1×) | 295 MB/s (4.2×) | 43 B | 18.3 B |
| lighttree | 18.5 MB/s (4.5×) | 291 MB/s (5.4×) | 346 MB/s (4.9×) | 40 B | 13.3 B |
| psi | 10.8 MB/s (2.6×) | 166 MB/s (3.1×) | 186 MB/s (2.7×) | 68 B | 25.7 B |

## Observations on the CongoCC grammar

- **Profile** (JFR, single thread): about 24% of CPU is in the `TOKEN_HOOK` of `Kotlin.ccc`, where every newline
  token calls `canIgnoreNewlines()`, which walks the whole parser call stack comparing production names. Keeping that
  stack costs a `NonTerminalCall` per production entry: 40% of all allocation. `BitSet.nextSetBit` in
  `TokenSource.nextCachedToken` is another 14%, and soft-keyword `String.contentEquals` in `typeMatches`/`hasMatch`
  7%. The Kotlin compiler answers "is this newline significant here?" in constant time, with a stack of flags in
  `SemanticWhitespaceAwarePsiBuilder`.
- **Conformance**: 56 files Kotlin accepts are rejected (`check-congo-only-rejects.txt`, with positions). 33 use newer
  syntax: context parameters `context(...)` (20), `_` as a type argument (9), multi-dollar strings `$$"…"` (2),
  `_` as a loop variable (1), the `@all:` use-site target (1). 7 end a statement with `;` right before a newline and
  `}` (or open a class body with `{;`). The rest are one-offs.
- **Tree shape**: braced bodies of `when` entries, `if`/`else` and `for` parse as `LambdaLiteral` (about 15,400 of
  them), where the Kotlin grammar has `controlStructureBody: block | statement`.
- **String templates in raw strings**: inside `"""…"""`, `${x}` lexes as a `$` token followed by `{x}` as string
  text, so the expression is never parsed: `val a = """${x}"""` gives `[$] [{x}]`, where `"${x}"` correctly gives
  `[${] [x] [}]`. On the accepted corpus that is about 620 of the 5,469 `${…}` templates Kotlin parses, in 114 files.
  CongoCC accepts these files, and the parity check only notices when the expression contains whitespace. The
  effect on the timings is negligible (it saves CongoCC a little work).
- **Tabs**: with `TAB_SIZE=4`, CongoCC expands tabs to spaces in its buffer, so token text and offsets past a tab no
  longer match the source.
- **The 24 files in `check-char-differences.txt`**: in 7, CongoCC counts more characters only because of tab
  expansion inside string literals (705 characters in each of the two spring-framework JSON tests, 3 per tab). 16 contain
  unparsed `${…}` templates in raw strings, whose whitespace CongoCC counts as string text. The last one
  (ktor's `YamlConfig.kt`) has a multi-dollar string `$$"${"`, tokenized differently.
