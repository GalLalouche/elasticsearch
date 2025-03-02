/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

import org.elasticsearch.core.Strings;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.CsvTests;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.analysis.Analyzer;
import org.elasticsearch.xpack.esql.analysis.AnalyzerContext;
import org.elasticsearch.xpack.esql.analysis.EnrichResolution;
import org.elasticsearch.xpack.esql.core.util.StringUtils;
import org.elasticsearch.xpack.esql.expression.function.EsqlFunctionRegistry;
import org.elasticsearch.xpack.esql.parser.EsqlParser;
import org.elasticsearch.xpack.esql.plan.QueryPlan;
import org.elasticsearch.xpack.esql.planner.mapper.Mapper;
import org.fusesource.jansi.Ansi;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.TEST_VERIFIER;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.unboundLogicalOptimizerContext;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.withDefaultLimitWarning;
import static org.elasticsearch.xpack.esql.analysis.AnalyzerTestUtils.defaultLookupResolution;

public class GoldenTests extends ESTestCase {
    private static final Logger logger = LogManager.getLogger(GoldenTests.class);

    public void testBasic() throws Exception {
        doTest("FROM employees");
    }

    public void testEmptyProjections() throws Exception {
        doTest("""
            from employees
            | keep salary
            | drop salary
            """);
    }

    public void testEmptyProjectionInStat() throws Exception {
        doTest("""
            from employees
            | stats c = count(salary)
            | drop c
            """);
    }

    public void testEmptyProjectInStatWithEval() throws Exception {
        doTest("""
            from employees
            | where languages > 1
            | stats c = count(salary)
            | eval x = 1, c2 = c*2
            | drop c, c2
            """);
    }

    public void testEmptyProjectInStatWithGroupAndEval() throws Exception {
        doTest("""
            from employees
            | where languages > 1
            | stats c = count(salary) by emp_no
            | eval x = 1, c2 = c*2
            | drop c, emp_no, c2
            """);
    }

    public void testCombineProjectionsWithEvalAndDrop() throws Exception {
        doTest("""
            from employees
            | eval f1 = languages, f2 = f1
            | keep f2
            """);
    }

    public void doTest(String esqlQuery) throws IOException {
        new Test(extractTestName(), esqlQuery, EnumSet.allOf(Stage.class)).doTest();
    }

    public void doTest(String esqlQuery, EnumSet<Stage> stages) throws Exception {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("At least one stage must be specified");
        }
        new Test(extractTestName(), esqlQuery, stages).doTest();
    }

    private static String extractTestName() {
        return StringUtils.camelCaseToUnderscore(Thread.currentThread().getStackTrace()[3].getMethodName().substring(4)).toLowerCase();
    }

    private record Test(String testName, String esqlQuery, EnumSet<Stage> stages) {

        public void doTest() throws IOException {
            var results = doTests();
            var failedStages = results.stream().filter(e -> e.v2() == TestResult.FAILURE).map(e -> e.v1()).toList();
            if (failedStages.isEmpty() == false) {
                fail(Strings.format("Output for test '%s' does not match for stages '%s'", testName, failedStages));
            }
        }

        public List<Tuple<Stage, TestResult>> doTests() throws IOException {
            var parsedStatement = new EsqlParser().createStatement(esqlQuery);
            var analyzer = new Analyzer(
                new AnalyzerContext(
                    EsqlTestUtils.TEST_CFG,
                    new EsqlFunctionRegistry(),
                    CsvTests.loadIndexResolution(CsvTests.testDatasets(parsedStatement)),
                    defaultLookupResolution(),
                    new EnrichResolution()
                ),
                TEST_VERIFIER
            );
            var result = new ArrayList<Tuple<Stage, TestResult>>();
            var analyzed = analyzer.analyze(parsedStatement);
            if (stages.contains(Stage.ANALYZER)) {
                result.add(Tuple.tuple(Stage.ANALYZER, verifyOrWrite(analyzed, Stage.ANALYZER)));
            }
            if (stages.contains(Stage.LOGICAL) == false && stages.contains(Stage.PHYSICAL) == false) {
                return result;
            }
            var logicallyOptimized = new LogicalPlanOptimizer(unboundLogicalOptimizerContext()).optimize(analyzed);
            if (stages.contains(Stage.LOGICAL)) {
                result.add(Tuple.tuple(Stage.LOGICAL, verifyOrWrite(logicallyOptimized, Stage.LOGICAL)));
            }
            if (stages.contains(Stage.PHYSICAL) == false) {
                return result;
            }
            var physicalPlanOptimizer = new PhysicalPlanOptimizer(new PhysicalOptimizerContext(null));
            result.add(
                Tuple.tuple(
                    Stage.PHYSICAL,
                    verifyOrWrite(physicalPlanOptimizer.optimize(new Mapper().map(logicallyOptimized)), Stage.PHYSICAL)
                )
            );
            return result;
        }

        enum TestResult {
            SUCCESS,
            FAILURE,
            CREATED
        }

        private <T extends QueryPlan<T>> TestResult verifyOrWrite(T plan, Stage stage) throws IOException {
            var outputFile = outputFile(stage);
            GoldenMode goldenMode = getGoldenMode();
            switch (goldenMode) {
                case BULLDOZE -> {
                    logger.info("Bulldozing file {}", outputFile);
                    return createNewOutput(plan, stage);
                }
                case VERIFY -> {
                    if (outputFile.toFile().exists() && goldenMode == GoldenMode.VERIFY) {
                        return verifyExisting(plan, stage);
                    } else {
                        logger.debug("No output exists for file {}, writing new output", outputFile);
                        return createNewOutput(plan, stage);
                    }
                }
                default -> throw new AssertionError("Unknown golden mode: " + goldenMode);
            }
        }

        private TestResult createNewOutput(QueryPlan<?> plan, Stage stage) throws IOException {
            Files.createDirectories(outputFile(stage).getParent());
            Files.write(outputFile(stage), plan.goldenTestToString().getBytes());
            return TestResult.CREATED;
        }

        private TestResult verifyExisting(QueryPlan<?> plan, Stage stage) throws IOException {
            Path output = outputFile(stage);
            var read = Files.readString(output);
            String testString = normalize(plan.goldenTestToString());
            if (normalize(testString).equals(normalize(read))) {
                return TestResult.SUCCESS;
            }
            List<String> actualLines = normalize(testString.lines());
            List<String> expectedLines = normalize(read.lines());
            printUnifiedDiff(stage, actualLines, expectedLines);
            Path path = output.resolveSibling(output.getFileName().toString().replaceAll(".expected", "_diff.md"));
            logger.info("Creating markdown file at " + path.toAbsolutePath());
            Files.write(path, createMarkdownDiff(actualLines, expectedLines).getBytes());
            Path actualFile = output.resolveSibling(output.getFileName().toString().replaceAll("expected", "actual"));
            logger.info("Creating actual file at " + actualFile.toAbsolutePath());
            Files.write(actualFile, actualLines);
            return TestResult.FAILURE;
        }

        private Path outputFile(Stage stage) {
            return Path.of(
                BASE_FILE.getAbsolutePath(),
                "golden_tests",
                testName,
                Strings.format("%s_%s.expected", testName, stage.name().toLowerCase())
            );
        }
    }

    @Override
    protected List<String> filteredWarnings() {
        return withDefaultLimitWarning(super.filteredWarnings());
    }

    private static final File BASE_FILE = new File(
        URI.create(
            new File(GoldenTests.class.getResource(".").getFile()).toURI()
                .toString()
                .replaceAll("build/classes/java/test", "src/test/resources")
        )
    );

    private static GoldenMode getGoldenMode() {
        var property = System.getProperty("golden.mode");
        if (property == null) {
            return GoldenMode.VERIFY;
        }
        return GoldenMode.valueOf(property.toUpperCase());
    }

    private enum GoldenMode {
        BULLDOZE,
        VERIFY
    }

    private enum Stage {
        ANALYZER,
        LOGICAL,
        PHYSICAL;
    }

    private static void printUnifiedDiff(Stage stage, List<String> actual, List<String> expected) {
        Patch<String> patch = DiffUtils.diff(actual, expected);

        logger.error(Ansi.ansi().fg(Ansi.Color.YELLOW).a("For stage '" + stage + "'").reset().toString());
        logger.error(Ansi.ansi().fg(Ansi.Color.RED).a("+++ Actual").reset().toString());
        logger.error(Ansi.ansi().fg(Ansi.Color.GREEN).a("--- Expected").reset().toString());

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int origStart = delta.getSource().getPosition() + 1;
            int revisedStart = delta.getTarget().getPosition() + 1;

            logger.error(
                Ansi.ansi()
                    .fg(Ansi.Color.CYAN)
                    .a("@@ -" + origStart + "," + delta.getSource().size() + " +" + revisedStart + "," + delta.getTarget().size() + " @@")
                    .reset()
                    .toString()
            );

            for (String line : delta.getSource().getLines()) {
                logger.error(Ansi.ansi().fg(Ansi.Color.RED).a("+ " + line).reset().toString());
            }

            for (String line : delta.getTarget().getLines()) {
                logger.error(Ansi.ansi().fg(Ansi.Color.GREEN).a("- " + line).reset().toString());
            }
        }
    }

    private static String createMarkdownDiff(List<String> actual, List<String> expected) {
        DiffRowGenerator generator = DiffRowGenerator.create()
            .showInlineDiffs(true)
            .mergeOriginalRevised(true)
            .inlineDiffByWord(true)
            .ignoreWhiteSpaces(true)
            .oldTag(f -> "~")
            .newTag(f -> "**")
            .build();
        var sb = new StringBuilder();
        sb.append("|Line #|Expected|Actual|\n");
        sb.append("|------|------|--------|\n");
        List<DiffRow> diffRows = generator.generateDiffRows(actual, expected);
        for (int i = 0; i < diffRows.size(); i++) {
            var row = diffRows.get(i);
            var line = switch (row.getTag()) {
                case INSERT, DELETE, CHANGE -> "|%d|<span style='color:green'>%s</span>|<span style='color:red'>%s</span>|";
                case EQUAL -> "|%d|%s|%s|";
            };
            sb.append(Strings.format(line + "\n", i, row.getNewLine(), row.getOldLine()));
        }
        return sb.toString();
    }

    private static String normalize(String s) {
        return s.lines().map(l -> l.strip()).collect(Collectors.joining("\n"));
    }

    private static List<String> normalize(Stream<String> s) {
        return s.map(l -> l.strip()).toList();
    }
}
