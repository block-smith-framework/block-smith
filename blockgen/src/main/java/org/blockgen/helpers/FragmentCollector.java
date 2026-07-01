package org.blockgen.helpers;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.utils.Pair;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.types.TypeResolver;
import org.blockgen.types.TypeResolverUtil;
import org.blockgen.Utils;
import org.blockgen.visitors.BracesVisitor;
import org.blockgen.visitors.BreakReplacementVisitor;
import org.blockgen.visitors.ControlFlowInstrumentVisitor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class FragmentCollector {

    private String srcPath = "";
    public boolean wrapInTry = false;
    public static boolean noVariableToLog = false;

    public void extract(String projectDir, String srcPath, String outputDir, String blockGenDir, int startLine, int endLine, String newClassName, String newMethodName, Context ctx) {
        System.out.println("Extracting code fragment from " + srcPath + " lines " + startLine + "-" + endLine);
        System.out.println("wrapInTry is set to " + wrapInTry);
        this.srcPath = srcPath;
        try {
            CompilationUnit cu = StaticJavaParser.parse(Paths.get(srcPath));
            MethodCallRemover.remove(cu, true, startLine, endLine, ctx.mockMethods);
            extract(cu, ctx, startLine, endLine, newClassName, newMethodName);
            convertToStatic(srcPath);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Failed to extract code fragment.");
            System.exit(1);
        }
    }

    private void buildMethod(CompilationUnit cu, ClassOrInterfaceDeclaration klass, Context ctx, String newMethodName) {
        MethodDeclaration method = new MethodDeclaration();

        NodeList<TypeParameter> mergedTypeParams = new NodeList<>();
        mergedTypeParams.addAll(ctx.classGenericTypes);
        mergedTypeParams.addAll(ctx.genericTypes);

        if (!mergedTypeParams.isEmpty()) {
            // Add type parameters to method signature.
            method.setTypeParameters(mergedTypeParams);
        }

        if (!ctx.thrownExceptions.isEmpty()) {
            method.setThrownExceptions(ctx.thrownExceptions);
        }

        if (System.getenv("CAPTURE_ASSIGNED_VARIABLES") != null && System.getenv("CAPTURE_ASSIGNED_VARIABLES").equals("true")) {
            // Capture all assigned variables, even those that are declared outside fragment and not referenced in fragment.
            System.out.println("CAPTURE_ASSIGNED_VARIABLES is set to true, capturing all assigned variables");
            ctx.logVariablesBefore.addAll(ctx.assignedVariables);
            ctx.logVariablesWithTypeBefore.putAll(ctx.assignedVariablesWithType);
        }

        Set<String> variablesBefore = new HashSet<>(ctx.logVariablesBefore);
        Set<String> logVariablesAfter = new HashSet<>(ctx.logVariablesAfter);
        Set<String> declaredVariables = ctx.declaredVariables;
        Set<String> declaredVariablesTopLevel = ctx.declaredVariablesTopLevel;

        // Variables assigned in fragment that are declared outside fragment, but not referenced to in fragment
        ctx.assignedVariables.removeAll(ctx.logVariablesBefore); // already logged
        ctx.assignedVariables.removeAll(ctx.declaredVariables); // declared in block, no need to log

        // Capture variables before fragment, e.g.,
        // All variables used in the fragment,
        // minus variables declared in the fragment (because they are not defined before the block),
        // minus variables that are not initialized before the fragment (because they are not initialized before the block)
        System.out.println(">>>>> logVariablesBefore");
        System.out.println("Initial variables before: " + variablesBefore);
        System.out.println("Removing variables declared in fragment");
        ctx.logVariablesBefore.removeAll(declaredVariables); // variable is declared in the block, so we can't capture value before the block
        System.out.println("Removing variables not initialized before fragment");
        ctx.logVariablesBefore.removeAll(ctx.uninitializedVariables); // variable is not initialized before fragment, so we can't capture value before the block
        System.out.println("Final variables before: " + ctx.logVariablesBefore);
        System.out.println("<<<<< logVariablesBefore");

        // Add parameters to method
        for (String variable : ctx.logVariablesBefore) {
            String type = ctx.logVariablesWithTypeBefore.get(variable);
            System.out.println("Adding parameter for variable used in fragment: " + variable + " with type " + type);
            method.addParameter(type, variable);
        }

        // TODO: determine if we should initialize uninitializedVariables here

        BlockStmt blockStmt = new BlockStmt();

        // All variables used in fragments, but not declared in the fragment, and not in input
        Set<String> variablesRequireInitialization = new HashSet<>(ctx.assignedVariables);
        variablesRequireInitialization.addAll(ctx.uninitializedVariables);
        variablesRequireInitialization.removeAll(ctx.logVariablesBefore);
        variablesRequireInitialization.removeAll(ctx.declaredVariables);
        for (String variable : variablesRequireInitialization) {
            String variableName = variable;
            Set<String> renameSet = ctx.rename.stream().map(Node::toString).collect(Collectors.toSet());
            if (renameSet.contains(variable)) {
                variableName = Utils.rename(variableName);
            }
            System.out.println("Variable requires initialization: " + variable + " -> " + variableName);
            VariableDeclarator initVar = new VariableDeclarator(TypeResolverUtil.getTypeFromStr(ctx.assignedVariablesWithType.getOrDefault(variable, ctx.uninitializedVariablesWithType.get(variable))), variableName, TypeResolverUtil.getTypeDefaultValue(ctx.assignedVariablesWithType.getOrDefault(variable, ctx.uninitializedVariablesWithType.get(variable))));
            blockStmt.addStatement(new VariableDeclarationExpr(initVar));
        }

        // Log "target-statement-start"
        if (ctx.endWithReturn || wrapInTry) ctx.message = "end-return";
        Statement logStartStmt = Utils.buildLogStatement(Constant.TARGET_STMT_START, null, ctx);
        blockStmt.addStatement(logStartStmt);
        ctx.message = "";

        // Log variables before target statement.
        for (String variable : ctx.logVariablesBefore) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_BEFORE, variable, ctx.logVariablesWithTypeBefore.get(variable), ctx);
            blockStmt.addStatement(logStmt);
        }

        for (String variable : variablesRequireInitialization) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_BEFORE, variable, ctx.assignedVariablesWithType.getOrDefault(variable, ctx.uninitializedVariablesWithType.get(variable)), ctx);
            blockStmt.addStatement(logStmt);
        }

        // noDeclarationAllowed: true means we cannot assert variables declared in the block
        // either because we wrap in try/finally to fix unreachable statement, or return statement in fragment.
        boolean noDeclarationAllowed = wrapInTry;
        TryStmt fragmentTryStmt = new TryStmt();
        BlockStmt fragmentTryBody = new BlockStmt();
        if (wrapInTry) {
            fragmentTryStmt.setTryBlock(fragmentTryBody);
            blockStmt.addStatement(fragmentTryStmt);
        }

        // Insert fragment here
        System.out.println("Inserting target fragment");
        cu.findAll(Statement.class).forEach(stmt -> {
            stmt.getRange().ifPresent(range -> {
                if (range.begin.line >= ctx.startLineNumber && range.end.line <= ctx.endLineNumber) {
                    // Only visit top-level statements in range (avoid double-visiting children)
                    boolean hasAncestorInRange = stmt.findAncestor(Statement.class)
                            .flatMap(Node::getRange)
                            .map(pr -> pr.begin.line >= ctx.startLineNumber && pr.end.line <= ctx.endLineNumber)
                            .orElse(false);

                    if (!hasAncestorInRange) {
                        if (wrapInTry)
                            fragmentTryBody.addStatement(stmt.clone());
                        else
                            blockStmt.addStatement(stmt.clone());
                    }
                }
            });
        });

//        if (wrapInTry) {
//            fragmentTryBody.accept(new ControlFlowInstrumentVisitor("BLOCKGEN_FLOW_INTERNAL"), null);
//        } else {
//            blockStmt.accept(new ControlFlowInstrumentVisitor("BLOCKGEN_FLOW_INTERNAL"), null);
//        }

        // Need to put it here (cannot put it in the beginning), because it will affect the fragment extraction above
        // Add braces to avoid issues when inserting fragments with unbraced if/else/for/while statements
        new BracesVisitor().visit(blockStmt, null);

        BreakReplacementVisitor replacementVisitor = new BreakReplacementVisitor(false, null, null, null);
        blockStmt.clone().accept(replacementVisitor, null);
        noDeclarationAllowed = noDeclarationAllowed || replacementVisitor.shouldBeReplaced;
        // If noDeclarationAllowed is true, that means fragment contains return statements
        // BDK will wrap code with return statements into try/finally block, so we can't assert values that are declared in the block

        System.out.println("Capturing variables after fragment");
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
        ctx.logVariablesAfter.removeAll(declaredVariablesNonTopLevel); // declared in inner block, can't write block tests

        BlockStmt afterBlock = new BlockStmt();
        BlockStmt endBlock = new BlockStmt();
        for (String variable : ctx.logVariablesAfter) {
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.logVariablesWithTypeAfter.get(variable), ctx);
            if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logStmt);
            afterBlock.addStatement(logStmt);
        }

        // TODO: technically we can also check variables that are declared in the fragment
        if (ctx.logVariablesAfter.isEmpty()) {
            System.out.println("logVariablesAfter is empty, capture all logVariablesBefore and variablesRequireInitialization");

            if (ctx.logVariablesBefore.isEmpty() && variablesRequireInitialization.isEmpty()) {
                noVariableToLog = true;
            }

            for (String variable : ctx.logVariablesBefore) {
                if (ctx.givenMethods.contains(variable)) continue;
                Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.logVariablesWithTypeBefore.get(variable), ctx);
                if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logStmt);
                afterBlock.addStatement(logStmt);
            }

            for (String variable : variablesRequireInitialization) {
                if (ctx.givenMethods.contains(variable)) continue;
                Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.assignedVariablesWithType.getOrDefault(variable, ctx.uninitializedVariablesWithType.get(variable)), ctx);
                if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logStmt);
                afterBlock.addStatement(logStmt);
            }
        } else if (!ctx.invokedVariablesWithType.isEmpty() && (!ctx.logVariablesBefore.isEmpty() || !variablesRequireInitialization.isEmpty())) {
            System.out.println("invokedVariables are: " + ctx.invokedVariablesWithType.keySet());
            for (String variable : ctx.invokedVariablesWithType.keySet()) {
                if (ctx.logVariablesBefore.contains(variable)) {
                    if (ctx.givenMethods.contains(variable)) continue;
                    Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.logVariablesWithTypeBefore.get(variable), ctx);
                    if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logStmt);
                    afterBlock.addStatement(logStmt);
                } else if (variablesRequireInitialization.contains(variable)) {
                    if (ctx.givenMethods.contains(variable)) continue;
                    Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_AFTER, variable, ctx.assignedVariablesWithType.getOrDefault(variable, ctx.uninitializedVariablesWithType.get(variable)), ctx);
                    if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logStmt);
                    afterBlock.addStatement(logStmt);
                }
            }
        }

        // Log "target-statement-end"
        Statement logFlowStmt = Utils.buildLogStatement(Constant.TARGET_STMT_FLOW, null, ctx);
        if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logFlowStmt);
        afterBlock.addStatement(logFlowStmt);
        endBlock.addStatement(logFlowStmt);

        Statement logEndStmt = Utils.buildLogStatement(Constant.TARGET_STMT_END, null, ctx);
        if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logEndStmt);
        afterBlock.addStatement(logEndStmt);
        endBlock.addStatement(logEndStmt);

        // Log "check-mocking"
        for (String variable : ctx.mockMethods) {
            Statement logMockStmt = Utils.buildLogStatement(Constant.MOCKING, variable, ctx);
            if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(logMockStmt);
            afterBlock.addStatement(logMockStmt);
            endBlock.addStatement(logMockStmt);
        }

        // Log "check-coverage"
        Statement checkCoverage = Utils.buildLogStatement(Constant.CHECK_COVERAGE, null, ctx);
        if (!ctx.endWithReturn && !wrapInTry) blockStmt.addStatement(checkCoverage);
        afterBlock.addStatement(checkCoverage);
        endBlock.addStatement(checkCoverage);

        if (wrapInTry) {
            fragmentTryStmt.setFinallyBlock(afterBlock);
        }

        BreakReplacementVisitor replacer = new BreakReplacementVisitor(false, afterBlock, endBlock, ctx);
        blockStmt.accept(replacer, null);

        blockStmt.accept(new ControlFlowInstrumentVisitor("BLOCKGEN_FLOW_INTERNAL"), null);

        if (Constant.exceptionTesting) {
            if (wrapInTry) {
                BlockStmt exceptBlock = endBlock.clone();
                exceptBlock.addStatement(0, Utils.buildLogStatement(Constant.TARGET_STMT_THROW, Constant.EXCEPTION_VARIABLE + ".getClass().getName()", ctx));
                CatchClause catchClause = new CatchClause(new Parameter(new ClassOrInterfaceType(null, "Exception"), Constant.EXCEPTION_VARIABLE), exceptBlock);
                fragmentTryStmt.setCatchClauses(new NodeList<>(catchClause));

                method.setBody(blockStmt);
            } else {
                TryStmt tryStmt = new TryStmt();
                BlockStmt exceptBlock = endBlock.clone();

                tryStmt.setTryBlock(blockStmt);
                exceptBlock.addStatement(0, Utils.buildLogStatement(Constant.TARGET_STMT_THROW, Constant.EXCEPTION_VARIABLE + ".getClass().getName()", ctx));
                CatchClause catchClause = new CatchClause(new Parameter(new ClassOrInterfaceType(null, "Exception"), Constant.EXCEPTION_VARIABLE), exceptBlock);
                tryStmt.setCatchClauses(new NodeList<>(catchClause));

                BlockStmt finalBlock = new BlockStmt();
                finalBlock.addStatement(tryStmt);
                method.setBody(finalBlock);
            }
        } else {
            method.setBody(blockStmt);
        }

        ClassOrInterfaceType sbType = new ClassOrInterfaceType(null, "java.lang.StringBuilder");
        method.getBody().ifPresent(body -> body.addStatement(0, new ExpressionStmt(
                new VariableDeclarationExpr(
                        new VariableDeclarator(
                                sbType,
                                "BLOCKGEN_FLOW_INTERNAL",
                                new ObjectCreationExpr(null, sbType.clone(), new NodeList<>())
                        )
                )
        )));

        method.setName(newMethodName);
        method.setType(new VoidType());
        method.setModifiers(com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
        method.setStatic(true);
        method.addThrownException(new ClassOrInterfaceType(null, "Exception"));
        klass.addMember(method);

        Map<String, Object> log = new HashMap<>();
        log.put("VariablesBefore", ctx.logVariablesBefore); // capture before
        log.put("VariablesBeforeCount", ctx.logVariablesBefore.size());
        log.put("VariablesAfter", ctx.logVariablesAfter); // capture after
        log.put("VariablesAfterCount", ctx.logVariablesAfter.size());
        log.put("VariablesChanged", logVariablesAfter);
        log.put("VariablesChangedCount", logVariablesAfter.size());
        log.put("VariablesUsedInFragment", variablesBefore);
        log.put("VariablesUsedInFragmentCount", variablesBefore.size());
        log.put("VariablesAssignedButDeclaredOutsideAndNotReferencedInFragment", ctx.assignedVariables);
        log.put("VariablesAssignedButDeclaredOutsideAndNotReferencedInFragmentCount", ctx.assignedVariables.size());
        log.put("VariablesUsedInFragmentButNotInitializedBeforeFragment", ctx.uninitializedVariables);
        log.put("VariablesUsedInFragmentButNotInitializedBeforeFragmentCount", ctx.uninitializedVariables.size());
        log.put("VariablesDeclaredInFragment", declaredVariables);
        log.put("VariablesDeclaredInFragmentCount", declaredVariables.size());
        log.put("VariablesDeclaredTopLevelInFragment", declaredVariablesTopLevel);
        log.put("VariablesDeclaredTopLevelInFragmentCount", declaredVariablesTopLevel.size());
        log.put("VariablesDeclaredInnerLevelInFragment", declaredVariablesNonTopLevel);
        log.put("VariablesDeclaredInnerLevelInFragmentCount", declaredVariablesNonTopLevel.size());
        log.put("VariablesRequireInitialization", variablesRequireInitialization);
        log.put("VariablesRequireInitializationCount", variablesRequireInitialization.size());
        log.put("MethodsRequireMocking", ctx.mockMethods);
        log.put("MethodsRequireMockingCount", ctx.mockMethods.size());
        log.put("MethodsRequireRenaming", ctx.rename.stream().map(Node::toString).collect(Collectors.toList()));
        log.put("MethodsRequireRenamingCount", ctx.rename.size());

        Utils.writeToJSON(log, Paths.get(ctx.logPath).getParent().toString() + "/instrumentation.json");
    }


    private void extract(CompilationUnit cu, Context ctx, int startLine, int endLine, String newClassName, String newMethodName) throws Exception {

        // Collect top-level statements in range
        BlockStmt body = new BlockStmt();
        cu.findAll(Statement.class).forEach(stmt -> {
            stmt.getRange().ifPresent(range -> {
                if (range.begin.line >= startLine && range.end.line <= endLine) {
                    boolean hasAncestorInRange = stmt.findAncestor(Statement.class)
                            .flatMap(Node::getRange)
                            .map(pr -> pr.begin.line >= startLine && pr.end.line <= endLine)
                            .orElse(false);

                    if (!hasAncestorInRange) {
                        body.addStatement(stmt.clone());
                    }
                }
            });
        });

        // Build new class
        ClassOrInterfaceDeclaration newClass = new ClassOrInterfaceDeclaration();
        newClass.setName(newClassName);
        newClass.setPublic(true);
        newClass.addConstructor(Modifier.Keyword.PRIVATE);
        buildMethod(cu, newClass, ctx, newMethodName);

        if (!TypeResolverUtil.inferredTypesMap.isEmpty()) {
            expandObjectParameters(newClass, ctx);
        }

        // Wrap in a new CompilationUnit
        CompilationUnit newCu = new CompilationUnit();

        // Package declaration
        newCu.setPackageDeclaration(cu.getPackageDeclaration().orElse(null));

        // TODO: handle inner classes
        newCu.setImports(cu.getImports());

        String packageName = null;
        if (cu.getPackageDeclaration().isPresent()) {
            packageName = cu.getPackageDeclaration().get().getNameAsString();
        }

        Set<String> result = new HashSet<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            cls.getParentNode().ifPresent(parent -> {
                result.add(Utils.getFullName(cls));
            });
        });

        String testedClassName = Paths.get(this.srcPath).getFileName().toString().split(".java")[0];
        result.add(testedClassName);
        for (String klass : result) {
            if (packageName != null) {
                System.out.println("Adding import: " + packageName + "." + klass);
                newCu.addImport(new ImportDeclaration(packageName + "." + klass, true, true));
            } else {
                System.out.println("Adding import: " + klass);
                newCu.addImport(new ImportDeclaration(klass, true, true));
            }
        }

        if (!Constant.useFQN) {
            newCu.addImport(new ImportDeclaration(Constant.LOG_CLASS_IMPORT, false, false));
        }

        newCu.addType(newClass);

        RenameHelper renameHelper = new RenameHelper();
        newClass.accept(renameHelper, ctx);

        FileWriter writer;
        String path = Paths.get(srcPath).getParent().toString() + File.separator + newClassName + ".java";
        System.out.println("Writing extracted fragment to " + path);
        writer = new FileWriter(path);
        writer.write(newCu.toString());
        writer.close();

        // Output non-instrumented version of the extracted fragment for unit-test generation purpose.
        path = "/tmp/NonInstrumented.java";
        writer = new FileWriter(path);
        writer.write(Utils.removeInstrumentation(newCu.clone()));
        writer.close();

        // Output non-instrumented, with main method as driver
        path = "/tmp/NonInstrumentedDriver.java";
        writer = new FileWriter(path);
        writer.write(Utils.useMainMethod(newCu.clone(), newMethodName));
        writer.close();
    }

    public static void convertToStatic(String srcPath) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(Paths.get(srcPath));
        boolean changedToPublic = false;

        String fileNameWithoutExtension = Paths.get(srcPath).getFileName().toString().replaceAll("\\.java$", "");

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean isTopLevel = cls.getParentNode()
                    .map(p -> p instanceof CompilationUnit)
                    .orElse(false);

            if (isTopLevel) {
                // Only make the top-level class public if its name matches the filename
                if (cls.getNameAsString().equals(fileNameWithoutExtension)) {
                    if (!cls.isPublic()) {
                        cls.setPrivate(false);
                        cls.setProtected(false);
                        cls.setPublic(true);
                        changedToPublic = true;
                    }
                }
            } else {
                // Inner/nested classes can all be made public safely
                if (!cls.isPublic()) {
                    cls.setPrivate(false);
                    cls.setProtected(false);
                    cls.setPublic(true);
                    changedToPublic = true;
                }
            }
        }

        // All static method should be public
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class, MethodDeclaration::isStatic)) {
            Optional<String> name = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString);
            if (name.isPresent() && !method.isPublic()) {
                method.setPrivate(false);
                method.setProtected(false);
                method.setPublic(true);
                changedToPublic = true;
            }
        }

        // All field should be public
        for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
            if (!fd.isPublic()) {
                fd.setPrivate(false);
                fd.setProtected(false);
                fd.setPublic(true);
                changedToPublic = true;
            }
        }

        // All enum should be public
        for (EnumDeclaration ed : cu.findAll(EnumDeclaration.class)) {
            boolean isTopLevel = ed.getParentNode()
                    .map(p -> p instanceof CompilationUnit)
                    .orElse(false);


            if (isTopLevel) {
                // Only make the top-level enum public if its name matches the filename
                if (ed.getNameAsString().equals(fileNameWithoutExtension)) {
                    if (!ed.isPublic()) {
                        ed.setPrivate(false);
                        ed.setProtected(false);
                        ed.setPublic(true);
                        changedToPublic = true;
                    }
                }
            } else {
                // Inner/nested enum can all be made public safely
                if (!ed.isPublic()) {
                    ed.setPrivate(false);
                    ed.setProtected(false);
                    ed.setPublic(true);
                    changedToPublic = true;
                }
            }
        }

        for (ConstructorDeclaration constructor : cu.findAll(ConstructorDeclaration.class)) {
            boolean inEnum = constructor.findAncestor(EnumDeclaration.class).isPresent();
            if (!inEnum && !constructor.isPublic()) {
                constructor.setPrivate(false);
                constructor.setProtected(false);
                constructor.setPublic(true);
                changedToPublic = true;
            }
        }

        if (changedToPublic) {
            try {
                Path path = Paths.get(srcPath);
                Files.write(path, cu.toString().getBytes());
                System.out.println("Modified static to public... Writing to " + srcPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String getClassName(String classWithPackage) {
        String[] tokens = classWithPackage.split("\\.");
        return tokens[tokens.length - 1];
    }

    public static void expandObjectParameters(ClassOrInterfaceDeclaration clazz, Context ctx) {
        MethodDeclaration original = clazz.getMethods().get(0);
        String originalName = original.getNameAsString();

        NodeList<Parameter> params = original.getParameters();
        List<List<String>> candidatesPerParam = new ArrayList<List<String>>();

        for (Parameter param : params) {
            String typeName = param.getType().asString();
            if (typeName.equals("Object") || typeName.equals("java.lang.Object")) {
                Set<String> inferred = TypeResolverUtil.inferredTypesMap.getOrDefault(param.getNameAsString(), new HashSet<>());
                // Handle alias (Object o = x), we can inferredTypes for both x and o
                for (Map.Entry<String, String> entry : ctx.variableToAssignment.entrySet()) {
                    if (param.getNameAsString().equals(entry.getValue()) || param.getNameAsString().equals(Utils.rename(entry.getValue()))) {
                        if (TypeResolverUtil.inferredTypesMap.containsKey(entry.getKey())) {
                            inferred.addAll(TypeResolverUtil.inferredTypesMap.get(entry.getKey()));
                        }
                    }
                }

                if (inferred != null && !inferred.isEmpty()) {
                    List<String> candidates = new ArrayList<String>(inferred);
                    candidates.add(null); // null = keep as Object
                    candidatesPerParam.add(candidates);
                } else {
                    candidatesPerParam.add(Collections.singletonList(null)); // null = keep Object, no suffix
                }
            } else {
                candidatesPerParam.add(Collections.singletonList(null)); // null = keep as-is, no suffix
            }
        }

        // Skip if no Object parameters have inferred types (nothing to expand)
        boolean hasExpansion = false;
        for (List<String> candidates : candidatesPerParam) {
            if (candidates.size() > 1 || candidates.get(0) != null) {
                hasExpansion = true;
                break;
            }
        }
        if (!hasExpansion) {
            return;
        }

        List<List<String>> combinations = Utils.cartesianProduct(candidatesPerParam);

        for (List<String> combination : combinations) {
            boolean allNull = true;
            for (String s : combination) {
                if (s != null) { allNull = false; break; }
            }
            if (allNull) continue;

            MethodDeclaration cloned = original.clone();
            NodeList<Parameter> clonedParams = cloned.getParameters();

            // Each entry: [origName, newParamName] for prepend statements
            List<String[]> prependEntries = new ArrayList<String[]>();

            // Build name suffix from non-null entries only
            StringBuilder nameSuffix = new StringBuilder();
            for (int i = 0; i < combination.size(); i++) {
                String newType = combination.get(i);
                if (newType != null) {
                    String newTypeName = newType.replaceAll("[<>,\\s]+", "_").replaceAll("\\.", "_").replaceAll("_+$", "").replaceAll("\\?", "Q");
                    nameSuffix.append("_").append(newTypeName);

                    Parameter param = clonedParams.get(i);
                    String origName = param.getNameAsString();

                    Set<String> renameSet = ctx.rename.stream().map(Node::toString).collect(Collectors.toSet());
                    if (renameSet.contains(origName)) {
                        origName = Utils.rename(origName);
                    }

                    String newParamName = origName + "_" + newTypeName;

                    param.setName(newParamName);
                    param.setType(new com.github.javaparser.ast.type.ClassOrInterfaceType(newType));

                    // Record: Object <origName> = <newParamName>;
                    prependEntries.add(new String[]{ origName, newParamName });
                }
            }

            /*
            Why we need to cast to Object:
            suppose we have: void extracted(Object val):
            if (val instanceof BigDecimal) {
                val = ((BigDecimal) val).doubleValue();
            }
            We will provide type hint:
            void extracted(BigDecimal val):
            if (val instanceof BigDecimal) {
                val = ((BigDecimal) val).doubleValue(); // <- we can't assign double to BigDecimal
            }
            Solution:
            void extracted(BigDecimal val_BigDecimal):
            Object val = val_BigDecimal;
            if (val instanceof BigDecimal) {
                val = ((BigDecimal) val).doubleValue();
            }
             */
            cloned.getBody().ifPresent(body -> {
                for (int i = prependEntries.size() - 1; i >= 0; i--) {
                    String origName    = prependEntries.get(i)[0];
                    String newParamName = prependEntries.get(i)[1];

                    VariableDeclarator declarator = new VariableDeclarator(
                            new com.github.javaparser.ast.type.ClassOrInterfaceType("Object"),
                            origName,
                            new com.github.javaparser.ast.expr.NameExpr(newParamName)
                    );
                    body.addStatement(0, new com.github.javaparser.ast.stmt.ExpressionStmt(
                            new com.github.javaparser.ast.expr.VariableDeclarationExpr(declarator)
                    ));
                }
            });

            cloned.setName(originalName + nameSuffix.toString());
            clazz.addMember(cloned);
        }
    }
}
