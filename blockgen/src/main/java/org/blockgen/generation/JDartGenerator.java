package org.blockgen.generation;

import org.blockgen.Constant;
import org.blockgen.Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.Collectors;


import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JDartGenerator {

    /** The log that keeps the output of the jdart generation process. */
    protected String jdartGenerationLog;

    private String projectDir;
    private String relpathToSrc;
    private String outputDir;
    private String blockGenDir;

    public JDartGenerator(String projectDir, String relpathToSrc, String outputDir, String blockGenDir) {
        this.projectDir = projectDir;
        this.relpathToSrc = relpathToSrc;
        this.outputDir = outputDir;
        this.blockGenDir = blockGenDir;
    }

    /** Generates JDart tests for classes specified in the JDart classpath list file. */
    public void generateJDartTests() {
        System.out.println("===== Generating JDart tests =====");

        // Create mutant directory if not exists
        new File(outputDir).getParentFile().mkdirs();
        new File(outputDir).mkdirs();

        int exitCode = -1;
        String jdartLogDir = outputDir + File.separator + "jdart-logs";
        String jdartTestsDir = projectDir + File.separator + "jdart-tests";
        this.jdartGenerationLog = jdartLogDir + File.separator + "generation-log.txt";
        File jdartLogFile = new File(jdartGenerationLog);
        if (!jdartLogFile.exists()) {
            jdartLogFile.getParentFile().mkdirs();
        }
        File jdartTestsFile = new File(jdartTestsDir);
        if (!jdartTestsFile.exists()) {
            jdartTestsFile.mkdirs();
        } else {
            System.out.println("JDart tests found, using existing ones and skipping generation.");
            return;
        }

        String jpfCorePath = System.getenv("JPF_CORE_PATH");
        if (jpfCorePath == null) {
            System.out.println("JPF_CORE_PATH environment variable is not set. Please set it to the path of the JPF Core installation.");
            System.exit(1);
        }
        String jdartPath = System.getenv("JDART_PATH");
        if (jdartPath == null) {
            System.out.println("JDART_PATH environment variable is not set. Please set it to the path of the JDart installation.");
            System.exit(1);
        }

        String jpfPath = outputDir + File.separator + "extractedTest.jpf";
        buildJPF(Paths.get(jpfPath));

        int timeout = 600;

        System.out.println("Timeout for JDart generation: " + timeout + " seconds");

        try {
            List<String> command = new ArrayList<>(Arrays.asList(jpfCorePath + File.separator + "bin" + File.separator + "jpf", jpfPath));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(jdartPath));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(this.jdartGenerationLog)));
            Process process = pb.start();
            exitCode = process.waitFor(timeout+600, TimeUnit.SECONDS) ? 0 : 1;
            int actualExit = process.exitValue();
            System.out.println("Exit code for JDart generation: " + actualExit + " and " + exitCode);

            String generatedTests = new LogToJUnitGenerator().generate(this.jdartGenerationLog);
            String testFilePath = jdartTestsDir + File.separator + "GeneratedUnitTests.java";
            Files.write(Paths.get(testFilePath), generatedTests.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("There was an issue with JDart test generation, check " + jdartGenerationLog + " for details.");
        }
    }

    public void buildJPF(Path jpfPath) {
    System.out.println("===== Building JPF configuration file =====");
    try {
        String target = Utils.getClassNameFromSrc(relpathToSrc);

        String parameters = Utils.getParameters(projectDir + File.separator + relpathToSrc);
        System.out.println("Parameters for target method: " + parameters);

        StringBuilder sb = new StringBuilder();
        sb.append("@using = jpf-jdart\n\n");
        sb.append("shell=gov.nasa.jpf.jdart.JDart\n\n");
        sb.append("log.finest=jdart\n");
        sb.append("log.info=constraints\n\n");
        sb.append("symbolic.dp=z3\n\n");
        sb.append("target=").append(target).append("\n\n");
        sb.append("concolic.method.extractedTest=").append(target).append(".extractedTest").append("(").append(parameters).append(")").append("\n");
        sb.append("concolic.method=extractedTest\n\n");
        sb.append("jpf-jdart = ${config_path}\n");

        List<String> entries = new ArrayList<>();
        entries.add("${jpf-jdart}/build/jpf-jdart-classes.jar");
        entries.add("${jpf-jdart}/build/jpf-jdart-annotations.jar");
        entries.add("${jpf-jdart}/build/tests");
        entries.add("${jpf-jdart}/build/examples");

        String deps = Utils.getDepsContent(projectDir, outputDir, blockGenDir);
        if (deps != null && !deps.isEmpty()) {
            entries.addAll(Arrays.asList(deps.split(File.pathSeparator)));
        }

        sb.append("jpf-jdart.classpath=\\\n");
        for (int i = 0; i < entries.size(); i++) {
            String e = entries.get(i);
            boolean last = (i == entries.size() - 1);
            sb.append("  ").append(e);
            if (!last) sb.append(";");
            if (!last) sb.append("\\");
            sb.append("\n");
        }

        Files.write(jpfPath, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote JPF file to: " + jpfPath.toString());
    } catch (IOException ex) {
        ex.printStackTrace();
    }
}

    public void compile() {
        System.out.println("===== Compiling JDart tests =====");
        String jdartTestsDir = projectDir + File.separator + "jdart-tests";
        String testFilePath = jdartTestsDir + File.separator + "GeneratedUnitTests.java";

        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";

        List<String> command = new ArrayList<>(Arrays.asList("javac", "-cp", junitStandaloneJar
                + File.pathSeparator + Utils.getDepsContent(projectDir, outputDir, blockGenDir), testFilePath));
        try {
            File logFile = new File(outputDir, "jdart-compilation.log");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectDir));
            pb.redirectOutput(logFile);
            pb.redirectErrorStream(true); // Merge stderr into stdout
            pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    public void execute() {
        System.out.println("===== Executing JDart tests =====");
        File logFile = new File(outputDir, "jdart-execution.log");
        String jdartTestsDir = projectDir + File.separator + "jdart-tests";
        String jacocoAgentJar = blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";

        try {
            List<String> command = new ArrayList<>(Arrays.asList("java", "-javaagent:" + jacocoAgentJar,
                    "-classpath",
                    jacocoAgentJar + File.pathSeparator
                            + Utils.getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                            + junitStandaloneJar + File.pathSeparator
                            + jdartTestsDir,
                    "org.junit.runner.JUnitCore", "GeneratedUnitTests"));

            System.out.println("Running command: " + String.join(" ", command));
            ProcessBuilder regressionTestPb = new ProcessBuilder(command);
            regressionTestPb.directory(new File(projectDir));
            regressionTestPb.redirectOutput(logFile);
            regressionTestPb.redirectErrorStream(true); // Merge stderr into stdout
            regressionTestPb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}

class LogToJUnitGenerator {

    // Matches: Analyses for method some.pkg.ClassName.methodName(param0:type0, param1:type1, ...)
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "Analyses for method\\s+(\\S+\\.([^.(]+))\\.([^(]+)\\(([^)]*)\\)"
    );

    // Matches a single param entry like: active0:long  or  jjmatchedPos:int
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "([\\w$]+)\\s*:\\s*([\\w.]+)"
    );

    // Matches a valuation line (starts with java.lang or a primitive type mapping)
    // e.g.: java.lang.Long:active1=0, java.lang.Integer:jjmatchedPos=2,
    private static final Pattern VALUATION_LINE_PATTERN = Pattern.compile(
            "^\\s*(?:java\\.lang\\.\\w+:\\w+=[-\\d]+,?\\s*)+"
    );

    // Matches a single assignment token like: java.lang.Long:active1=0
    // Value group is intentionally greedy-stopped — we parse values manually
    private static final Pattern ASSIGNMENT_START = Pattern.compile(
            "([\\w.]+):(\\w+)="
    );

    public String generate(String logPath) throws IOException {
        String logContent = new String(Files.readAllBytes(Paths.get(logPath)));
        String[] lines = logContent.split("\\r?\\n");

        String fullClassName = null;
        String simpleClassName = null;
        String methodName = null;
        List<String> paramNames = new ArrayList<String>(); // ordered param names
        Map<String, String> paramTypes = new LinkedHashMap<String, String>(); // name -> declared type (long/int)

        List<Map<String, String>> valuations = new ArrayList<Map<String, String>>();

        boolean inValuations = false;

        for (String rawLine : lines) {
            // Strip [INFO] prefix if present
            String line = rawLine.replaceFirst("^\\[INFO\\]\\s*", "").trim();

            // Detect method header
            Matcher mm = METHOD_PATTERN.matcher(line);
            if (mm.find()) {
                fullClassName = mm.group(1);         // e.g. org.pkg.ClassName_Extracted
                simpleClassName = mm.group(2);       // e.g. ClassName_Extracted
                methodName = mm.group(3);            // e.g. extractedTest
                String paramsStr = mm.group(4);      // e.g. active0:long,jjmatchedPos:int,...

                paramNames.clear();
                paramTypes.clear();
                for (String token : paramsStr.split(",")) {
                    Matcher pm = PARAM_PATTERN.matcher(token.trim());
                    if (pm.find()) {
                        String pName = pm.group(1);
                        String pType = pm.group(2); // long, int, etc.
                        paramNames.add(pName);
                        paramTypes.put(pName, pType);
                    }
                }
                inValuations = false;
                valuations.clear();
                continue;
            }

            // Detect start of valuation section
            if (line.startsWith("# of valuations")) {
                inValuations = true;
                continue;
            }

            // Stop collecting at separator
            if (inValuations && line.startsWith("---")) {
                inValuations = false;
                continue;
            }

            // Collect valuation lines
            if (inValuations && VALUATION_LINE_PATTERN.matcher(line).find()) {
                Map<String, String> valuation = parseValuationLine(line);
                if (!valuation.isEmpty()) {
                    valuations.add(valuation);
                }
            }
        }

        if (fullClassName == null || methodName == null) {
            System.err.println("Could not find method analysis header in log.");
            System.exit(1);
        }

        String testClassName = "GeneratedUnitTests";

        // --- Build CompilationUnit with JavaParser ---
        CompilationUnit cu = new CompilationUnit();

        // Add JUnit import
        cu.addImport("org.junit.Test");

        // Create public class
        ClassOrInterfaceDeclaration testClass = cu.addClass(testClassName, Modifier.Keyword.PUBLIC);

        // One @Test method per valuation
        for (int i = 0; i < valuations.size(); i++) {
            Map<String, String> val = valuations.get(i);

            MethodDeclaration method = testClass.addMethod(
                    "test" + (i + 1),
                    Modifier.Keyword.PUBLIC
            );

            // @Test(timeout = 1000)
            NormalAnnotationExpr testAnnotation = new NormalAnnotationExpr();
            testAnnotation.setName("Test");
            testAnnotation.addPair("timeout", new IntegerLiteralExpr("4000000"));
            method.addAnnotation(testAnnotation);

            // throws Exception
            method.addThrownException(new com.github.javaparser.ast.type.ClassOrInterfaceType("Exception"));
            method.setType("void");

            // Build the static method call arguments in param-declaration order
            NodeList<Expression> callArgs = new NodeList<Expression>();
            for (String pName : paramNames) {
                String rawVal = val.get(pName);
                if (rawVal == null) rawVal = "0";

                String declaredType = paramTypes.get(pName);
                if (declaredType == null) declaredType = "int";

                Expression argExpr = buildLiteral(rawVal, declaredType);
                callArgs.add(argExpr);
            }

            // fullClassName.methodName(args...)
            Expression callExpr = new MethodCallExpr(
                    new NameExpr(fullClassName),
                    methodName,
                    callArgs
            );

            BlockStmt body = new BlockStmt();
            body.addStatement(new ExpressionStmt(callExpr));
            method.setBody(body);
        }


        System.out.println(cu.toString());
        return cu.toString();
    }

    /**
     * Parse a valuation line into an ordered map of name->value.
     * Each entry is: java.lang.Type:name=VALUE, ...
     * The tricky part is VALUE may contain commas (e.g. Character=',')
     * so we parse left-to-right, using the next "java.lang.X:y=" as delimiter.
     */
    private static Map<String, String> parseValuationLine(String line) {
        Map<String, String> result = new LinkedHashMap<String, String>();

        Matcher m = ASSIGNMENT_START.matcher(line);
        List<int[]> starts = new ArrayList<int[]>();
        List<String> types = new ArrayList<String>();
        List<String> names = new ArrayList<String>();

        while (m.find()) {
            starts.add(new int[]{ m.start(), m.end() });
            types.add(m.group(1));
            names.add(m.group(2));
        }

        for (int i = 0; i < starts.size(); i++) {
            int valueStart = starts.get(i)[1];
            int valueEnd;
            boolean isChar = types.get(i).toLowerCase().contains("character");

            if (i + 1 < starts.size()) {
                // Value ends where the next header starts; strip the ", " separator before it
                valueEnd = starts.get(i + 1)[0];
                // Strip one trailing ", " (separator between entries)
                while (valueEnd > valueStart && line.charAt(valueEnd - 1) == ' ') valueEnd--;
                if (valueEnd > valueStart && line.charAt(valueEnd - 1) == ',') valueEnd--;
            } else {
                // Last entry: strip trailing whitespace, then exactly one trailing comma if present
                valueEnd = line.length();
                while (valueEnd > valueStart && line.charAt(valueEnd - 1) == ' ') valueEnd--;
                if (valueEnd > valueStart && line.charAt(valueEnd - 1) == ',') valueEnd--;
            }

            result.put(names.get(i), line.substring(valueStart, valueEnd));
        }

        return result;
    }

    private static Expression buildLiteral(String rawVal, String declaredType) {
        switch (declaredType.toLowerCase()) {

            case "long":
            case "java.lang.long":
                return new LongLiteralExpr(rawVal + "L");

            case "int":
            case "java.lang.integer":
                return new IntegerLiteralExpr(rawVal);

            case "short":
            case "java.lang.short":
                return new CastExpr(
                        com.github.javaparser.ast.type.PrimitiveType.shortType(),
                        new IntegerLiteralExpr(rawVal)
                );

            case "byte":
            case "java.lang.byte":
                return new CastExpr(
                        com.github.javaparser.ast.type.PrimitiveType.byteType(),
                        new IntegerLiteralExpr(rawVal)
                );

            case "double":
            case "java.lang.double":
                return new DoubleLiteralExpr(rawVal);

            case "float":
            case "java.lang.float":
                return new DoubleLiteralExpr(rawVal + "f");

            case "char":
            case "java.lang.character":
                if (rawVal.isEmpty()) {
                    return new CharLiteralExpr("\\0");
                }
                if (rawVal.equals("'")) return new CharLiteralExpr("\\'");
                if (rawVal.equals("\\")) return new CharLiteralExpr("\\\\");
                if (rawVal.equals("\n")) return new CharLiteralExpr("\\n");
                if (rawVal.equals("\r")) return new CharLiteralExpr("\\r");
                if (rawVal.equals("\t")) return new CharLiteralExpr("\\t");
                return new CharLiteralExpr(rawVal);

            case "boolean":
            case "java.lang.boolean":
                return new BooleanLiteralExpr("true".equalsIgnoreCase(rawVal) || "1".equals(rawVal.trim()));

            case "string":
            case "java.lang.string":
                return new StringLiteralExpr(rawVal);

            default:
                return new StringLiteralExpr(rawVal);
        }
    }
}
