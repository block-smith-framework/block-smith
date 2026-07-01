package org.blockgen.helpers;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class MethodCallRemover {

    /**
     * Removes calls to local methods and mocked methods from the given `CompilationUnit`.
     *
     * @param cu The `CompilationUnit` to process.
     * @param inFragmentOnly If true, only processes statements within the specified line range.
     * @param startLine The starting line number of the range to process.
     * @param endLine The ending line number of the range to process.
     * @param mockMethods A set of mocked method names to remove.
     */
    public static void remove(CompilationUnit cu, boolean inFragmentOnly, int startLine, int endLine, Set<String> mockMethods) {
        System.out.println("The following methods are mocked: " + mockMethods);
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        Set<String> removedMethods = new HashSet<>();
        for (ClassOrInterfaceDeclaration cls : classes) {
            Set<String> localMethodNames = cls.getMethods().stream()
                    .filter(m -> !m.isStatic())
                    .map(MethodDeclaration::getNameAsString)
                    .collect(Collectors.toSet());

            cls.findAll(ExpressionStmt.class).forEach(stmt -> {
                if (inFragmentOnly && stmt.getRange().isPresent()) {
                    Range range = stmt.getRange().get();
                    if (range.begin.line < startLine || range.end.line > endLine) {
                        return;
                    }
                }

                boolean hasRemovedCall = false;
                // NOTE: this can be very strict (range is outer lambda and one method in lambda is mocked, then the whole lambda is removed)
//                hasRemovedCall = !stmt.findAll(MethodCallExpr.class).stream()
//                        .filter(mce -> matchesRemovedMethod(mce, mockMethods))
//                        .collect(Collectors.toList())
//                        .isEmpty();

                // Alternative: only check the outermost call
                if (stmt != null) {
                    Expression expr = ((ExpressionStmt) stmt).getExpression();
                    if (expr instanceof MethodCallExpr) {
                        // Only check the outermost call, NOT nested ones inside lambdas
                        MethodCallExpr mce = (MethodCallExpr) expr;
                        hasRemovedCall = matchesRemovedMethod(mce, mockMethods);
                    }
                }

                if (hasRemovedCall) {
                    // Remove statement because it contains mocked method call
                    System.out.println("Remove method because it is mocked: " + stmt);
                    stmt.remove();
                    return;
                }

                Expression expr = stmt.getExpression();

                // We only care about bare method calls (no assignment)
                if (!(expr instanceof MethodCallExpr)) return;

                MethodCallExpr call = (MethodCallExpr) expr;
                Optional<Expression> scope = call.getScope();

                // TODO: static is OK, non this/super are fine
                // Check if the method is declared in the same class
                if (localMethodNames.contains(call.getNameAsString()) && isLocalCall(scope)) {
                    // Remove statement because it calls local method
                    System.out.println("Remove method call (and mock): " + stmt);
                    stmt.remove();
                    // We don't want to update mockMethods right away
                    // otherwise, it will make `hasRemovedCall` true.
                    removedMethods.add(call.getNameAsString());
                }
            });
        }
        mockMethods.addAll(removedMethods);
    }

    private static boolean isLocalCall(Optional<Expression> scope) {
        if (!scope.isPresent()) return true; // bar() — no qualifier
        Expression s = scope.get();
        return s instanceof ThisExpr || s instanceof SuperExpr; // this.bar() or super.bar()
    }

    private static boolean matchesRemovedMethod(MethodCallExpr mce, Set<String> removed) {
        String scope = mce.getScope().isPresent()
                ? mce.getScope().get().toString()
                : "";
        String fullName = scope.isEmpty()
                ? mce.getNameAsString()
                : scope + "." + mce.getNameAsString();
        return removed.contains(fullName);
    }
}
