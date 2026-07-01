package org.blockgen.helpers;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.basic.URLConverter;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import com.thoughtworks.xstream.converters.reflection.SerializableConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import com.thoughtworks.xstream.security.AnyTypePermission;
import org.blockgen.BlockTest;
import org.blockgen.Constant;
import org.blockgen.Utils;
import org.blocktest.Assertion;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


public class InstrumentHelper {

    static boolean init = false; // init only once
    static Map<String, Integer> srcLineNoCounter = new HashMap<String, Integer>(); // srcPath + lineNo -> counter
    static List<BlockTest> blockTests = new ArrayList<BlockTest>();
    static Map<String, Integer> allSrcLineNoCounter = new HashMap<String, Integer>();
    static List<BlockTest> allBlockTests = new ArrayList<BlockTest>();
    static Set<String> blocktestStrings = new HashSet<>();
    static Map<String, CoverageBuilder> classLineNoToCoverageBefore = new HashMap<String, CoverageBuilder>();
    static Map<String, CoverageBuilder> classLineNoToCoverageAfter = new HashMap<String, CoverageBuilder>();
    static Map<String, Set<String>> classLineNoToCovered = new HashMap<String, Set<String>>(); // class + lineNo ->
    // covered lineNos
    static String blockGenDir;
    static String serializedDataDir;
    static Map<String, String> serializedDataToFilePathMap = new HashMap<String, String>(); // serialized data hash code
    // -> file path
    static Map<String, BlockTest> targetStmtLineNoToCurBlockTestMap = new HashMap<String, BlockTest>();
    static int totalBlockTests = 0;
    // physical [startLine, endLine] pairs for each fragment in the single instrumented source file, derived during init()
    static List<int[]> fragmentRanges = new ArrayList<>();
    // lines that have not been covered in any JVM run yet; persisted across runs
    static Set<Integer> uncoveredLines = new HashSet<>();
    /*
        This method is called from generated code
     */
    public static void logVariableAndGenerateTest(String info, String logPath, String r0TestPath, String r1TestPath, String srcPath, int targetNumStart, int targetNumEnd, Object variable, String variableName, Class clazz, String classesDirectory, String message) {
        if (!init) {
            init = true;
            Constant.logFilePath = logPath;
            Constant.r0TestPath = r0TestPath;
            Constant.r1TestPath = r1TestPath;
            init(srcPath, clazz);
        }
        String targetStmtNum = targetNumStart + "-" + targetNumEnd;
        String key = srcPath + ":" + targetStmtNum;
        try {
            int counter = srcLineNoCounter.getOrDefault(key, 0);
            int allCounter = allSrcLineNoCounter.getOrDefault(key, 0);
            if (counter > Constant.MAX_BLOCK_TESTS_PER_STMT || allCounter > Constant.MAX_BLOCK_TESTS_PER_STMT) {
                return;
            }

            String type = "";
            if (variableName != null && variableName.contains("@TYPE@")) {
                type = variableName.split("@TYPE@")[1];
                variableName = variableName.split("@TYPE@")[0];
            }

            if (info.equals(Constant.TARGET_STMT_START)) {
                // initialize a new block test
                System.out.println("initialize a new block test");
                BlockTest currentBlockTest = new BlockTest();
                currentBlockTest.srcPath = srcPath;
                currentBlockTest.clazzName = clazz.getName();
                currentBlockTest.targetStmtLineNo = targetStmtNum;

                if (message.contains("end-return")) {
                    currentBlockTest.end = Constant.END + "(FIRST_STATEMENT, 9999999)";
                }

                targetStmtLineNoToCurBlockTestMap.put(targetStmtNum, currentBlockTest);
                addCoverageRateBefore(targetStmtNum, clazz.getName(), classesDirectory);
                saveFiles();
            } else if (info.equals(Constant.TARGET_STMT_BEFORE)) {
                // add given statement
                System.out.println("add given statement");
                String varType = parseVarType(variable);
                String varValue = parseValue(varType, variable);
                System.out.println(variableName + ": " + varType + " = " + varValue);
                String givenStmt;

                String API = Constant.GIVEN;
                if (variableName.endsWith(")")) {
                    // it is actually a method replacement, so we need to use MOCK
                    // should not match `children.oGet(i).flags`
                    API = Constant.MOCK;
                }

                if (varValue.startsWith("BLOCKGEN@@") || varValue.startsWith("BLOCKGEN##") || varValue.startsWith("BLOCKGEN!!") || varValue.startsWith("BLOCKGEN**")) {
                    // Need type info when it is a xml file
                    varValue = varValue.replace("BLOCKGEN@@", "").replace("BLOCKGEN##", "").replace("BLOCKGEN!!", "").replace("BLOCKGEN**", "");
                    if (varValue.contains(".txt")) {
                        givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ")";
                    } else {
                        if (type.isEmpty())
                            givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ",\"" + getRuntimeTypeString(variable) + "\")";
                        else
                            givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ",\"" + type + "\")";
                    }
                } else {
                    if (type.isEmpty())
                        givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ")";
                    else
                        givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ",\"" + type + "\")";
                    String varTypeStr = getRuntimeTypeString(variable);
                    if (varTypeStr.equals("java.lang.Double") || varTypeStr.equals("java.lang.Float")) {
                        if (!varValue.contains("Infinity")) {
                            if (varValue.equals("NaN")) {
                                givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + varTypeStr + ".NaN)";
                            }
                        } else {
                            String infType = getInfType(varValue, varTypeStr);
                            givenStmt = API + "(\"" + Utils.escapeString(variableName) + "\"," + infType + ")";
                        }
                    }
                }

                if (targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtNum)) {
                    BlockTest currentBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtNum);
                    currentBlockTest.givens.add(givenStmt);
                }
                saveFiles();
            } else if (info.equals(Constant.TARGET_STMT_AFTER) || info.equals(Constant.TARGET_STMT_RETURN)) {
                // add assertion statement for statement
                System.out.println("add assertion statement for statement");
                String varType = parseVarType(variable);
                String varValue = parseValue(varType, variable);

                System.out.println(variableName + ": " + varType + " = " + varValue);

                // Post-process some comment variable
                if (variable instanceof StringBuilder) {
                    varType = "String";
                    variableName = variableName + ".toString()";
                    varValue = parseValue(varType, ((StringBuilder) variable).toString());
                    System.out.println("variable is a StringBuilder (variableName: " + variableName + ") (varValue: " + varValue + ")");
                } else if (variable instanceof StringBuffer) {
                    varType = "String";
                    variableName = variableName + ".toString()";
                    varValue = parseValue(varType, ((StringBuffer) variable).toString());
                    System.out.println("variable is a StringBuffer (variableName: " + variableName + ") (varValue: " + varValue + ")");
                } else if (varType.startsWith("java.util.concurrent.atomic.Atomic")) {
                    varType = "String";
                    variableName = variableName + ".toString()";
                    varValue = parseValue(varType, variable.toString());
                    System.out.println("variable is a " + variable.getClass().getName() + " (variableName: " + variableName + ") (varValue: " + varValue + ")");
                } else if (variable instanceof java.util.StringTokenizer) {
                    varType = "int";
                    variableName = variableName + ".countTokens()";
                    varValue = parseValue(varType, ((java.util.StringTokenizer) variable).countTokens());
                    System.out.println("variable is a java.util.StringTokenizer (variableName: " + variableName + ") (varValue: " + varValue + ")");
                }

                if (varValue.startsWith("BLOCKGEN!!")) {
                    // We cannot deserialize the variable, so we just skip adding assertion statement for it because the assertion will fail anyway
                    System.out.println("Variable cannot be deserialized, skip adding assertion statement for variable " + variableName);
                    return;
                } else if (varValue.startsWith("BLOCKGEN**")) {
                    // We cannot compare with this variable, so we just skip adding assertion statement for it because the assertion will fail anyway
                    System.out.println("Variable cannot be compared, skip adding assertion statement for variable " + variableName);
                    return;
                }

                String checkStmt;
                if (info.equals(Constant.TARGET_STMT_RETURN)) {
                    // return statement
                    // TODO: handle StringBuilder/StringBuffer/BLOCKGEN## (probably need a different API to do .toString())
                    if (varValue.startsWith("BLOCKGEN@@") || varValue.startsWith("BLOCKGEN##")) {
                        // Need type info when it is a xml file
                        varValue = varValue.replace("BLOCKGEN@@", "").replace("BLOCKGEN##", "").replace("BLOCKGEN!!", "").replace("BLOCKGEN**", "");
                        if (varValue.contains(".txt")) {
                            checkStmt = Constant.CHECK_RETURN_EQ + "(" + varValue + ")";
                        } else {
                            if (type.isEmpty()) {
                                checkStmt = Constant.CHECK_RETURN_EQ + "(" + varValue + ",\"" + getRuntimeTypeString(variable) + "\")";
                            } else {
                                checkStmt = Constant.CHECK_RETURN_EQ + "(" + varValue + ",\"" + type + "\")";
                            }
                        }
                    } else {
                        checkStmt = Constant.CHECK_RETURN_EQ + "(" + varValue + ")";
                        String varTypeStr = getRuntimeTypeString(variable);
                        if (varTypeStr.equals("java.lang.Double") || varTypeStr.equals("java.lang.Float")) {
                            // TODO: cannot handle Number: assertEq(0.1, NumberObject, 0.01)
                            if (!varValue.contains("Infinity")) {
                                checkStmt = Constant.CHECK_RETURN_EQ + "(" + varValue + ", 0.01)";
                                if (varValue.equals("NaN")) {
                                    checkStmt = Constant.CHECK_RETURN_EQ + "(" + varTypeStr + ".NaN, 0.01)";
                                }
                            } else {
                                String infType = getInfType(varValue, varTypeStr);
                                checkStmt = Constant.CHECK_RETURN_EQ + "(" + infType + ", 0.01)";
                            }
                        }
                    }
                } else {
                    // non return statement
                    if (varValue.startsWith("BLOCKGEN@@")) {
                        // Need type info when it is a xml file
                        varValue = varValue.replace("BLOCKGEN@@", "");
                        if (varValue.contains(".txt")) {
                            checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ")";
                        } else {
                            if (type.isEmpty()) {
                                checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ",\"" + getRuntimeTypeString(variable) + "\")";
                            } else {
                                checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ",\"" + type + "\")";
                            }
                        }
                    } else if (varValue.startsWith("BLOCKGEN##")) {
                        variableName = variableName + ".toString()";
                        String value = "\"" + Utils.escapeString(variable.toString()) + "\"";
                        checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + value + ")";
                    } else {
                        checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ")";
                        String varTypeStr = getRuntimeTypeString(variable);
                        System.out.println("varTypeStr is " + varTypeStr + " for variable " + Utils.escapeString(variableName) + " with varValue " + varValue);
                        if (varTypeStr.equals("java.lang.Double") || varTypeStr.equals("java.lang.Float")) {
                            // TODO: cannot handle Number: assertEq(0.1, NumberObject, 0.01)
                            if (!varValue.contains("Infinity")) {
                                checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varValue + ", 0.01)";
                                if (varValue.equals("NaN")) {
                                    checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + varTypeStr + ".NaN, 0.01)";
                                }
                            } else {
                                String infType = getInfType(varValue, varTypeStr);
                                checkStmt = Constant.CHECK_EQ + "(\"" + Utils.escapeString(variableName) + "\"," + infType + ", 0.01)";
                            }
                        }
                    }
                }
                if (targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtNum)) {
                    BlockTest currentBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtNum);
                    currentBlockTest.assertions.add(checkStmt);
                }
                saveFiles();
            } else if (info.equals(Constant.TARGET_STMT_THROW)) {
                if (variable.toString().contains("org.evosuite")) {
                    System.out.println("Evosuite exception, skip adding assertion statement for exception");
                    return;
                }

                System.out.println("Found exception, add assertion statement for exception");
                String expectStmt = Constant.EXPECT + "(" + variable + ".class)";
                if (targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtNum)) {
                    BlockTest currentBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtNum);
                    currentBlockTest.expect = expectStmt;
                }
                saveFiles();
            } else if (info.equals(Constant.TARGET_STMT_FLOW)) {
                String varValue = parseValue("String", variable);
                if (varValue.startsWith("BLOCKGEN@@") || varValue.startsWith("BLOCKGEN##")) {
                    // Need type info when it is a xml file
                    varValue = varValue.replace("BLOCKGEN@@", "").replace("BLOCKGEN##", "").replace("BLOCKGEN!!", "").replace("BLOCKGEN**", "");
                }

                System.out.println("Found control flow variable with value " + varValue + ", add assertion statement for control flow");
                String checkFlowStmt = Constant.CHECK_FLOW + "(" + varValue + ")";
                if (targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtNum)) {
                    BlockTest currentBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtNum);
                    currentBlockTest.assertions.add(checkFlowStmt);
                }
                saveFiles();
            } else if (info.equals(Constant.TARGET_STMT_END)) {
                // update coverage information
                System.out.println("fragment end reached, update coverage information");
                addCoverageRateAfter(targetStmtNum, clazz.getName(), classesDirectory);
                saveFiles();
            } else if (info.equals(Constant.CHECK_COVERAGE)) {
                // check if the coverage rate is different from existing block tests
                System.out.println("input captured, adding block tests");
                addBlockTest(targetStmtNum, clazz.getName(), classesDirectory);
                targetStmtLineNoToCurBlockTestMap.remove(targetStmtNum);
                saveFiles();
            } else if (info.equals(Constant.MOCKING)) {
                // handle mocking
                String mockStmt = Constant.MOCK + "(\"" + Utils.escapeString(variableName) + "(..)\")";
                String mockStmt2 = Constant.MOCK + "(\"" + Utils.escapeString(variableName) + "()\")";
                if (targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtNum)) {
                    BlockTest currentBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtNum);
                    currentBlockTest.mocking.add(mockStmt);
                    currentBlockTest.mocking.add(mockStmt2);
                }
                saveFiles();
            }
        } catch (Exception ex) {
            System.out.println("logVariableAndGenerateTest has an exception");
            ex.printStackTrace();
            targetStmtLineNoToCurBlockTestMap.remove(targetStmtNum);
        }
    }

    private static String getInfType(String varValue, String varTypeStr) {
        String infType = "INFINITY";
        if (varValue.equals("Infinity")) {
            infType = "POSITIVE_" + infType;
        } else {
            infType = "NEGATIVE_" + infType;
        }
        if (varTypeStr.equals("java.lang.Double")) {
            infType = "Double." + infType;
        } else {
            infType = "Float." + infType;
        }
        return infType;
    }

    public static String getRuntimeTypeString(Object obj) {
        if (obj == null) {
            return "Object";
        }

        // Collections
        if (obj instanceof Collection) {
            Collection<?> coll = (Collection<?>) obj;
            String className = obj.getClass().getCanonicalName();
            String elementType = "Object";
            if (!coll.isEmpty()) {
                Object first = coll.iterator().next();
                if (first != null) {
                    elementType = first.getClass().getCanonicalName();
                }
            }

            if (className.equals("java.util.Arrays.ArrayList") || className.equals("java.util.Collections.EmptyList")) {
                // they are private
                className = "java.util.List";
            }
            return className + "<" + elementType + ">";
        }
        if (obj.getClass().isArray()) {
            Class<?> componentType = obj.getClass().getComponentType();
            String componentName = (componentType != null) ? componentType.getCanonicalName() : "Object";
            return componentName + "[]";
        }
        return obj.getClass().getCanonicalName();
    }

    /**
     * read coverage information from file and scan srcPath for all target fragment ranges
     */
    public static void init(String srcPath, Class clazz) {
        System.out.println("Reading old results");

        try {
            if (Files.exists(Paths.get(Constant.r0TestPath))) {
                System.out.println("Reading old tests");
                for (String test : Files.readAllLines(Paths.get(Constant.r0TestPath))) {
                    String testID = test.split(";")[2].split("\"")[1].split("_")[2];
                    totalBlockTests = Math.max(totalBlockTests, Integer.parseInt(testID));
                    System.out.println("Reading testID " + testID);

                    String tmp = test.split(";")[2];
                    blocktestStrings.add(tmp.substring(tmp.indexOf(')')+1));
                }
                System.out.println("Set to " + totalBlockTests + " total tests");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Unable to read r0 file");
        }

        blockGenDir = Utils.createDir(Constant.BLOCK_GEN_DIR_NAME);
        serializedDataDir = Utils.createDir(Constant.BLOCK_GEN_DIR_NAME + "/" + Constant.SERIALIZED_DATA_DIR_NAME);

        // read number of block tests for each target statement
        String blockTestsCounterFile = blockGenDir + "/" + Constant.BLOCK_TESTS_COUNTER_FILE_NAME;
        Path blockTestsCounterFilePath = Paths.get(blockTestsCounterFile);
        if (Files.exists(blockTestsCounterFilePath)) {
            System.out.println("Reading old counter");
            try {
                BufferedReader reader = new BufferedReader(new FileReader(blockTestsCounterFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("END")) {
                        System.out.println("FILE ENDED");
                        break;
                    }
                    String[] tokens = line.split(";");
                    if (tokens.length <= 1) {
                        continue;
                    }
                    String srcLineNo = tokens[0];
                    int count = Integer.parseInt(tokens[1]);
                    srcLineNoCounter.put(srcLineNo, count);
                }
                reader.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // read coverage information for each target statement
        String coverageFile = blockGenDir + "/" + Constant.COVERAGE_FILE_NAME;
        Path coverageFilePath = Paths.get(coverageFile);
        if (Files.exists(coverageFilePath)) {
            System.out.println("Reading old coverage");
            try {
                BufferedReader reader = new BufferedReader(new FileReader(coverageFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("END")) {
                        System.out.println("FILE ENDED");
                        break;
                    }
                    String[] tokens = line.split(";");
                    String classLineNo = tokens[0];

                    for (int i = 1; i < tokens.length; i++) {
                        String lineNo = tokens[i];
                        if (!classLineNoToCovered.containsKey(classLineNo)) {
                            classLineNoToCovered.put(classLineNo, new HashSet<String>());
                        }
                        classLineNoToCovered.get(classLineNo).add(lineNo);
                    }
                }
                reader.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // read serialized data to path
        String serializedDataToPath = blockGenDir + "/" + Constant.SERIALIZED_DATA_TO_PATH_FILE_NAME;
        File serializedDataDirFile = new File(serializedDataToPath);
        if (serializedDataDirFile.exists()) {
            System.out.println("Reading old serialized data");
            try {
                BufferedReader reader = new BufferedReader(new FileReader(serializedDataToPath));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("END")) {
                        System.out.println("FILE ENDED");
                        break;
                    }
                    String[] tokens = line.split(";");
                    String serialzedData = tokens[0];
                    String path = tokens[1];
                    serializedDataToFilePathMap.put(serialzedData, path);
                    System.out.println("Adding " + serialzedData + " to path " + path);
                }
                reader.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        String scanPath = srcPath;
        if (clazz.getSimpleName().endsWith("_Extracted") && !srcPath.endsWith("_Extracted.java")) {
            String extractedFileName = clazz.getSimpleName() + ".java";
            // Replace just the filename portion, keeping the directory path
            String dir = srcPath.substring(0, srcPath.lastIndexOf("/") + 1);
            scanPath = dir + extractedFileName;
        }
        fragmentRanges.addAll(Utils.scanFragmentRanges(scanPath));

        String uncoveredLinesFile = blockGenDir + "/" + Constant.UNCOVERED_LINES_FILE_NAME;
        if (Files.exists(Paths.get(uncoveredLinesFile))) {
            try {
                for (String line : Files.readAllLines(Paths.get(uncoveredLinesFile))) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        uncoveredLines.add(Integer.parseInt(line));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            for (int[] range : fragmentRanges) {
                for (int line = range[0]; line <= range[1]; line++) {
                    uncoveredLines.add(line);
                }
            }
        }
    }

    public static void addBlockTest(String targetStmtLineNo, String clazzName, String classesDirectory) {
        if (!targetStmtLineNoToCurBlockTestMap.containsKey(targetStmtLineNo)) {
            return;
        }
        BlockTest curBlockTest = targetStmtLineNoToCurBlockTestMap.get(targetStmtLineNo);
        if (curBlockTest == null) {
            return;
        }
        // skip the block test if it does not have an assertion
        if (curBlockTest.assertions.isEmpty() && curBlockTest.expect.isEmpty()) {
            System.out.println("Skip block test because it does not have an assertion: " + curBlockTest);
            return;
        }
        if (curBlockTest.toString().length() > Constant.MAX_BLOCK_TEST_LENGTH) {
            System.out.println("Skip block test because it is too long (length: " + curBlockTest.toString().length() + "): " + curBlockTest);
            return;
        }
        String key = curBlockTest.srcPath + ":" + curBlockTest.targetStmtLineNo;

        // Discard duplicated block tests
        String tmp = curBlockTest.toString();
        if (tmp.indexOf(')') == -1) {
            System.out.println("Invalid block test, skip adding to r0 and r1: " + curBlockTest);
            return;
        }
        String testStr = tmp.substring(tmp.indexOf(')')+1);
        if (blocktestStrings.contains(testStr)) {
            System.out.println("Duplicated block test, skip adding to r0 and r1: " + curBlockTest);
            return;
        }
        blocktestStrings.add(testStr);

        totalBlockTests++;

        String testName = "AUTO_GEN_" + totalBlockTests;
        curBlockTest.testName = testName;

        System.out.println("Generated block test...");
        System.out.println(curBlockTest.toString());

        if (!blockTests.contains(curBlockTest)) {
            if (canAddBlockTest(targetStmtLineNo, clazzName, classesDirectory)) {
                System.out.println("Add to r1");
                saveReducedBlockTest(curBlockTest);
                int counter = srcLineNoCounter.getOrDefault(key, 0);
                srcLineNoCounter.put(key, counter + 1);
            } else {
                System.out.println("Coverage not changed, skip adding to r1");
            }
        }

        if (!allBlockTests.contains(curBlockTest)) {
            System.out.println("Add to r0");
            int allCounter = allSrcLineNoCounter.getOrDefault(key, 0);
            saveAllBlockTest(curBlockTest);
            allSrcLineNoCounter.put(key, allCounter + 1);
        }

        printFragmentCoverage(testName, clazzName, classesDirectory);
    }

    private static void printFragmentCoverage(String testName, String clazzName, String classesDirectory) {
        if (fragmentRanges.isEmpty()) {
            return;
        }
        CoverageBuilder cb = getCoverageRateFromAllClasses(classesDirectory);
        String internalName = clazzName.replace('.', '/');
        IClassCoverage cc = cb.getClasses().stream()
                .filter(c -> c.getName().equals(internalName))
                .findFirst().orElse(null);
        if (cc == null) {
            System.out.println("Fragment coverage: class not found in snapshot: " + clazzName);
            return;
        }

        List<Integer> newlyCoveredLines = new ArrayList<>();
        // update uncoveredLines: remove covered lines, and also remove any EMPTY lines that
        // were seeded from fragmentRanges but are not executable
        for (int[] range : fragmentRanges) {
            for (int line = range[0]; line <= range[1]; line++) {
                if (!uncoveredLines.contains(line)) continue;
                int status = cc.getLine(line).getStatus();
                if (status == ICounter.FULLY_COVERED || status == ICounter.PARTLY_COVERED) {
                    uncoveredLines.remove(line);
                    newlyCoveredLines.add(line);
                } else if (status == ICounter.EMPTY) {
                    uncoveredLines.remove(line);
                }
            }
        }
        saveUncoveredLines(testName, newlyCoveredLines);

        for (int[] range : fragmentRanges) {
            int totalStmts = 0, coveredStmts = 0;
            int totalBranches = 0, coveredBranches = 0;
            for (int line = range[0]; line <= range[1]; line++) {
                int status = cc.getLine(line).getStatus();
                if (status != ICounter.EMPTY) {
                    totalStmts++;
                    if (!uncoveredLines.contains(line)) {
                        coveredStmts++;
                    }
                }
                ICounter bc = cc.getLine(line).getBranchCounter();
                totalBranches   += bc.getTotalCount();
                coveredBranches += bc.getCoveredCount();
            }
            String stmtPct   = totalStmts    > 0 ? String.format("%.1f%%", 100.0 * coveredStmts    / totalStmts)    : "n/a";
            String branchPct = totalBranches > 0 ? String.format("%.1f%%", 100.0 * coveredBranches / totalBranches) : "n/a";
            System.out.println("Fragment coverage [" + range[0] + "-" + range[1] + "]"
                    + "  stmt: " + coveredStmts + "/" + totalStmts + " (" + stmtPct + ")"
                    + "  branch: " + coveredBranches + "/" + totalBranches + " (" + branchPct + ")");
        }
    }

    private static void saveUncoveredLines(String testName, List<Integer> newlyCoveredLines) {
        String uncoveredLinesFile = blockGenDir + "/" + Constant.UNCOVERED_LINES_FILE_NAME;
        try (FileWriter writer = new FileWriter(uncoveredLinesFile)) {
            for (int line : uncoveredLines) {
                writer.write(line + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (newlyCoveredLines.isEmpty())
            return;

        String mapping = blockGenDir + "/" + Constant.TESTS_TO_COVERED_LINES_FILE_NAME;
        try (FileWriter writer = new FileWriter(mapping, true)) {
            StringBuilder line = new StringBuilder(testName);
            for (int coveredLine : newlyCoveredLines) {
                line.append(",").append(coveredLine);
            }
            writer.write(line + "\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void saveReducedBlockTest(BlockTest curBlockTest) {
        blockTests.add(curBlockTest);
        // save reduced block tests to file
        saveBlockTestToFile(curBlockTest, Constant.r1TestPath);
    }

    public static void saveAllBlockTest(BlockTest curBlockTest) {
        allBlockTests.add(curBlockTest);
        // save all block tests to file
        saveBlockTestToFile(curBlockTest, Constant.r0TestPath);
    }

    public static void saveBlockTestToFile(BlockTest blockTest, String destPath) {
        if (destPath == null) {
            return;
        }
        // save inline test
        try {
            FileWriter writer = new FileWriter(destPath, true);
            writer.write(blockTest.srcPath + ";" + blockTest.targetStmtLineNo + ";" + blockTest.toString()
                    + "\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveFiles() {
        System.out.println("File Saver is RUN!");
        blockGenDir = Utils.createDir(Constant.BLOCK_GEN_DIR_NAME);

        // write number of block tests for each target statement
        String blockTestsCounterFile = blockGenDir + "/" + Constant.BLOCK_TESTS_COUNTER_FILE_NAME;
        try {
            FileWriter writer = new FileWriter(blockTestsCounterFile);
            for (String srcLineNo : srcLineNoCounter.keySet()) {
                writer.write(srcLineNo + ";" + srcLineNoCounter.get(srcLineNo) + "\n");
            }
            writer.write("END\n");
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // write coverage information for each target statement
        String coverageFile = blockGenDir + "/" + Constant.COVERAGE_FILE_NAME;
        try {
            FileWriter writer = new FileWriter(coverageFile);
            for (String classLineNo : classLineNoToCovered.keySet()) {
                writer.write(classLineNo);
                for (String lineNo : classLineNoToCovered.get(classLineNo)) {
                    writer.write(";" + lineNo);
                }
                writer.write("\n");
                writer.write("END\n");
            }
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // write serialized data to path
        String serializedDataToPath = blockGenDir + "/" + Constant.SERIALIZED_DATA_TO_PATH_FILE_NAME;
        try {
            FileWriter writer = new FileWriter(serializedDataToPath);
            for (String serializedData : serializedDataToFilePathMap.keySet()) {
                writer.write(serializedData + ";" + serializedDataToFilePathMap.get(serializedData) + "\n");
            }
            writer.write("END\n");
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String parseVarType(Object variable) {
        String varType = "";
        Class<?> clz = null;
        if (variable == null) {
            varType = "null";
        } else {
            clz = variable.getClass();
            switch (clz.getName()) {
                case "java.lang.Boolean":
                    varType = "boolean";
                    break;
                case "java.lang.Byte":
                    varType = "byte";
                    break;
                case "java.lang.Character":
                    varType = "char";
                    break;
                case "java.lang.Short":
                    varType = "short";
                    break;
                case "java.lang.Integer":
                    varType = "int";
                    break;
                case "java.lang.Long":
                    varType = "long";
                    break;
                case "java.lang.Float":
                    varType = "float";
                    break;
                case "java.lang.Double":
                    varType = "double";
                    break;
                case "java.lang.String":
                    varType = "String";
                    break;
                default:
                    varType = variable.getClass().getSimpleName();
                    break;
            }
        }
        return varType;
    }

    public static String parseValue(String varType, Object variable) {
        return parseValue(varType, variable, true);
    }

    public static String parseValue(String varType, Object variable, boolean saveToFile) {
        if (variable == null) {
            return "null";
        }
        String value = "";
        if (Constant.PRIMITIVE_TYPES.contains(varType)) {
            if (varType.endsWith("[]")) {
                String arrayLiteral = "new " + varType + " {";
                String arrayVal = parseArrayValue(varType, variable);
                arrayLiteral += arrayVal.substring(1, arrayVal.length() - 1);
                arrayLiteral += "}";

                if (saveToFile) {
                    String hashCode = Integer.toString(arrayLiteral.hashCode());
                    if (serializedDataToFilePathMap.containsKey(hashCode)) {
                        return "BLOCKGEN@@" + serializedDataToFilePathMap.get(hashCode);
                    }
                    String arrFileName = "@" + serializedDataToFilePathMap.size() + ".java";
                    String filePath = serializedDataDir + File.separator + serializedDataToFilePathMap.size() + ".java";
                    String escapedFilePath = "\"" + Utils.escapeString(arrFileName) + "\"";
                    serializedDataToFilePathMap.put(hashCode, escapedFilePath);
                    System.out.println("Writing new java fragment file to " + filePath);
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                        writer.write(arrayLiteral);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return "BLOCKGEN@@" + escapedFilePath;
                }

                value = arrayLiteral;
            } else {
                if (varType.equals("String")) {
                    if (saveToFile && ((String) variable).length() > Constant.MAX_STRING_SIZE) {
                        String raw = Utils.escapeString((String) variable);
                        String hashCode = Integer.toString(raw.hashCode());
                        if (serializedDataToFilePathMap.containsKey(hashCode)) {
                            return "BLOCKGEN@@" + serializedDataToFilePathMap.get(hashCode);
                        }
                        String txtFileName = "@" + serializedDataToFilePathMap.size() + ".txt";
                        String filePath = serializedDataDir + File.separator + serializedDataToFilePathMap.size() + ".txt";
                        String escapedFilePath = "\"" + Utils.escapeString(txtFileName) + "\"";
                        serializedDataToFilePathMap.put(hashCode, escapedFilePath);
                        System.out.println("Writing new txt file to " + filePath);
                        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                            writer.write(raw);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return "BLOCKGEN@@" + escapedFilePath;
                    }
                    value = "\"" + Utils.escapeString((String) variable) + "\"";
                } else if (varType.equals("char")) {
                    if (variable.equals('\'')) {
                        value = "'\\''";
                    } else if (variable.equals('\n')) {
                        value = "'\\n'";
                    } else if (variable.equals('\r')) {
                        value = "'\\r'";
                    } else if (variable.equals('\\')) {
                        value = "'\\\\'";
                    } else {
                        int charValue = (int) ((char) variable);
                        if (charValue >= 32 && charValue <= 126) {
                            // Printable character
                            value = "'" + variable + "'";
                        } else {
                            value = "'\\u" + String.format("%04x", charValue) + "'";
                        }
                    }
                } else if (varType.equals("long")) {
                    value = variable + "L";
                } else if (varType.equals("float")) {
                    if (!variable.toString().contains("Infinity") && !variable.toString().contains("NaN")) {
                        value = variable + "f";
                    } else {
                        value = variable + "";
                    }
                } else {
                    value = variable.toString();
                }
            }

        } else {
            try {
                boolean useToString = false;
                boolean deserializeFailed = false;
                boolean noAssertion = false;

                XStream xstream = new XStream();

                // We don't want this, because the object is *_Extracted/MockIOException, but
                // we will get deserialized object is not equal to original object if we convert
//                XStream xstream = new XStream() {
//                    @Override
//                    protected MapperWrapper wrapMapper(MapperWrapper next) {
//                        return new MapperWrapper(next) {
//                            @Override
//                            public Class realClass(String elementName) {
//                                if (elementName.endsWith("_Extracted")) {
//                                    return Object.class;
//                                }
//                                if (elementName.equals("org.evosuite.runtime.mock.java.io.MockIOException")) {
//                                    return IOException.class;
//                                }
//                                return super.realClass(elementName);
//                            }
//
//                            @Override
//                            public String serializedClass(Class type) {
//                                if (type != null && type.getName().endsWith("_Extracted")) {
//                                    return super.serializedClass(Object.class);
//                                }
//                                if (type != null && type.getName().equals("org.evosuite.runtime.mock.java.io.MockIOException")) {
//                                    return super.serializedClass(IOException.class);
//                                }
//                                return super.serializedClass(type);
//                            }
//                        };
//                    }
//                };

//                xstream.registerConverter(new ReflectionConverter(xstream.getMapper(), xstream.getReflectionProvider()) {
//                    @Override
//                    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
//                        try {
//                            return super.unmarshal(reader, context);
//                        } catch (Exception e) {
//                            return null;
//                        }
//                    }
//                }, XStream.PRIORITY_VERY_LOW);
//                xstream.registerConverter(new SerializableConverter(xstream.getMapper(), xstream.getReflectionProvider(), xstream.getClassLoader()) {
//                    @Override
//                    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
//                        try {
//                            return super.unmarshal(reader, context);
//                        } catch (Exception e) {
//                            return null;
//                        }
//                    }
//                }, XStream.PRIORITY_VERY_LOW);
                xstream.ignoreUnknownElements();
                xstream.registerConverter(new URLConverter() {
                    @Override
                    public Object fromString(String str) {
                        try {
                            return super.fromString(str);
                        } catch (Exception e) {
                            System.err.println("Skipping malformed URL: " + str);
                            return null;
                        }
                    }
                }, XStream.PRIORITY_VERY_HIGH);
                xstream.registerConverter(new ReflectionConverter(xstream.getMapper(), xstream.getReflectionProvider()) {
                    @Override
                    protected Object unmarshallField(UnmarshallingContext context, Object result, Class type, Field field) {
                        try {
                            return super.unmarshallField(context, result, type, field);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }, XStream.PRIORITY_VERY_LOW);
                String serializedString = xstream.toXML(variable);
                xstream.addPermission(AnyTypePermission.ANY);
                String serializedStringHashCode = Integer.toString(serializedString.hashCode());
                try {
                    Object deserialized = xstream.fromXML(serializedString);
                    try {
                        boolean notEquals = false;
                        if (!variable.equals(deserialized)) {
                            System.out.println("Warning: deserialized object is not equal to original object using equals() method");
                            try {
                                Assertion.assertEquals(variable, deserialized);
                                System.out.println("OK: deserialized object equals to original object using Assertion.assertEquals() method");
                            } catch (Exception | Error ex) {
                                System.out.println("Warning: deserialized object is not equal to original object using Assertion.assertEquals() method");
                                notEquals = true;
                            }
                        } else if (variable.getClass().getMethod("equals", Object.class).getDeclaringClass().equals(Object.class)) { // TODO: unsure if this need this one
                            System.out.println("Warning: equals method is not implemented for variable " + variable);
                            notEquals = true;
                        }

                        if (notEquals) {
                            // Just check if toString() works or not...
                            // if (!variable.getClass().getMethod("toString").getDeclaringClass().equals(Object.class)) {
                            if (variable.toString().equals(deserialized.toString())) {
                                System.out.println("toString method returns the same string for original and deserialized object");
                                useToString = true;
                            } else {
                                System.out.println("toString method returns different string for original and deserialized object");
                                System.out.println("'" + variable + "' vs '" + deserialized + "'");
                                noAssertion = true;
                            }
                        }
                    } catch (Exception ex) {
                        noAssertion = true;
                        System.out.println("Warning: toString method crashed");
                        ex.printStackTrace();
                    }
                } catch (Exception ex) {
                    deserializeFailed = true;
                    System.out.println("Warning: failed to deserialize serialized string for variable " + variable);
                    ex.printStackTrace();
                }

                // check if the serialized data has been seen before
                if (serializedDataToFilePathMap.containsKey(serializedStringHashCode)) {
                    return deserializePrefix(useToString, deserializeFailed, noAssertion) + serializedDataToFilePathMap.get(serializedStringHashCode);
                }
                System.out.println("Writing new file to " + serializedDataToFilePathMap.size() + ".xml");
                // String xmlFileName = varType + serializedDataToFilePathMap.size() + ".xml";
                String xmlFileName = "@" + serializedDataToFilePathMap.size() + ".xml";
                String filePath = serializedDataDir + File.separator + serializedDataToFilePathMap.size() + ".xml";
                String escapedFilePath = "\"" + Utils.escapeString(xmlFileName) + "\"";
                serializedDataToFilePathMap.put(serializedStringHashCode, escapedFilePath);
                System.out.println("serializedDataToFilePathMap[" + serializedStringHashCode + "] = " + escapedFilePath);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                    writer.write(serializedString);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                // return the file path
                return deserializePrefix(useToString, deserializeFailed, noAssertion) + escapedFilePath;
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to serialize variable: " + variable, e);
            }
        }
        return value;
    }

    private static String deserializePrefix(boolean useToString, boolean deserializeFailed, boolean noAssertion) {
        if (useToString) {
            return "BLOCKGEN##";
        } else if (deserializeFailed) {
            return "BLOCKGEN!!";
        } else if (noAssertion) {
            return "BLOCKGEN**";
        } else {
            return "BLOCKGEN@@";
        }
    }

    public static String parseArrayValue(String varType, Object variable) {
        String arrayVal = "";
        if (varType.equals("boolean[]")) {
            arrayVal = java.util.Arrays.toString((boolean[]) variable);
        } else if (varType.equals("byte[]")) {
            arrayVal = java.util.Arrays.toString((byte[]) variable);
        } else if (varType.equals("char[]")) {
            arrayVal += "[";
            for (int i = 0; i < ((char[]) variable).length; i++) {
                arrayVal += parseValue("char", ((char[]) variable)[i], false);
                if (i < ((char[]) variable).length - 1) {
                    arrayVal += ", ";
                }
            }
            arrayVal += "]";
        } else if (varType.equals("short[]")) {
            arrayVal = java.util.Arrays.toString((short[]) variable);
        } else if (varType.equals("int[]")) {
            arrayVal = java.util.Arrays.toString((int[]) variable);
        } else if (varType.equals("long[]")) {
            arrayVal += "[";
            for (int i = 0; i < ((long[]) variable).length; i++) {
                arrayVal += parseValue("long", ((long[]) variable)[i], false);
                if (i < ((long[]) variable).length - 1) {
                    arrayVal += ", ";
                }
            }
            arrayVal += "]";
        } else if (varType.equals("float[]")) {
//            arrayVal = java.util.Arrays.toString((float[]) variable).replace("NaN", "Float.NaN");
            boolean hasFirst = false;
            arrayVal += "[";
            for (String item : java.util.Arrays.toString((float[]) variable).replace("NaN", "Float.NaN").replace("[","").replace("]", "").split(", ")) {
                if (hasFirst) {
                    arrayVal += ", ";
                } else {
                    hasFirst = true;
                }
                if (item.isEmpty())
                    continue;
                if (item.contains("Infinity")) {
                    String infType = getInfType(item, "java.lang.Float");
                    arrayVal += infType;
                } else if (!item.contains("NaN")) {
                    arrayVal += item + "f";
                } else {
                    arrayVal += item;
                }
            }
            arrayVal += "]";
            System.out.println("Float arrayVal is " + arrayVal);
        } else if (varType.equals("double[]")) {
//            arrayVal = java.util.Arrays.toString((double[]) variable).replace("NaN", "Double.NaN");
            boolean hasFirst = false;
            arrayVal += "[";
            for (String item : java.util.Arrays.toString((double[]) variable).replace("NaN", "Double.NaN").replace("[","").replace("]", "").split(", ")) {
                if (hasFirst) {
                    arrayVal += ", ";
                } else {
                    hasFirst = true;
                }
                if (item.isEmpty())
                    continue;
                if (item.contains("Infinity")) {
                    String infType = getInfType(item, "java.lang.Double");
                    arrayVal += infType;
                } else {
                    arrayVal += item;
                }
            }
            arrayVal += "]";
        } else if (varType.equals("String[]")) {
            arrayVal += "[";
            for (int i = 0; i < ((String[]) variable).length; i++) {
                arrayVal += parseValue("String", ((String[]) variable)[i], false);
                if (i < ((String[]) variable).length - 1) {
                    arrayVal += ", ";
                }
            }
            arrayVal += "]";
        } else {
            arrayVal += "[";
            for (int i = 0; i < ((Object[]) variable).length; i++) {
                arrayVal += parseValue(varType.substring(0, varType.length() - 2), ((Object[]) variable)[i], false);
                if (i < ((Object[]) variable).length - 1) {
                    arrayVal += ", ";
                }
            }
            arrayVal += "]";
        }
        return arrayVal;
    }

    /*
     * ===== Coverage management =====
     */

    /**
     * A helper method to check if the coverage rate increases
     *
     * @param oldCC
     * @param newCC
     * @param key
     * @return
     */
    public static boolean isCovered(IClassCoverage oldCC, IClassCoverage newCC, String key) {
        boolean changed = false;
        for (int i = newCC.getFirstLine(); i <= newCC.getLastLine(); i++) {
            int status = newCC.getLine(i).getStatus();
            // Log.debug(key + ", class: " + newCC.getName() + ", line: " + i + ", status: "
            // + status);
            if ((status == ICounter.FULLY_COVERED
                    && (oldCC == null || oldCC.getLine(i).getStatus() == ICounter.NOT_COVERED
                    || oldCC.getLine(i).getStatus() == ICounter.PARTLY_COVERED))
                    || (status == ICounter.PARTLY_COVERED
                    && (oldCC == null || oldCC.getLine(i).getStatus() == ICounter.NOT_COVERED))) {
                if (classLineNoToCovered.get(key).contains(newCC.getName() + i)) {
                    continue;
                } else {
                    classLineNoToCovered.get(key).add(newCC.getName() + i);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static boolean coverageChanged(CoverageBuilder oldCoverageBuilder, CoverageBuilder newCoverageBuilder,
                                          String key) {
        boolean changed = false;
        for (final IClassCoverage cc : newCoverageBuilder.getClasses()) {
            if (cc.getInstructionCounter().getCoveredCount() == 0) {
                continue;
            } else {
                IClassCoverage oldCC = oldCoverageBuilder.getClasses().stream()
                        .filter(i -> i.getName().equals(cc.getName()))
                        .findFirst().orElse(null);
                changed = isCovered(oldCC, cc, key) || changed;
            }
        }
        return changed;
    }

    /**
     * Check if the inline test can be added
     *
     * @param lineNumber
     * @param clazzName,       string should be like "com.example.Example"
     * @param classesDirectory
     * @return
     */
    public static boolean canAddBlockTest(String lineNumber, String clazzName, String classesDirectory) {
        // This is the increased coverage rate after executing the target fragment
        String key = clazzName + lineNumber;
        CoverageBuilder oldCoverageBuilder = classLineNoToCoverageBefore.get(key);
        CoverageBuilder newCoverageBuilder = classLineNoToCoverageAfter.get(key);
        if (newCoverageBuilder == null || oldCoverageBuilder == null) {
            System.out.println("ERROR: Missing coverage builder");
            return false;
        }

        if (!classLineNoToCovered.containsKey(key)) {
            classLineNoToCovered.put(key, new HashSet<String>());
        }

        // coverage rate of target fragment itself
        boolean stmtChanged = coverageChanged(oldCoverageBuilder, newCoverageBuilder, key);
        System.out.println("coverageChanged(oldCoverageBuilder, newCoverageBuilder, key) -> " + stmtChanged);
        System.out.println("key -> " + key);

        // coverage rate of context
        CoverageBuilder currentCoverageBuilder = getCoverageRateFromAllClasses(classesDirectory);
        boolean contextChanged = coverageChanged(newCoverageBuilder, currentCoverageBuilder, key);
        System.out.println("coverageChanged(newCoverageBuilder, currentCoverageBuilder, key) -> " + contextChanged);
        return stmtChanged || contextChanged;
    }

    /**
     * Get the coverage rate of all classes before executing the target statement
     *
     * @param lineNumber
     * @param clazzName
     * @param clazzDirectory
     */
    public static void addCoverageRateBefore(String lineNumber, String clazzName, String clazzDirectory) {
        CoverageBuilder coverageBuilder = getCoverageRateFromAllClasses(clazzDirectory);
        if (coverageBuilder == null) {
            System.out.println("ERROR: addCoverageRateBefore's coverageBuilder is null");
            return;
        }
        classLineNoToCoverageBefore.put(clazzName + lineNumber, coverageBuilder);
    }

    /**
     * Get the coverage rate of all classes after executing the target statement
     *
     * @param lineNumber
     * @param clazzName
     * @param clazzDirectory
     */
    public static void addCoverageRateAfter(String lineNumber, String clazzName, String clazzDirectory) {
        CoverageBuilder coverageBuilder = getCoverageRateFromAllClasses(clazzDirectory);
        if (coverageBuilder == null) {
            System.out.println("ERROR: addCoverageRateAfter's coverageBuilder is null");
            return;
        }
        classLineNoToCoverageAfter.put(clazzName + lineNumber, coverageBuilder);
    }

    /**
     * Get the coverage rate of all classes
     *
     * @param classesDirectory
     * @return
     */
    public static CoverageBuilder getCoverageRateFromAllClasses(String classesDirectory) {
        try {
            IAgent agent = RT.getAgent();
            byte[] executionData = agent.getExecutionData(false);
            ExecFileLoader execFileLoader = new ExecFileLoader();
            execFileLoader.load(new ByteArrayInputStream(executionData));
            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(execFileLoader.getExecutionDataStore(), coverageBuilder);
            File classesDirectoryFile = new File(classesDirectory);
            if (!classesDirectoryFile.exists()) {
                throw new RuntimeException("Classes directory does not exist: " + classesDirectory);
            }
            analyzer.analyzeAll(classesDirectoryFile);
            return coverageBuilder;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
