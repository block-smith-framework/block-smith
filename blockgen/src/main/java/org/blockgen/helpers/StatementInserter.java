package org.blockgen.helpers;

import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class StatementInserter {

    public final CompilationUnit cu;

    public StatementInserter(Path javaFile) throws IOException {
        this.cu = StaticJavaParser.parse(javaFile);
    }

    public StatementInserter(CompilationUnit cu) throws IOException {
        this.cu = cu;
    }

    public void insertStatementBefore(Statement newStmt, Statement targetStmt) {
        Node parent = targetStmt.getParentNode().get();
        if (parent instanceof Statement) {
            if (!(parent instanceof BlockStmt)) {
                parent = new BlockStmt().addStatement((Statement) parent);
                targetStmt.setParentNode(parent);
            }
            ((BlockStmt) parent).getStatements().addBefore(newStmt, targetStmt);
        } else if (parent instanceof SwitchEntry) {
            ((SwitchEntry) parent).getStatements().addBefore(newStmt, targetStmt);
        }
    }

    public static void insertStatementAfter(Statement newStmt, Statement targetStmt) {
        Node parent = targetStmt.getParentNode().get();
        if (parent instanceof Statement) {
            if (!(parent instanceof BlockStmt)) {
                parent = new BlockStmt().addStatement((Statement) parent);
                targetStmt.setParentNode(parent);
            }
            ((BlockStmt) parent).getStatements().addAfter(newStmt, targetStmt);
        } else if (parent instanceof SwitchEntry) {
            ((SwitchEntry) parent).getStatements().addAfter(newStmt, targetStmt);
        }
    }

    public Statement findStatementAtLine(int targetLine) {
        List<Statement> allStmts = cu.findAll(Statement.class);
        Statement bestMatch = null;
        int smallestRange = Integer.MAX_VALUE;

        for (Statement stmt : allStmts) {
            if (!stmt.getRange().isPresent()) continue;
            com.github.javaparser.Range range = stmt.getRange().get();
            if (range.begin.line <= targetLine && range.end.line >= targetLine) {
                int size = range.end.line - range.begin.line;
                if (size < smallestRange && isDirectlyInBlock(stmt)) {
                    smallestRange = size;
                    bestMatch = stmt;
                }
            }
        }
        return bestMatch;
    }

    public boolean isLineAComment(int targetLine) {
        boolean hasComment = cu.getAllComments().stream()
                .filter(c -> c.getRange().isPresent())
                .anyMatch(c -> {
                    com.github.javaparser.Range range = c.getRange().get();
                    return range.begin.line <= targetLine && range.end.line >= targetLine;
                });

        if (!hasComment) return false;

        boolean hasCode = cu.findAll(Statement.class).stream()
                .filter(s -> s.getRange().isPresent())
                .anyMatch(s -> s.getRange().get().begin.line == targetLine);

        return !hasCode;
    }

    private static boolean isDirectlyInBlock(Statement stmt) {
        if (!stmt.getParentNode().isPresent()) return false;
        Node parent = stmt.getParentNode().get();
        return parent instanceof BlockStmt || parent instanceof SwitchEntry;
    }

    public void insertStatementAtLine(int targetLine, Statement newStmt, boolean insertBefore) {
        int line = targetLine;
        while (isLineAComment(line)) {
            if (insertBefore) line += 1;
            else line -= 1;
        }

        Statement targetStmt = findStatementAtLine(line);
        if (targetStmt != null) {
            if (insertBefore) {
                insertStatementBefore(newStmt, targetStmt);
            } else {
                insertStatementAfter(newStmt, targetStmt);
            }
        } else {
            System.out.println("Unable to find target statement at line " + targetLine + " or " + line);
        }
    }

    public void insertCoverageStatement(int targetLine, Statement logCoverageStmt, BlockStmt afterBlock, BlockStmt endBlock, Context ctx) {
        int line = targetLine;
        while (isLineAComment(line)) {
            line -= 1;
        }

        Statement n = findStatementAtLine(line);
        if (n == null || !n.getParentNode().isPresent()) {
            System.out.println("Unable to insert coverage statement at line " + targetLine + " or " + line);
            return;
        }
        Node parent = n.getParentNode().get();

        NodeList<Statement> stmts = null;
        if (parent instanceof BlockStmt) {
            stmts = ((BlockStmt) parent).getStatements();
        } else if (parent instanceof SwitchEntry) {
            stmts = ((SwitchEntry) parent).getStatements();
        }
        if (stmts == null) {
            throw new RuntimeException("parent node is not block statement or switch entry");
        }

        // surround with try catch block
        TryStmt tryStmt = new TryStmt();
        BlockStmt tryBlock = new BlockStmt();
        BlockStmt exceptBlock = endBlock.clone();
        BlockStmt finallyBlock = new BlockStmt();

        // special handling for super constructor invocation because
        // first statement in constructor must be super constructor
        // invocation (super() or this())
        ExplicitConstructorInvocationStmt superStmt = null;
        // move statements to try block
        while (!stmts.isEmpty()) {
            Statement stmt = stmts.remove(0);
            if (stmt instanceof ExplicitConstructorInvocationStmt) {
                superStmt = (ExplicitConstructorInvocationStmt) stmt;
            } else {
                tryBlock.addStatement(stmt);
            }
        }
        tryStmt.setTryBlock(tryBlock);
        // insert log coverage statement in finally block
        if (afterBlock != null) {
            finallyBlock = afterBlock;
        }
        finallyBlock.addStatement(logCoverageStmt);
        tryStmt.setFinallyBlock(finallyBlock);

        if (Constant.exceptionTesting) {
            exceptBlock.addStatement(0, Utils.buildLogStatement(Constant.TARGET_STMT_THROW, Constant.EXCEPTION_VARIABLE + ".getClass().getName()", ctx));
            CatchClause catchClause = new CatchClause(new Parameter(new ClassOrInterfaceType(null, "Exception"), Constant.EXCEPTION_VARIABLE), exceptBlock);
            tryStmt.setCatchClauses(new NodeList<>(catchClause));
        }

        // replace original statements with try catch block
        if (superStmt != null) {
            stmts.add(superStmt);
        }
        stmts.add(tryStmt);
    }

    public String print() {
        return cu.toString();
    }

}