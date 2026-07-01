package org.blockgen.strategies;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.Utils;
import org.blockgen.helpers.FragmentCollector;
import org.blockgen.helpers.StatementInserter;
import org.blockgen.helpers.VariableFinder;
import org.blockgen.types.SpoonResolver;
import org.blockgen.types.TypeResolver;
import org.blockgen.types.TypeResolverUtil;
import org.blockgen.visitors.BracesVisitor;
import org.blockgen.visitors.BreakReplacementVisitor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Exli {

    public static Set<String> brokenBefore = new HashSet<>();
    public static Set<String> brokenAfter = new HashSet();

    public static boolean wrapInTry = false;
    public static boolean noVariableToLog = false;


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
        instrumentFragment(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumberStr, endLineNumberStr, logFilePath, classesDirectory, r0TestPath, r1TestPath);

        for (int i = 0; i < 3; i++) {
            boolean isRepairNeeded = isRepairNeeded(i, projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumberStr, endLineNumberStr, logFilePath, classesDirectory, r0TestPath, r1TestPath);
            if (!isRepairNeeded) break;

            Files.copy(Paths.get(absPathToSrc), Paths.get(outputDir + File.separator + "generated.java" + "." + (i+1)), StandardCopyOption.REPLACE_EXISTING);
            Utils.replaceFile(absPathToSrc, outputDir + File.separator + "backup.java");
            instrumentFragment(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumberStr, endLineNumberStr, logFilePath, classesDirectory, r0TestPath, r1TestPath);
        }

    }

    private static boolean isRepairNeeded(int attempt, String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String startLineNumberStr, String endLineNumberStr, String logFilePath, String classesDirectory, String r0TestPath, String r1TestPath) {
        boolean newRepair = false;
        if (noVariableToLog && !Constant.assertStatic) {
            System.out.println(">>> Repairing by asserting static variables");
            Constant.assertStatic = true;
            newRepair = true;
            noVariableToLog = false;
        }

        System.out.println(">>> Checking if instrumented fragment compiles");
        if (!Utils.compileMavenProject(projectDir, outputDir, blockGenDir)) {
            System.out.println("Failed to compile project");
            // Repair time
            if (Constant.exceptionTesting && attempt >= 1) {
                Constant.exceptionTesting = false;
                newRepair = true;
            }

            String logFile = outputDir + File.separator + "compile-all.log";
            try {
                for (String line : Files.readAllLines(Paths.get(logFile))) {
                    if (line.contains("might not have been initialized")) {
                        String pattern = ".* ([^:]+):\\[(\\d+),\\d+\\] (error: )?variable (\\S+) might not have been initialized";
                        Matcher matcher = Pattern.compile(pattern).matcher(line);
                        if (matcher.find()) {
                            String filePath = matcher.group(1);
                            int lineNumber  = Integer.parseInt(matcher.group(2));
                            String varName  = matcher.group(4);

                            try {
                                String targetLine = Files.lines(Paths.get(filePath))
                                        .skip(lineNumber - 1)
                                        .findFirst()
                                        .orElse("Line not found");
                                System.out.println("Line causing compile error: " + targetLine);
                                if (targetLine.contains(Constant.TARGET_STMT_BEFORE)) {
                                    brokenBefore.add(varName);
                                    newRepair = true;
                                } else if (targetLine.contains(Constant.TARGET_STMT_AFTER)) {
                                    brokenAfter.add(varName);
                                    newRepair = true;
                                }
                            } catch (IOException e) {
                                System.err.println("Failed to read file: " + e.getMessage());
                            }
                        } else {
                            System.out.println("Failed to parse compile error: " + line);
                        }

                        if (Constant.exceptionTesting && !newRepair) {
                            // might not have been initialized occurs after the fragment, due to try/catch we added to capture exception
                            Constant.exceptionTesting = false;
                            newRepair = true;
                            System.out.println(">>> Repairing might not have been initialized error by disabling exception testing");
                        }
                    } else if (line.contains("unreachable statement")) {
                        wrapInTry = true;
                        newRepair = true;
                        System.out.println(">>> Repairing unreachable statement");
                    } else if (line.contains("bad return type in lambda expression")) {
                        wrapInTry = true;
                        newRepair = true;
                        System.out.println(">>> Repairing unreachable statement");
                    } else if (line.contains("lambda body is neither value nor void compatible")) {
                        wrapInTry = true;
                        newRepair = true;
                        System.out.println(">>> Repairing unreachable statement");
                    } else if (line.contains("incompatible types:") || (line.contains("required, but") && line.contains("found")) || line.contains("cannot find symbol") || line.contains("wrong number of type arguments")) {
                        TypeResolverUtil.useSpoonFirst = true;
                        newRepair = true;
                        System.out.println(">>> Repairing type error");
                    }  else if (line.contains("reference to ") && line.endsWith("is ambiguous")) {
                        Constant.removeFQNType.add(line.split("reference to ")[1].split(" ")[0]);
                        newRepair = true;
                        System.out.println(">>> Repairing type conflict");
                    }
                }
            } catch (Exception ex) {
                System.out.println("Fail to repair compile error");
                ex.printStackTrace();
            }

            if (!newRepair) {
                System.out.println("No known compile error found, unable to repair");
                System.exit(0);
            }
        } else if (!newRepair) {
            System.exit(0);
        }
        return newRepair;
    }

    /**
     * Instrument the Java source file so that when tests execute, the
     * added instrumentations can generate inline tests with variable
     * values in target statements.
     */
    public static void instrumentFragment(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String startLineNumberStr, String endLineNumberStr, String logFilePath, String classesDirectory, String r0TestPath, String r1TestPath) throws IOException {
        System.out.println("wrapInTry is set to " + wrapInTry);

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

        VariableFinder.findVariables(cu, ctx, startLineNumber, endLineNumber, false);
        VariableFinder.findClassName(cu, ctx, startLineNumber, endLineNumber, false);

        // Find assigned variable that is not declared
        System.out.println("Computing variables that are assigned only. We might need to capture their original values.");
        Set<String> variablesBefore = new HashSet<>(ctx.logVariablesBefore);
        Set<String> logVariablesAfter = new HashSet<>(ctx.logVariablesAfter);
        Set<String> unassignedVariables = ctx.unassignedVariables;
        Set<String> declaredVariables = ctx.declaredVariables;
        Set<String> declaredVariablesTopLevel = ctx.declaredVariablesTopLevel;
        Set<String> uninitializedVariables = ctx.uninitializedVariables;
        System.out.println("Assigned in fragment, init before fragment variables: " + ctx.assignedVariables);
        System.out.println("Assigned in fragment, init in fragment variables: " + unassignedVariables);
        System.out.println("Declared in block variables: " + declaredVariables);
        System.out.println("Declared in block (top level) variables: " + declaredVariablesTopLevel);
        System.out.println("Variables not initialized before fragment: " + uninitializedVariables);
        ctx.assignedVariables.removeAll(variablesBefore); // already logged
        ctx.assignedVariables.removeAll(declaredVariables); // declared in block, no need to log
        ctx.assignedVariables.removeAll(unassignedVariables); // not assigned, no need to log
        System.out.println("Declared and assigned outside target fragment, re-assigned but not used in target fragment variables: " + ctx.assignedVariables);

        // Capture variables before fragment, e.g.,
        // All variables used in the fragment,
        // minus variables declared in the fragment (because they are not defined before the block),
        // minus variables that are not initialized before the fragment (because they are not initialized before the block)
        System.out.println(">>>>> logVariablesBefore");
        System.out.println("Initial variables before: " + variablesBefore);
        System.out.println("Removing variables declared in fragment");
        ctx.logVariablesBefore.removeAll(declaredVariables); // variable is declared in the block, so we can't capture value before the black
        System.out.println("Removing variables not initialized before fragment");
        ctx.logVariablesBefore.removeAll(uninitializedVariables); // variable is not initialized before fragment, so we can't capture value before the black
        System.out.println("Final variables before: " + ctx.logVariablesBefore);
        System.out.println("<<<<< logVariablesBefore");

        BlockStmt blockStmt = new BlockStmt();

        // noDeclarationAllowed: true means we cannot assert variables declared in the block
        // either because we wrap in try/finally to fix unreachable statement, or return statement in fragment.
        boolean noDeclarationAllowed = wrapInTry;

        cu.findAll(Statement.class).forEach(stmt -> {
            stmt.getRange().ifPresent(range -> {
                if (range.begin.line >= ctx.startLineNumber && range.end.line <= ctx.endLineNumber) {
                    // Only visit top-level statements in range (avoid double-visiting children)
                    boolean hasAncestorInRange = stmt.findAncestor(Statement.class)
                            .flatMap(Node::getRange)
                            .map(pr -> pr.begin.line >= ctx.startLineNumber && pr.end.line <= ctx.endLineNumber)
                            .orElse(false);

                    if (!hasAncestorInRange) {
                        blockStmt.addStatement(stmt.clone());
                    }
                }
            });
        });

        BreakReplacementVisitor replacementVisitor = new BreakReplacementVisitor(true, null, null, null);
        blockStmt.clone().accept(replacementVisitor, null);
        noDeclarationAllowed = noDeclarationAllowed || replacementVisitor.shouldBeReplaced;
        // If noDeclarationAllowed is true, that means fragment contains return statements
        // BDK will wrap code with return statements into try/finally block, so we can't assert values that are declared in the block
        System.out.println("Check for return/break/continue in");
        System.out.println(blockStmt);

        // Capture variables after fragment, e.g.,
        // All variables changed (assigned),
        // minus variables declared in the inner block of the fragment (because they are not defined in the outer block)
        Set<String> declaredVariablesNonTopLevel = new HashSet<>(declaredVariables);
        if (!noDeclarationAllowed) {
            System.out.println("No return statements in target fragment. Can assert variables declared in top level");
            // we can assert values that are declared in the block if there is no return statement in the block
            declaredVariablesNonTopLevel.removeAll(ctx.declaredVariablesTopLevel);
        } else {
            System.out.println("There are return statements in target fragment. Can't assert variables declared in the block");
        }
        ctx.logVariablesAfter.removeAll(declaredVariablesNonTopLevel); // declared in block (non-top level), can't write block tests
        System.out.println("Variables after: " + ctx.logVariablesAfter);

        System.out.println("VariablesBefore cause compile error before repair: " + brokenBefore);
        System.out.println("VariablesAfter cause compile error before repair: " + brokenAfter);

        ctx.logVariablesBefore.removeAll(brokenBefore);
        ctx.assignedVariables.removeAll(brokenBefore);
        ctx.logVariablesAfter.removeAll(brokenAfter);

        Map<String, Object> log = new HashMap<>();
        log.put("VariablesBefore", ctx.logVariablesBefore); // capture before
        log.put("VariablesBeforeCount", ctx.logVariablesBefore.size());
        log.put("VariablesAfter", ctx.logVariablesAfter); // capture after
        log.put("VariablesAfterCount", ctx.logVariablesAfter.size());
        log.put("VariablesChanged", logVariablesAfter);
        log.put("VariablesChangedCount", logVariablesAfter.size());
        log.put("VariablesUsedInFragment", variablesBefore);
        log.put("VariablesUsedInFragmentCount", variablesBefore.size());
        log.put("VariablesReassignedInFragment", ctx.assignedVariables);
        log.put("VariablesReassignedInFragmentCount", ctx.assignedVariables.size());
        log.put("VariablesFirstInitializedInFragment", unassignedVariables);
        log.put("VariablesFirstInitializedInFragmentCount", unassignedVariables.size());
        log.put("VariablesUsedInFragmentButNotInitializedBeforeFragment", uninitializedVariables);
        log.put("VariablesUsedInFragmentButNotInitializedBeforeFragmentCount", uninitializedVariables.size());
        log.put("VariablesDeclaredInFragment", declaredVariables);
        log.put("VariablesDeclaredInFragmentCount", declaredVariables.size());
        log.put("VariablesDeclaredTopLevelInFragment", declaredVariablesTopLevel);
        log.put("VariablesDeclaredTopLevelInFragmentCount", declaredVariablesTopLevel.size());
        log.put("VariablesDeclaredInnerLevelInFragment", declaredVariablesNonTopLevel);
        log.put("VariablesDeclaredInnerLevelInFragmentCount", declaredVariablesNonTopLevel.size());
        log.put("MethodsRequiredMocking", ctx.mockMethods);
        log.put("MethodsRequiredMockingCount", ctx.mockMethods.size());
        log.put("VariablesBeforeCausingCompileError", brokenBefore);
        log.put("VariablesBeforeCausingCompileErrorCount", brokenBefore.size());
        log.put("VariablesAfterCausingCompileError", brokenAfter);
        log.put("VariablesAfterCausingCompileErrorCount", brokenAfter.size());

        Utils.writeToJSON(log, Paths.get(logFilePath).getParent().toString() + "/instrumentation.json");


        StatementInserter inserter = new StatementInserter(Paths.get(absPathToSrc));

        if (ctx.endWithReturn || wrapInTry) ctx.message = "end-return";
        Statement startLogStmt = Utils.buildPromptStatement(Constant.TARGET_STMT_START, ctx);
        ctx.message = "";
        inserter.insertStatementAtLine(startLineNumber, startLogStmt, true);


        for (String variable : ctx.logVariablesBefore) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_BEFORE, variable, ctx.allVariablesWithType.getOrDefault(variable, null), ctx);
            inserter.insertStatementAtLine(startLineNumber, logStmt, true);
        }

        for (String variable : ctx.assignedVariables) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_BEFORE, variable,  ctx.allVariablesWithType.getOrDefault(variable, null), ctx);
            inserter.insertStatementAtLine(startLineNumber, logStmt, true);
        }

        BlockStmt afterBlock = new BlockStmt();
        BlockStmt endBlock = new BlockStmt();

        Statement endLogStmt = Utils.buildPromptStatement(Constant.TARGET_STMT_END, ctx);
        if (!ctx.endWithReturn && !wrapInTry)  inserter.insertStatementAtLine(endLineNumber, endLogStmt, false);
        endBlock.addStatement(endLogStmt);
        afterBlock.addStatement(endLogStmt);

        for (String variable : ctx.logVariablesAfter) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.allVariablesWithType.getOrDefault(variable, null), ctx);
            if (!ctx.endWithReturn && !wrapInTry)  inserter.insertStatementAtLine(endLineNumber, logStmt, false);
            afterBlock.addStatement(0, logStmt);
        }

        if (ctx.logVariablesAfter.isEmpty()) {
            System.out.println("logVariablesAfter is empty, capture all logVariablesBefore and assignedVariables");
            if (ctx.logVariablesBefore.isEmpty() && ctx.assignedVariables.isEmpty()) {
                noVariableToLog = true;
            }

            for (String variable : ctx.logVariablesBefore) {
                Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.allVariablesWithType.getOrDefault(variable, null), ctx);
                if (!ctx.endWithReturn && !wrapInTry) inserter.insertStatementAtLine(endLineNumber, logStmt, false);
                afterBlock.addStatement(0, logStmt);
            }

            for (String variable : ctx.assignedVariables) {
                Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.allVariablesWithType.getOrDefault(variable, null), ctx);
                if (!ctx.endWithReturn && !wrapInTry) inserter.insertStatementAtLine(endLineNumber, logStmt, false);
                afterBlock.addStatement(0, logStmt);
            }
        }

        for (String variable : ctx.mockMethods) {
            Statement logStmt = Utils.buildLogStatement(Constant.MOCKING, variable, ctx);
            if (!ctx.endWithReturn && !wrapInTry)  inserter.insertStatementAtLine(endLineNumber, logStmt, false);
            endBlock.addStatement(0, logStmt);
            afterBlock.addStatement(0, logStmt);
        }

        Statement coverageStmt = Utils.buildPromptStatement(Constant.CHECK_COVERAGE, ctx);
        inserter.insertCoverageStatement(endLineNumber, coverageStmt, wrapInTry ? afterBlock : null, endBlock, ctx);

        // Need to put it here (cannot put it in the beginning), because it will affect the statement inserter above
        // Add braces to avoid issues when inserting fragments with unbraced if/else/for/while statements
        new BracesVisitor().visit(inserter.cu, null);

        BreakReplacementVisitor replacer = new BreakReplacementVisitor(true, afterBlock, endBlock, ctx);
        inserter.cu.findAll(Statement.class).forEach(stmt -> {
            stmt.getRange().ifPresent(range -> {
                if (range.begin.line >= ctx.startLineNumber && range.end.line <= ctx.endLineNumber) {
                    // Guard: skip detached nodes
                    if (!stmt.findRootNode().equals(inserter.cu)) {
                        System.out.println("Skipping detached node: " + stmt);
                        return;
                    }

                    // Only visit top-level statements in range (avoid double-visiting children)
                    boolean hasAncestorInRange = stmt.findAncestor(Statement.class)
                            .flatMap(Node::getRange)
                            .map(pr -> pr.begin.line >= ctx.startLineNumber && pr.end.line <= ctx.endLineNumber)
                            .orElse(false);

                    if (!hasAncestorInRange) {
                        System.out.println("Sending statement to replacer: " + stmt);
                        stmt.accept(replacer, null);
                    }
                }
            });
        });

        if (!Constant.useFQN) {
            inserter.cu.addImport(new ImportDeclaration(Constant.LOG_CLASS_IMPORT, false, false));
        }

        FileWriter writer;
        writer = new FileWriter(absPathToSrc);
        writer.write(inserter.print());
        writer.close();
    }
}
