package org.blockgen;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.ast.expr.AssignExpr;

public class Constant {
    public final static String TARGET_STMT_BEFORE = "target-fragment-before";
    public final static String TARGET_STMT_AFTER = "target-fragment-after";
    public final static String TARGET_STMT_RETURN = "target-fragment-return";
    public final static String TARGET_STMT_FLOW = "target-fragment-flow";
    public final static String TARGET_STMT_THROW = "target-fragment-throw";
    public final static String TARGET_STMT_START = "target-fragment-start";
    public final static String TARGET_STMT_END = "target-fragment-end";
    public final static String TARGET_METHOD_BEFORE = "target-method-before";
    public final static String TARGET_STMT_EXECUTED = "target-fragment-executed";
    public final static String TARGET_STMT_NOT_EXECUTED = "target-fragment-not-executed";
    public final static String TARGET_STMT_IF_START = "target-fragment-if-start";
    public final static String CHECK_COVERAGE = "check-coverage"; // check coverage rate at the end of the block
    public final static String MOCKING = "check-mocking";
    public final static String LOG_CLASS_NAME = "InstrumentHelper";
    public final static String LOG_CLASS_IMPORT = "org.blockgen.helpers.InstrumentHelper";
    public final static String COUNTER_CLASS_NAME = "org.blockgen.CounterHelper";
    public final static String LOG_SEPARATOR = ";";
    public final static List<String> STRING_MANIPULATION = Arrays.asList("split", "substring", "indexOf", "format",
            "replace");
    public final static List<String> REGEX = Arrays.asList("matches", "find", "group");
    public final static List<String> STREAM = Arrays.asList("stream");
    public final static List<String> WHITELISTED_NON_MOCK_CLASSES = Arrays.asList("System");
    public final static List<String> PRIMITIVE_TYPES = Arrays.asList("int", "long", "double", "float", "boolean", "char",
            "byte", "short",
            "String", "int[]", "long[]", "double[]", "float[]", "boolean[]", "char[]", "byte[]", "short[]",
            "String[]");
    public final static List<AssignExpr.Operator> COMPOUND_ASSIGN_OPERATORS = Arrays.asList(AssignExpr.Operator.PLUS,
            AssignExpr.Operator.MINUS,
            AssignExpr.Operator.MULTIPLY, AssignExpr.Operator.DIVIDE, AssignExpr.Operator.BINARY_AND,
            AssignExpr.Operator.BINARY_OR, AssignExpr.Operator.XOR, AssignExpr.Operator.REMAINDER,
            AssignExpr.Operator.LEFT_SHIFT, AssignExpr.Operator.SIGNED_RIGHT_SHIFT,
            AssignExpr.Operator.UNSIGNED_RIGHT_SHIFT);
    public static String logFilePath;
    public static String r0TestPath;
    public static String r1TestPath;
    public static String blockTestName = ""; // default block test name
    public static boolean exceptionTesting = true;
    public static boolean assertStatic = false;
    public static Set<String> removeFQNType = new HashSet<>();

    public static boolean useFQN = false;
    public final static String CONFIGURE_FILE_NAME = ".blocktestsrc";
    public final static String BLOCK_GEN_DIR_NAME = ".blocktests";
    public final static String SERIALIZED_DATA_DIR_NAME = "serialized-data";
    public final static String BLOCK_TESTS_COUNTER_FILE_NAME = "block-tests-counter.txt";
    public final static String UNCOVERED_LINES_FILE_NAME = "uncovered-lines.txt";
    public final static String TESTS_TO_COVERED_LINES_FILE_NAME = "test-to-covered-lines.txt";
    public final static String UNIQUE_BLOCK_TESTS_COUNTER_FILE_NAME = "unique-block-tests-counter.txt";
    public final static String TARGET_STMTS_HIT_COUNTER_FILE_NAME = "all-target-stmts-hit-counter.txt";
    public final static String COVERAGE_FILE_NAME = "coverage.txt";
    public final static String SERIALIZED_DATA_TO_PATH_FILE_NAME = "serialized-data-to-path.txt";
    public final static int MAX_BLOCK_TESTS_PER_STMT = 100;
    public final static int MAX_BLOCK_TEST_LENGTH = 10000;

    public final static String DECLARE_NAME = "blocktest";
    public final static String GIVEN = "given";
    public final static String END = "end";
    public final static String CHECK_EQ = "checkEq";
    public final static String CHECK_RETURN_EQ = "checkReturnEq";
    public final static String MOCK = "mock";
    public final static String EXPECT = "expect";
    public final static String CHECK_FLOW = "checkControlFlow";
    public final static String CHECK_TRUE = "checkTrue";
    public final static String CHECK_FALSE = "checkFalse";

    public final static String EXCEPTION_VARIABLE = "BLOCKGEN_EXCEPTION";

    public final static List<String> SKIPS = Arrays.asList("-Dmaven.nbm.verify=skip", "-Dcheckstyle.skip", "-Drat.skip", "-Denforcer.skip", "-Danimal.sniffer.skip",
            "-Dmaven.javadoc.skip", "-Dfindbugs.skip", "-Dwarbucks.skip", "-Dmodernizer.skip", "-Dimpsort.skip",
            "-Dpmd.skip", "-Dxjc.skip", "-Dinvoker.skip", "-DskipDocs", "-DskipITs", "-Dmaven.plugin.skip", "-Dlombok.delombok.skip",
            "-Dlicense.skipUpdateLicense", "-Dremoteresources.skip", "-Dlicense.skip", "-Dgpg.skip",  "-Dspotbugs.skip", "-Dmaven.antrun.skip", "-Dair.check.skip-all", "-Dfmt.skip", "-Djacoco.skip");
    public final static List<String> SKIPS_WITH_JACOCO = Arrays.asList("-Dmaven.nbm.verify=skip", "-Dcheckstyle.skip", "-Drat.skip", "-Denforcer.skip", "-Danimal.sniffer.skip",
            "-Dmaven.javadoc.skip", "-Dfindbugs.skip", "-Dwarbucks.skip", "-Dmodernizer.skip", "-Dimpsort.skip",
            "-Dpmd.skip", "-Dxjc.skip", "-Dinvoker.skip", "-DskipDocs", "-DskipITs", "-Dmaven.plugin.skip", "-Dlombok.delombok.skip",
            "-Dlicense.skipUpdateLicense", "-Dremoteresources.skip", "-Dlicense.skip", "-Dgpg.skip",  "-Dspotbugs.skip", "-Dmaven.antrun.skip", "-Dair.check.skip-all", "-Dfmt.skip");

    // Jars
    public static final String EVOSUITE_JAR = "evosuite-master-1.2.1-SNAPSHOT.jar";
    public static final String JACOCO_EXT_JAR = "jacoco-extension-1.0-SNAPSHOT.jar";
    public static final String JUNIT_STANDALONE_JAR = "junit-platform-console-standalone-1.12.0.jar";
    public static final String JACOCO_AGENT_JAR = "org.jacoco.agent-0.8.12-runtime.jar";
    public static final String RANDOOP_JAR = "randoop-all-4.3.3.jar";
    public static final String JACOCO_CLI_JAR = "jacococli-0.8.12.jar";
    // Test-generation
    public static final int DEFAULT_SEED = 42;
    public static final int EVOSUITE_TIMEOUT_S = 120;
    public static int EVOSUITE_SINGLE_TEST_TIMEOUT_MS = 4000000;
    public static final int RANDOOP_TIMEOUT_S = 64800;
    public static final int RANDOOP_SINGLE_CLASS_TIMEOUT_S = 60;
    public static final int MAX_STRING_SIZE = 25;
    public static int THREAD_POOL_SIZE = 16;

    public enum TESTING_FRAMEWORK {
        JUNIT4, JUNIT5, TESTNG
    }
}
