package org.blockgen.strategies;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.Statement;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.Utils;
import org.blockgen.helpers.FragmentCollector;
import org.blockgen.helpers.MethodCallRemover;
import org.blockgen.helpers.StatementInserter;
import org.blockgen.helpers.VariableFinder;
import org.blockgen.types.SpoonResolver;
import org.blockgen.types.TypeResolver;
import org.blockgen.types.TypeResolverUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Genie {
    /**
     * Instrument the Java source file so that when tests execute, the
     * added instrumentations can generate inline tests with variable
     * values in target statements.
     */
    public static void instrument(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String startLineNumberStr, String endLineNumberStr, String logFilePath, String classesDirectory, String r0TestPath, String r1TestPath) throws IOException {
        if (!new File(outputDir).getParentFile().exists()) {
            new File(outputDir).getParentFile().mkdirs();
        }
        if (!new File(outputDir).exists()) {
            new File(outputDir).mkdirs();
        }

        TypeResolverUtil.depClassPaths = Utils.getDepsContent(projectDir, outputDir, blockGenDir);
        TypeResolverUtil.appSrcPath = absPathToSrc;
        TypeResolver.setup();
        try {
            SpoonResolver.setup(absPathToSrc, Utils.parseLineNumber(startLineNumberStr), Utils.parseLineNumber(endLineNumberStr));
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Unable to setup SpoonResolver. Good luck!");
        }

        Utils.replaceFile(outputDir + File.separator + "backup.java", absPathToSrc);

        CompilationUnit cu = StaticJavaParser.parse(Paths.get(absPathToSrc));
        Context ctx = new Context();
        int startLineNumber = Utils.parseLineNumber(startLineNumberStr);
        int endLineNumber = Utils.parseLineNumber(endLineNumberStr);
        new File(logFilePath).getParentFile().mkdirs();
        System.out.println("Creating directory at " + new File(logFilePath).getParentFile().toString());
        ctx.logPath = logFilePath;
        ctx.startLineNumber = startLineNumber;
        ctx.endLineNumber = endLineNumber;
        ctx.srcPath = absPathToSrc;
        ctx.r0TestPath = r0TestPath;
        ctx.r1TestPath = r1TestPath;
        ctx.classesDirectory = classesDirectory;

        // Use cu2 to find methods to mock without removing method using MethodCallRemover.
        CompilationUnit cu2 = StaticJavaParser.parse(Paths.get(absPathToSrc));
        Context ctx2 = new Context();
        ctx2.logPath = logFilePath;
        ctx2.startLineNumber = startLineNumber;
        ctx2.endLineNumber = endLineNumber;
        ctx2.srcPath = absPathToSrc;
        ctx2.r0TestPath = r0TestPath;
        ctx2.r1TestPath = r1TestPath;
        ctx2.classesDirectory = classesDirectory;
        System.out.println(">>> Collecting mock methods");
        VariableFinder.findVariables(cu2, ctx2, startLineNumber, endLineNumber, true);
        Set<String> mockMethods = ctx2.mockMethods;

        System.out.println(">>> Collecting variables");
        ctx.mockMethods = mockMethods;
        Context ctxBackup = ctx.clone();
        MethodCallRemover.remove(cu, true, startLineNumber, endLineNumber, mockMethods);
        VariableFinder.findVariables(cu, ctx, startLineNumber, endLineNumber, true);
        VariableFinder.findClassName(cu, ctx, startLineNumber, endLineNumber, true);

        System.out.println(">>> Extracting fragment");
        FragmentCollector fragmentCollector = new FragmentCollector();
        fragmentCollector.extract(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumber, endLineNumber, ctx.className, "extractedTest", ctx.clone());

        for (int i = 0; i < 2; i++) {
            Set<RepairStrategy> strategies = isRepairNeeded(i, projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumberStr, endLineNumberStr, logFilePath, classesDirectory, r0TestPath, r1TestPath);
            if (strategies.isEmpty()) {
                // Repair is not needed
                break;
            }

            String destPath = Paths.get(absPathToSrc).getParent().toString() + File.separator + ctx.className + ".java";
            Files.copy(Paths.get(destPath), Paths.get(outputDir + File.separator + "generated.java" + "." + (i+1)), StandardCopyOption.REPLACE_EXISTING);

            Utils.replaceFile(absPathToSrc, outputDir + File.separator + "backup.java"); // need to restore the original file (file with block tests inserted)

            if (strategies.contains(RepairStrategy.ASSERT_STATIC)) {
                System.out.println(">>> Repairing by asserting static variables");
                Constant.assertStatic = true;

                ctx = ctxBackup.clone();
                VariableFinder.findVariables(cu, ctx, startLineNumber, endLineNumber, true);
                VariableFinder.findClassName(cu, ctx, startLineNumber, endLineNumber, true);
            }
            if (strategies.contains(RepairStrategy.EXCEPTION_TESTING)) {
                System.out.println(">>> Repairing by exception testing");
                Constant.exceptionTesting = false;
            }
            if (strategies.contains(RepairStrategy.UNREACHABLE_STATEMENT)) {
                System.out.println(">>> Repairing unreachable statement");
                fragmentCollector.wrapInTry = true;
            }
            if (strategies.contains(RepairStrategy.REMOVE_FQP)) {
                System.out.println(">>> Repairing by removing fully qualified names");
                System.out.println(Constant.removeFQNType);

                // Need to re-calculate type, hence need to find variables again
                ctx = ctxBackup.clone();
                VariableFinder.findVariables(cu, ctx, startLineNumber, endLineNumber, true);
                VariableFinder.findClassName(cu, ctx, startLineNumber, endLineNumber, true);
            }
            if (strategies.contains(RepairStrategy.TYPE_ERROR)) {
                System.out.println(">>> Repairing type error");
                TypeResolverUtil.useSpoonFirst = true;

                ctx = ctxBackup.clone();
                VariableFinder.findVariables(cu, ctx, startLineNumber, endLineNumber, true);
                VariableFinder.findClassName(cu, ctx, startLineNumber, endLineNumber, true);
            }
            fragmentCollector.extract(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumber, endLineNumber, ctx.className, "extractedTest", ctx.clone());
        }
    }

    enum RepairStrategy {
        UNREACHABLE_STATEMENT,
        TYPE_ERROR,
        EXCEPTION_TESTING,
        ASSERT_STATIC,
        REMOVE_FQP
    }

    private static Set<RepairStrategy> isRepairNeeded(int attempt, String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String startLineNumberStr, String endLineNumberStr, String logFilePath, String classesDirectory, String r0TestPath, String r1TestPath) {
        Set<RepairStrategy> strategies = new HashSet<>();
        System.out.println(">>> Checking if extracted fragment compiles");
        if (FragmentCollector.noVariableToLog && !Constant.assertStatic) {
            strategies.add(RepairStrategy.ASSERT_STATIC);
            FragmentCollector.noVariableToLog = false;
        }

        if (!Utils.compileMavenProject(projectDir, outputDir, blockGenDir)) {
            System.out.println("Failed to compile project");
            // Repair time

            if (Constant.exceptionTesting && attempt >= 1) {
                strategies.add(RepairStrategy.EXCEPTION_TESTING);
            }

            String logFile = outputDir + File.separator + "compile-all.log";
            try {
                for (String line : Files.readAllLines(Paths.get(logFile))) {
                    if (line.contains("unreachable statement")) {
                        strategies.add(RepairStrategy.UNREACHABLE_STATEMENT);
                    }
                    if (line.contains("incompatible types:") || (line.contains("required, but") && line.contains("found")) || (line.contains("Extracted.java") && (line.contains("cannot find symbol") || line.contains("wrong number of type arguments")))) {
                        if (SpoonResolver.setUpOK)
                            strategies.add(RepairStrategy.TYPE_ERROR);
                    }
                    if (line.contains("reference to ") && line.endsWith("is ambiguous")) {
                        strategies.add(RepairStrategy.REMOVE_FQP);
                        Constant.removeFQNType.add(line.split("reference to ")[1].split(" ")[0]);
                    }
                }
            } catch (Exception ex) {
                System.out.println("Fail to repair compile error");
                ex.printStackTrace();
            }

            if (strategies.isEmpty()) {
                System.out.println("No known compile error found, unable to repair");
                System.exit(0);
            }
        }
        return strategies;
    }
}
