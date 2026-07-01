package org.blockgen.visitors;

import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

public class BracesVisitor extends ModifierVisitor<Void> {

    @Override
    public Visitable visit(IfStmt n, Void arg) {
        super.visit(n, arg);
        if (!(n.getThenStmt() instanceof BlockStmt))
            n.setThenStmt(wrap(n.getThenStmt()));
        n.getElseStmt().ifPresent(el -> {
            if (!(el instanceof BlockStmt) && !(el instanceof IfStmt))
                n.setElseStmt(wrap(el));
        });
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void arg) {
        super.visit(n, arg);
        if (!(n.getBody() instanceof BlockStmt))
            n.setBody(wrap(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void arg) {
        super.visit(n, arg);
        if (!(n.getBody() instanceof BlockStmt))
            n.setBody(wrap(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(WhileStmt n, Void arg) {
        super.visit(n, arg);
        if (!(n.getBody() instanceof BlockStmt))
            n.setBody(wrap(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(DoStmt n, Void arg) {
        super.visit(n, arg);
        if (!(n.getBody() instanceof BlockStmt))
            n.setBody(wrap(n.getBody()));
        return n;
    }

    private BlockStmt wrap(Statement stmt) {
        BlockStmt block = new BlockStmt();
        block.addStatement(stmt.clone());
        return block;
    }
}