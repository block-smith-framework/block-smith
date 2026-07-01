package org.blockgen.helpers;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.blockgen.Context;
import org.blockgen.visitors.BreakReplacementVisitor;
import org.blockgen.visitors.VariableFindingVisitor;
import org.blockgen.visitors.VariableWithTypeFindingVisitor;

import java.util.List;
import java.util.stream.Collectors;

public class VariableFinder {
    public static void findVariables(CompilationUnit cu, Context ctx, int startLine, int endLine, boolean collectType) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            cls.findAll(FieldDeclaration.class).forEach(fd -> {
                if (fd.hasModifier(Modifier.Keyword.STATIC)) {
                    fd.getVariables().forEach(var -> {
                        if (var.getInitializer().isPresent()) {
                            // Only capture if we have initializer, otherwise will could get NPE
                            ctx.staticVariables.add(var.getNameAsString());
                        }
                    });
                }
            });
        });
        cu.getImports().forEach(imp -> {
            // Note: not sure if this is a good assumption
            // Goal: In https://github.com/blackbeard334/djoom3/blob/64362645420c99ac621b051a678de96883e2a329/src/main/java/neo/ui/EditWindow.java#L287-L300
            // idKeyInput is mis-classified as variable, but it is a static class
            String impName = imp.getNameAsString();
            String staticMember = impName.substring(impName.lastIndexOf(".") + 1);
            ctx.staticVariables.add(staticMember);
        });

        VoidVisitorAdapter<Context> visitor = collectType ? new VariableWithTypeFindingVisitor() : new VariableFindingVisitor();
        // Find all statements that fall within the line range and visit them

        cu.findAll(Statement.class).forEach(stmt -> {
            stmt.getRange().ifPresent(range -> {
                if (range.begin.line >= startLine && range.end.line <= endLine) {
                    // Only visit top-level statements in range (avoid double-visiting children)
                    boolean hasAncestorInRange = stmt.findAncestor(Statement.class)
                            .flatMap(Node::getRange)
                            .map(pr -> pr.begin.line >= startLine && pr.end.line <= endLine)
                            .orElse(false);

                    if (!hasAncestorInRange) {
                        if (stmt instanceof ReturnStmt || stmt instanceof BreakStmt || stmt instanceof ContinueStmt || stmt instanceof ThrowStmt) {
                            ctx.endWithReturn = true;
                        } else {
                            ctx.endWithReturn = false;
                        }

                        if (stmt instanceof ExpressionStmt) {
                            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
                            if (exprStmt.getExpression() instanceof VariableDeclarationExpr) {
                                // it's a declaration statement
                                VariableDeclarationExpr n = exprStmt.getExpression().asVariableDeclarationExpr();
                                for (VariableDeclarator variableDeclarator : n.getVariables()) {
                                    String name = variableDeclarator.getNameAsString();
                                    String type = variableDeclarator.getTypeAsString();
                                    System.out.println("Variable " + name + " is declared @ top level with type " + type);

                                    ctx.declaredVariablesTopLevel.add(name);
                                }
                            }
                        }

                        System.out.println("findVariables on statement " + stmt);
                        stmt.accept(visitor, ctx);
                    }
                }
            });
        });

        if (ctx.endWithReturn) {
            System.out.println("Target fragment is ending with a return/break/continue statement");
        }
    }

    public static void findClassName(CompilationUnit cu, Context ctx, int startLine, int endLine, boolean collectType) {
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        int smallestRange = Integer.MAX_VALUE;

        for (ClassOrInterfaceDeclaration cls : classes) {
            if (!cls.getRange().isPresent()) continue;
            com.github.javaparser.Range range = cls.getRange().get();
            if (range.begin.line <= startLine && range.end.line >= endLine) {
                int size = range.end.line - range.begin.line;
                if (size < smallestRange) {
                    smallestRange = size;
                    ctx.className = collectType ? cls.getNameAsString() + "_Extracted" : cls.getNameAsString();
                    ctx.canCreateThis = cls.getConstructors().stream().anyMatch(c -> c.getParameters().isEmpty()); // there is a constructor that does not require argument
                    ctx.classGenericTypes = cls.getTypeParameters();
                }

                ctx.methods = cls.getMethods().stream()
                        .map(MethodDeclaration::getNameAsString)
                        .collect(Collectors.toSet());
            }
        }

        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

        for (MethodDeclaration method : methods) {
            if (!method.getRange().isPresent()) continue;
            com.github.javaparser.Range range = method.getRange().get();
            if (range.begin.line <= startLine && range.end.line >= endLine) {
                int size = range.end.line - range.begin.line;
                if (size < smallestRange) {
                    smallestRange = size;
                    ctx.genericTypes = method.getTypeParameters();
                    ctx.returnType = method.getType();
                }
            }
        }

        List<ReturnStmt> returnStmts = cu.findAll(ReturnStmt.class);

        ReturnStmt foundReturn = null;
        for (ReturnStmt returnStmt : returnStmts) {
            if (!returnStmt.getRange().isPresent() || !returnStmt.getExpression().isPresent()) continue;
            com.github.javaparser.Range range = returnStmt.getRange().get();
            if (range.begin.line >= startLine && range.end.line <= endLine) {
                System.out.println("Potential return statement on line " + range.begin.line + ": " + returnStmt);
                int size = range.end.line - range.begin.line;
                if (size < smallestRange) {
                    smallestRange = size;
                    foundReturn = returnStmt;
                }
            }
        }
        if (foundReturn != null) {
            try {
                System.out.println("Predicting return type");
                ctx.returnType = BreakReplacementVisitor.getReturnType(foundReturn);
                System.out.println("The return type is likely " + ctx.returnType);
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }

            if (ctx.returnType == null) {
                throw new RuntimeException("Unable to determine return type of lambda expression");
            }
        }
    }
}
