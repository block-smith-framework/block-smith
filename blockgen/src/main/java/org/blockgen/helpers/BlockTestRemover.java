package org.blockgen.helpers;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class BlockTestRemover {

    public static boolean removeBlockTests(String src) {
        try {
            removeFragmentMarkers(src);
            CompilationUnit cu = StaticJavaParser.parse(Paths.get(src));

            cu.accept(new ModifierVisitor<Void>() {
                @Override
                public Visitable visit(ExpressionStmt stmt, Void arg) {
                    if (stmt.getExpression() instanceof MethodCallExpr) {
                        MethodCallExpr call = (MethodCallExpr) stmt.getExpression();
                        if (startsWithBlockTest(call)) {
                            return null; // remove entire statement
                        }
                    }
                    return super.visit(stmt, arg);
                }
            }, null);

            FileWriter writer = new FileWriter(src);
            writer.write(cu.toString());
            writer.close();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean removeFragmentMarkers(String src) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(src));
            List<String> filtered = lines.stream()
                    .filter(line -> !line.trim().startsWith("System.out.println(\"BLOCKGEN_FRAGMENT"))
                    .collect(Collectors.toList());
            Files.write(Paths.get(src), filtered);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWithBlockTest(MethodCallExpr call) {
        Expression current = call;
        while (current instanceof MethodCallExpr) {
            MethodCallExpr mce = (MethodCallExpr) current;
            if (!mce.getScope().isPresent()) {
                // This is the root of the chain
                return mce.getNameAsString().equals("blocktest") || mce.getNameAsString().equals("lambdatest");
            }
            current = mce.getScope().get();
        }
        return false;
    }
}
