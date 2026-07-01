package org.blockgen.visitors;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.Utils;
import org.blockgen.types.SpoonResolver;
import org.blockgen.types.TypeResolverUtil;

public class VariableFindingVisitor extends VoidVisitorAdapter<Context> {

    @Override
    public void visit(final AssignExpr n, final Context ctx) {
        Expression target = n.getTarget();
        boolean isArrayAccess = false;
        while (target instanceof ArrayAccessExpr) {
            target = ((ArrayAccessExpr) target).getName();
            isArrayAccess = true;
        }

        if (!isLocalVariable(target.toString(), ctx)) {
            // (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr())
            if (target.isNameExpr() || (target.isFieldAccessExpr())) {
                if (isPreviouslyAssigned(target.toString(), n)) {
                    System.out.println("Variable " + target + " is assigned at this point");
                    ctx.assignedVariables.add(target.toString());
                    recordVariableType(ctx, target);
                } else {
                    System.out.println("Variable " + target + " is not assigned at this point");
                    ctx.unassignedVariables.add(target.toString());
                    ctx.uninitializedVariables.add(target.toString());
                    recordVariableType(ctx, target);
                }
            }

            if (isArrayAccess) {
                // (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr())
                if (target.isNameExpr() || (target.isFieldAccessExpr())) {
                    System.out.println("AssignExpr with ArrayAccessExpr: add logVariablesBefore and logVariablesAfter - " + target);
                    ctx.logVariablesBefore.add(target.toString());
                    ctx.logVariablesAfter.add(target.toString());
                    recordVariableType(ctx, target);
                }
            } else {
                System.out.println("regular AssignExpr: add logVariablesAfter - " + target);
                ctx.logVariablesAfter.add(target.toString());
                recordVariableType(ctx, target);
            }
        }
        // skip: n.getTarget().accept(this, ctx);
        if (isArrayAccess) {
            System.out.println("Need to visit index (maybe there are variables in it) - " + n.getTarget().asArrayAccessExpr().getIndex());
            n.getTarget().asArrayAccessExpr().getIndex().accept(this, ctx);
        }

        n.getValue().accept(this, ctx);
    }

    @Override
    public void visit(UnaryExpr n, final Context ctx) {
        Expression target = n.getExpression();

        if (target.isNameExpr()) {
            System.out.println("UnaryExpr: add logVariablesAfter - " + target);
            ctx.logVariablesAfter.add(target.toString());
            recordVariableType(ctx, target);
        }

        super.visit(n, ctx);
    }

    @Override
    public void visit(final VariableDeclarator n, final Context ctx) {
        System.out.println("Variable " + n.getNameAsString() + " is declared");
        ctx.declaredVariables.add(n.getNameAsString());
        ctx.declaredVariablesWithType.put(n.getNameAsString(), n.getTypeAsString());
        ctx.allVariablesWithType.put(n.getNameAsString(), n.getTypeAsString());

        if (ctx.locals.size() > 0) {
            ctx.locals.peek().add(n.getNameAsString());
        }
        n.getInitializer().ifPresent(l -> {
            if (!isLocalVariable(n.getNameAsString(), ctx)) {
                ctx.logVariablesAfter.add(n.getNameAsString());
                ctx.allVariablesWithType.put(n.getNameAsString(), n.getTypeAsString());
            }
            l.accept(this, ctx);
        });
        // skip: n.getName().accept(this, ctx);
        // skip: n.getType().accept(this, ctx);
    }

    public boolean containLocalVariable(Node n, Context ctx) {
        if (ctx.locals.size() == 0) {
            return false;
        }
        if (n instanceof NameExpr) {
            return isLocalVariable(((NameExpr) n).getNameAsString(), ctx);
        }
        boolean res = false;
        for (Node child : n.getChildNodes()) {
            res = res || containLocalVariable(child, ctx);
        }
        return res;
    }

    @Override
    public void visit(final FieldAccessExpr n, final Context ctx) {
        // skip: n.getName().accept(this, ctx);
        // skip: n.getScope().accept(this, ctx);
        // skip: n.getTypeArguments().ifPresent(l -> l.forEach(v -> v.accept(this,
        // ctx)));

        Expression scope = n.getScope();
        boolean unwrapping = true;
        while (unwrapping) {
            if (scope instanceof EnclosedExpr) {
                scope = ((EnclosedExpr) scope).getInner();
            } else if (scope instanceof CastExpr) {
                scope = ((CastExpr) scope).getExpression();
            } else {
                unwrapping = false;
            }
        }
        System.out.println("VISIT FieldAccessExpr : " + n + ", scope is " + scope);

        MethodCallExpr inPlainMethodCall = getEnclosingPlainMethodCall(n, ctx);
        String inPlainMethodCallStr = getEnclosingPlainMethodCall(n, false, ctx);
        if (inPlainMethodCall != null) {
            boolean shouldMock = false;

            if (inPlainMethodCall.getScope().isPresent()) {
                if (inPlainMethodCall.getScope().toString().equals("super")) {
                    shouldMock = true;
                } else if (inPlainMethodCall.getScope().toString().equals("this")) {
                    // can still be static so we need to double check
                    try {
                        if (!inPlainMethodCall.resolve().isStatic()) {
                            shouldMock = true;
                        }
                    } catch (Exception ex) {
                        System.out.println("Unable to resolve method call " + inPlainMethodCall + " using JavaParser");
                        if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(inPlainMethodCall.toString())) {
                            System.out.println("Resolved method call " + inPlainMethodCall + " using SpoonResolver");
                            SpoonResolver.MethodCallInfo info = SpoonResolver.methodCalls.get(inPlainMethodCall.toString());
                            boolean isStatic = info.isStatic;
                            if (!isStatic) {
                                shouldMock = true;
                            }
                        } else {
                            throw ex;
                        }
                    }
                } else {
                    try {
                        String targetTypeStr = TypeResolverUtil.calculateType(inPlainMethodCall.getScope().get());
                        if (targetTypeStr.equals("org.slf4j.Logger")) {
                            // mock because we don't want to generate a logger object
                            System.out.println("Need to mock logger.");
                            shouldMock = true;
                        }
                    } catch (Exception ignored) {}
                }
            }


            if (shouldMock) {
                System.out.println("FieldAccessExpr " + n + " is in plain method call: " + inPlainMethodCallStr + ", discard");

                // Mocking is needed
                for (String whitelisted : Constant.WHITELISTED_NON_MOCK_CLASSES) {
                    if (inPlainMethodCallStr.startsWith(whitelisted + ".")) return;
                }
                System.out.println("FieldAccessExpr: Mocking " + inPlainMethodCallStr);
                ctx.mockMethods.add(inPlainMethodCallStr);
                return;
            }
        }

        System.out.println(ctx.declaredVariables);
        if (ctx.logVariablesBefore.contains(scope.toString()) ||
                ctx.assignedVariables.contains(scope.toString()) ||
                ctx.declaredVariables.contains(scope.toString())){
            // We already checked the parent variable (e.g., foo in foo.bar), so we don't need to treat foo.bar as new variable
            System.out.println("Already handled scope variable " + scope + ", skipping " + n);
            return;
        }

        System.out.println("FieldAccessExpr - " + n + ", " + containLocalVariable(n, ctx) + ", " + n.getNameAsString());
        if (containLocalVariable(n, ctx))
            return;

        if (SpoonResolver.setUpOK) {
            if (SpoonResolver.staticFieldsUsed.contains(n.toString())) {
                System.out.println(n + " is static based on Spoon's resolution");
                return;
            }
        }

        boolean probablyNonStatic = Utils.isStartWithLowerCase(n.getNameAsString()) && Utils.isStartWithLowerCase(n.toString());
        if (!probablyNonStatic) {
            // cannot make sure it is not static based on naming convention, let's try to resolve it
            System.out.println("Cannot determine if " + n + " is static based on naming convention, trying to resolve it...");
            try {
                probablyNonStatic = !n.resolve().asField().isStatic();
                System.out.println(n.toString() + " is probably " + (probablyNonStatic ? "non-static" : "static") + " based on resolution");
            } catch (Exception ignored) {
                // if we cannot resolve, then too bad :(
                System.out.println("JavaParser cannot resolve " + n + ", let's hope it is really static...");
            }
        }

        if (probablyNonStatic) {
            // 1. field access does not contain local variable (e.g., a.b.c)
            // 2. field access scope is not a class name (e.g., System.out) [we don't want to override static fields]
            System.out.println("FieldAccessExpr: add logVariablesBefore - " + n + ", n.getNameAsString() is " + n.getNameAsString());
            ctx.logVariablesBefore.add(n.toString());
            recordVariableType(ctx, n);

            if (!isPreviouslyAssigned(n.toString(), n) && !isPreviouslyAssigned(n.getScope().toString(), n.getScope())) {
                System.out.println("Adding variable " + n.toString() + " to uninitializedVariables");
                ctx.uninitializedVariables.add(n.toString());
            }
        }
    }

    @Override
    public void visit(final LambdaExpr n, final Context ctx) {
        ctx.locals.push(
                ((LambdaExpr) n).getParameters().stream().map(p -> p.getNameAsString())
                        .collect(Collectors.toSet()));
        n.getBody().accept(this, ctx);
        System.out.println("Lambda: " + ctx.locals);
        ctx.locals.pop();
        // skip: n.getParameters().forEach(p -> p.accept(this, ctx));
    }

    @Override
    public void visit(final NameExpr n, final Context ctx) {
        String name = n.getNameAsString();
        System.out.println("VISIT NameExpr : " + n);

        if (!Constant.assertStatic && ctx.staticVariables.contains(name)) {
            System.out.println("Skipping: this variable is static");
            return;
        }

        try {
            ResolvedValueDeclaration resolved = n.resolve();
            if (resolved.isEnumConstant()) {
                System.out.println("Skipping: this variable is an enum");
                return;
            }
        } catch (Exception ignored) {}
        if (SpoonResolver.setUpOK && SpoonResolver.classesUsed.contains(name)) {
            System.out.println("Skipping: this variable is an class");
            return;
        }

        MethodCallExpr inPlainMethodCall = getEnclosingPlainMethodCall(n, ctx);
        if (inPlainMethodCall != null) {
            boolean shouldMock = false;

            if (Utils.getRootCaller(inPlainMethodCall).isPresent()) {
                if (Utils.getRootCaller(inPlainMethodCall).toString().equals("super")) {
                    shouldMock = true;
                } else if (Utils.getRootCaller(inPlainMethodCall).toString().equals("this")) {
                    // can still be static so we need to double check
                    if (!inPlainMethodCall.resolve().isStatic()) {
                        shouldMock = true;
                    }
                } else {
                    try {
                        String targetTypeStr = TypeResolverUtil.calculateType(Utils.getRootCaller(inPlainMethodCall).get());
                        if (targetTypeStr.equals("org.slf4j.Logger")) {
                            // mock because we don't want to generate a logger object
                            System.out.println("Need to mock logger.");
                            shouldMock = true;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (shouldMock) {
                String methodName = inPlainMethodCall.getNameAsString();
                if (inPlainMethodCall.getScope().isPresent()) {
                    System.out.println("Mocking: NameExpr " + inPlainMethodCall.getScope().get() + "." + inPlainMethodCall.getNameAsString() + " is in plain method call: " + methodName + ", discard");
                    ctx.mockMethods.add(inPlainMethodCall.getScope().get() + "." + inPlainMethodCall.getNameAsString());
                } else {
                    System.out.println("Mocking: NameExpr " + inPlainMethodCall.getNameAsString() + " is in plain method call: " + methodName + ", discard");
                    ctx.mockMethods.add(inPlainMethodCall.getNameAsString());
                }

                n.getName().accept(this, ctx);
                return;
            }
        }
        if (!isLocalVariable(name, ctx) && (Utils.isStartWithLowerCase(name)
                || (Utils.isConstant(name) && (name.length() >= 2 || isPreviouslyAssigned(n.toString(), n))))) {
            System.out.println("NameExpr: add logVariablesBefore - " + n);
            ctx.logVariablesBefore.add(name);
            recordVariableType(ctx, n);

            if (!isPreviouslyAssigned(n.toString(), n)) {
                System.out.println("Adding variable " + n.toString() + " to uninitializedVariables");
                ctx.uninitializedVariables.add(n.toString());
            }
        }
        n.getName().accept(this, ctx);
    }

    @Override
    public void visit(MethodCallExpr n, final Context ctx) {
        String name = n.getNameAsString();
        MethodCallExpr inPlainMethodCall = getEnclosingPlainMethodCall(n, ctx);
        if (inPlainMethodCall != null) {
            // The method enclosing `n` is a plain method call, OR n is a plain method call
            boolean shouldMock = false;
            MethodCallExpr shouldMockTarget = null;
            if (Utils.getRootCaller(n).isPresent()) {
                // with explicit caller
                if (Utils.getRootCaller(n).get().toString().equals("super")) {
                    shouldMock = true;
                    shouldMockTarget = n;
                } else if (Utils.getRootCaller(n).get().toString().equals("this")) {
                    // TODO: need to re-write this (convert e.g., remove this)
                    // can still be static so we need to double check
                    try {
                        if (!n.resolve().isStatic()) {
                            shouldMock = true;
                            shouldMockTarget = n;
                        }
                    } catch (Exception ex) {
                        System.out.println("Unable to resolve method call " + n + " using JavaParser");
                        if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(n.toString())) {
                            System.out.println("Resolved method call " + n + " using SpoonResolver");
                            SpoonResolver.MethodCallInfo info = SpoonResolver.methodCalls.get(n.toString());
                            boolean isStatic = info.isStatic;
                            if (!isStatic) {
                                shouldMock = true;
                                shouldMockTarget = n;
                            }
                        } else {
                            throw ex;
                        }
                    }
                } else {
                    try {
                        String targetTypeStr = TypeResolverUtil.calculateType(Utils.getRootCaller(n).get());
                        if (targetTypeStr.equals("org.slf4j.Logger")) {
                            // mock because we don't want to generate a logger object
                            System.out.println("Need to mock logger.");
                            shouldMock = true;
                            shouldMockTarget = n;
                        }
                    } catch (Exception ignored) {}
                }
            } else if (Utils.getRootCaller(inPlainMethodCall).isPresent()) {
                // outer call has explicit caller
                if (Utils.getRootCaller(inPlainMethodCall).get().toString().equals("super")) {
                    shouldMock = true;
                    shouldMockTarget = inPlainMethodCall;
                } else if (Utils.getRootCaller(inPlainMethodCall).get().toString().equals("this")) {
                    // TODO: need to re-write this (convert e.g., remove this)
                    // can still be static so we need to double check
                    try {
                        if (!inPlainMethodCall.resolve().isStatic()) {
                            shouldMock = true;
                            shouldMockTarget = inPlainMethodCall;
                        }
                    } catch (Exception ex) {
                        System.out.println("Unable to resolve method call " + inPlainMethodCall + " using JavaParser");
                        if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(inPlainMethodCall.toString())) {
                            System.out.println("Resolved method call " + inPlainMethodCall + " using SpoonResolver");
                            SpoonResolver.MethodCallInfo info = SpoonResolver.methodCalls.get(inPlainMethodCall.toString());
                            boolean isStatic = info.isStatic;
                            if (!isStatic) {
                                shouldMock = true;
                                shouldMockTarget = inPlainMethodCall;
                            }
                        } else {
                            throw ex;
                        }
                    }
                } else {
                    try {
                        String targetTypeStr = TypeResolverUtil.calculateType(Utils.getRootCaller(inPlainMethodCall).get());
                        if (targetTypeStr.equals("org.slf4j.Logger")) {
                            // mock because we don't want to generate a logger object
                            System.out.println("Need to mock logger.");
                            shouldMock = true;
                            shouldMockTarget = inPlainMethodCall;
                        } else {
                            ctx.invokedVariablesWithType.put(Utils.getRootCaller(inPlainMethodCall).get().toString(), targetTypeStr);
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                try {
                    if (!inPlainMethodCall.resolve().isStatic()) {
                        shouldMock = true;
                        shouldMockTarget = inPlainMethodCall;
                    }
                } catch (Exception ex) {
                    System.out.println("Unable to resolve method call " + inPlainMethodCall + " using JavaParser");
                    if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(inPlainMethodCall.toString())) {
                        System.out.println("Resolved method call " + inPlainMethodCall + " using SpoonResolver");
                        SpoonResolver.MethodCallInfo info = SpoonResolver.methodCalls.get(inPlainMethodCall.toString());
                        boolean isStatic = info.isStatic;
                        if (!isStatic) {
                            shouldMock = true;
                            shouldMockTarget = inPlainMethodCall;
                        }
                    } else {
                        throw ex;
                    }
                }
            }
            if (n.getArguments().stream().anyMatch(param -> param instanceof ThisExpr)) {
                // if any argument is "this", we should mock it
                System.out.println("Argument contains this, need to mock");
                shouldMock = true;
                shouldMockTarget = n;
            }

            if (shouldMock) {
                System.out.println(shouldMockTarget + " vs " + n);
                if (shouldMockTarget.toString().equals(n.toString())) {
                    if (n.getScope().isPresent()) {
                        System.out.println("MethodCallExpr: Mocking " + n.getScope().get() + "." + name + " because it is on this or super");
                        ctx.mockMethods.add(n.getScope().get() + "." + name);
                    } else {
                        System.out.println("MethodCallExpr: Mocking " + name + " because it is on this or super");
                        ctx.mockMethods.add(name);
                    }
                }

                String name2 = getEnclosingPlainMethodCall(n, false, ctx);
                System.out.println(name + " vs " + name2);
                if (name2 != null && !name.equals(name2)) {
                    System.out.println("MethodCallExpr: Mocking " + name2 + " because it is the enclosing method");
                    ctx.mockMethods.add(name2);
                }
            }
        }

        super.visit(n, ctx);
    }

    private String getEnclosingPlainMethodCall(Node node, boolean full, Context ctx) {
        // 1. Find the outermost MethodCallExpr that contains this node
        MethodCallExpr outerCall = null;
        Node current = node;
        while (current != null) {
            if (current instanceof MethodCallExpr && current.getRange().isPresent()) {
                Range range = current.getRange().get();
                if (range.begin.line >= ctx.startLineNumber && range.end.line <= ctx.endLineNumber)
                    outerCall = (MethodCallExpr) current;
            }
            current = current.getParentNode().orElse(null);
        }

        if (outerCall == null) {
            return null; // no enclosing method call
        }

        // 2. Find the nearest statement ancestor
        Optional<Statement> stmtOpt = outerCall.findAncestor(Statement.class);
        if (!stmtOpt.isPresent()) {
            return null;
        }

        Statement stmt = stmtOpt.get();

        // 3. The statement must be a plain ExpressionStmt wrapping this outerCall
        if (!(stmt instanceof ExpressionStmt)) {
            return null;
        }

        Expression stmtExpr = ((ExpressionStmt) stmt).getExpression();
        if (stmtExpr != outerCall) {
            return null; // outerCall is part of assignment/return/etc.
        }

        if (full) {
            return outerCall.toString();
        }

        // 4. Build the qualified name
        if (outerCall.getScope().isPresent()) {
            return outerCall.getScope().get().toString() + "." + outerCall.getNameAsString();
        }

        return outerCall.getNameAsString();
    }

    private MethodCallExpr getEnclosingPlainMethodCall(Node node, Context ctx) {
        // 1. Find the outermost MethodCallExpr that contains this node
        MethodCallExpr outerCall = null;
        Node current = node;
        while (current != null) {
            if (current instanceof MethodCallExpr && current.getRange().isPresent()) {
                Range range = current.getRange().get();
                if (range.begin.line >= ctx.startLineNumber && range.end.line <= ctx.endLineNumber)
                    outerCall = (MethodCallExpr) current;
            }
            current = current.getParentNode().orElse(null);
        }

        if (outerCall == null) {
            return null; // no enclosing method call
        }

        // 2. Find the nearest statement ancestor
        Optional<Statement> stmtOpt = outerCall.findAncestor(Statement.class);
        if (!stmtOpt.isPresent()) {
            return null;
        }

        Statement stmt = stmtOpt.get();

        // 3. The statement must be a plain ExpressionStmt wrapping this outerCall
        if (!(stmt instanceof ExpressionStmt)) {
            return null;
        }

        Expression stmtExpr = ((ExpressionStmt) stmt).getExpression();
        if (stmtExpr != outerCall) {
            return null; // outerCall is part of assignment/return/etc.
        }

        return outerCall;
    }

    private boolean isLocalVariable(String name, Context ctx) {
        System.out.println("isLocalVariable (" + name + "): " + ctx.locals);
        boolean isLocalVariableRes = false;
        for (Set<String> locals : ctx.locals) {
            if (locals.contains(name)) {
                isLocalVariableRes = true;
                break;
            }
        }
        return isLocalVariableRes;
    }

    @Override
    public void visit(final TryStmt n, final Context ctx) {
        Set<String> variableSet = new HashSet<>();
        NodeList<Expression> expressionList = n.getResources();
        for (Expression expression : expressionList) {
            if (expression instanceof VariableDeclarationExpr) {
                for (VariableDeclarator variableDeclarator : ((VariableDeclarationExpr) expression).getVariables()) {
                    variableSet.add(variableDeclarator.getNameAsString());
                }
            }
        }
        ctx.locals.push(variableSet);
        // skip: n.getCatchClauses().forEach(p -> p.accept(this, ctx));
        n.getFinallyBlock().ifPresent(l -> l.accept(this, ctx));
        n.getResources().forEach(p -> p.accept(this, ctx));
        n.getTryBlock().accept(this, ctx);
        ctx.locals.pop();
    }

    @Override
    public void visit(final ForEachStmt n, final Context ctx) {
        Set<String> variableSet = new HashSet<>();
        VariableDeclarationExpr variableDeclarationExpr = n.getVariable();
        for (VariableDeclarator variableDeclarator : variableDeclarationExpr.getVariables()) {
            variableSet.add(variableDeclarator.getNameAsString());
        }
        ctx.locals.push(variableSet);
        n.getBody().accept(this, ctx);
        n.getIterable().accept(this, ctx);
        n.getVariable().accept(this, ctx);
        ctx.locals.pop();
    }

    @Override
    public void visit(final ForStmt n, final Context ctx) {
        Set<String> variableSet = new HashSet<>();
        NodeList<Expression> expressionList = n.getInitialization();
        for (Expression expression : expressionList) {
            if (expression instanceof VariableDeclarationExpr) {
                for (VariableDeclarator variableDeclarator : ((VariableDeclarationExpr) expression).getVariables()) {
                    variableSet.add(variableDeclarator.getNameAsString());
                }
            }
        }
        ctx.locals.push(variableSet);
        n.getBody().accept(this, ctx);
        n.getCompare().ifPresent(l -> l.accept(this, ctx));
        n.getInitialization().forEach(p -> p.accept(this, ctx));
        n.getUpdate().forEach(p -> p.accept(this, ctx));
        ctx.locals.pop();
    }

    @Override
    public void visit(final SynchronizedStmt n, final Context ctx) {
        Set<String> variableSet = new HashSet<>();
        Expression expression = n.getExpression();
        if (expression instanceof VariableDeclarationExpr) {
            for (VariableDeclarator variableDeclarator : ((VariableDeclarationExpr) expression).getVariables()) {
                variableSet.add(variableDeclarator.getNameAsString());
            }
        }
        ctx.locals.push(variableSet);
        n.getBody().accept(this, ctx);
        n.getExpression().accept(this, ctx);
        ctx.locals.pop();
    }

    public static boolean isPreviouslyAssigned(String varName, Node n) {
        int currentLine = n.getRange().map(r -> r.begin.line).orElse(Integer.MAX_VALUE);

        // "this.x" and "x" can both refer to a field — normalize for field lookups
        String normalizedName = varName.startsWith("this.")
                ? varName.substring(5)
                : varName;
        boolean hasThisPrefix = varName.startsWith("this.");

        if (varName.startsWith("this.")) return true;

        Node current = n;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();

            // Parameters can only match if there's no "this." prefix
            if (!hasThisPrefix) {
                if (current instanceof MethodDeclaration) {
                    for (Parameter p : ((MethodDeclaration) current).getParameters())
                        if (p.getNameAsString().equals(varName)) return true;
                } else if (current instanceof ConstructorDeclaration) {
                    for (Parameter p : ((ConstructorDeclaration) current).getParameters())
                        if (p.getNameAsString().equals(varName)) return true;
                } else if (current instanceof LambdaExpr) {
                    for (Parameter p : ((LambdaExpr) current).getParameters())
                        if (p.getNameAsString().equals(varName)) return true;
                }
            }

            // Check for-each loop variable
            if (current instanceof ForEachStmt) {
                ForEachStmt forEach = (ForEachStmt) current;
                for (VariableDeclarator var : forEach.getVariable().getVariables())
                    if (var.getNameAsString().equals(varName)) return true;
            }

            // Check for loop variable
            if (current instanceof ForStmt) {
                ForStmt forStmt = (ForStmt) current;
                for (Expression init : forStmt.getInitialization()) {
                    if (init instanceof VariableDeclarationExpr) {
                        for (VariableDeclarator var : ((VariableDeclarationExpr) init).getVariables())
                            if (var.getNameAsString().equals(varName)) return true;
                    }
                }
            }

            // Fields are always default-initialized.
            // Use normalizedName so both "x" and "this.x" match the field "x".
            if (current instanceof ClassOrInterfaceDeclaration) {
                for (FieldDeclaration field : ((ClassOrInterfaceDeclaration) current).getFields())
                    for (VariableDeclarator var : field.getVariables())
                        if (var.getNameAsString().equals(normalizedName)) return true;

                try {
                    if (getAllFields((ClassOrInterfaceDeclaration) current).contains(varName)) {
                        System.out.println("Variable " + varName + " is a field of class or its parent classes according to JavaParser");
                        return true;
                    }
                } catch (Exception ignored) {}
            } else if (current instanceof EnumDeclaration) {
                for (FieldDeclaration field : ((EnumDeclaration) current).getFields())
                    for (VariableDeclarator var : field.getVariables())
                        if (var.getNameAsString().equals(normalizedName)) return true;
            }
        }

        // Walk up to the root
        Node root = n;
        while (root.getParentNode().isPresent()) {
            root = root.getParentNode().get();
        }

        // root is now the CompilationUnit
        List<AssignExpr> assignments = root.findAll(AssignExpr.class);
        for (AssignExpr a : assignments) {
            if (a == n) continue;
            if (a.getTarget().toString().equals(varName)) {
                int line = a.getRange().map(r -> r.begin.line).orElse(-1);
                if (line < currentLine) return true;
            }
        }

        List<VariableDeclarator> decls = root.findAll(VariableDeclarator.class);
        for (VariableDeclarator decl : decls) {
            if (decl.getNameAsString().equals(varName) && decl.getInitializer().isPresent()) {
                int line = decl.getRange().map(r -> r.begin.line).orElse(-1);
                if (line < currentLine) return true;
            }
        }

        if (SpoonResolver.setUpOK && SpoonResolver.allFields != null && SpoonResolver.allFields.contains(varName)) {
            // It is a field
            System.out.println("Variable " + varName + " is a field of class or its parent classes according to Spoon");
            return true;
        }

        return false;
    }

    public static Set<String> getAllFields(ClassOrInterfaceDeclaration clazz) {
        Set<String> result = new HashSet<>();

        for (FieldDeclaration fd : clazz.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                result.add(v.getNameAsString());
            }
        }

        for (ClassOrInterfaceType parentType : clazz.getExtendedTypes()) {
            try {
                ResolvedReferenceTypeDeclaration resolved =
                        parentType.resolve().asReferenceType().getTypeDeclaration().get();

                if (resolved instanceof JavaParserClassDeclaration) {
                    ClassOrInterfaceDeclaration parentDecl =
                            ((JavaParserClassDeclaration) resolved).getWrappedNode();
                    result.addAll(getAllFields(parentDecl));
                }
            } catch (UnsolvedSymbolException e) {
            }
        }
        return result;
    }

    public void recordVariableType(Context ctx, Expression variable) {
        try {
            ctx.allVariablesWithType.put(variable.toString(), TypeResolverUtil.calculateType(variable));
        } catch (Exception ex) {
            System.out.println("Unable to determine the type for " + variable);
        }
    }
}
