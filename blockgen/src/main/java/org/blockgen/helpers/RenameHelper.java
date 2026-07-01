package org.blockgen.helpers;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import org.blockgen.Context;
import org.blockgen.Utils;

public class RenameHelper extends ModifierVisitor<Context> {
    @Override
    public Visitable visit(FieldAccessExpr n, Context ctx) {
        if (ctx.rename.contains(n)) {
            return new NameExpr(Utils.rename(n.toString()));
        }
        return super.visit(n, ctx);
    }

    @Override
    public Visitable visit(MethodCallExpr n, Context ctx) {
        if (ctx.rename.contains(n)) {
            return new NameExpr(Utils.rename(n.toString()));
        }
        return super.visit(n, ctx);
    }

    @Override
    public Visitable visit(Parameter n, Context ctx) {
        if (!Utils.isValidVariableName(n.getNameAsString())) {
            return new Parameter(n.getType(), Utils.rename(n.getNameAsString()));
        }
        return super.visit(n, ctx);
    }

    @Override
    public Visitable visit(AssignExpr n, Context ctx) {
        if (ctx.rename.contains(n.getTarget())) {
            return super.visit(new AssignExpr(new NameExpr(Utils.rename(n.getTarget().toString())), n.getValue(), n.getOperator()), ctx);
        }
        return super.visit(n, ctx);
    }

    @Override
    public Visitable visit(ArrayAccessExpr n, Context ctx) {
        if (ctx.rename.contains(n)) {
            return super.visit(new NameExpr(Utils.rename(n.toString())), ctx);
        }
        return super.visit(n, ctx);
    }

    @Override
    public Visitable visit(ThisExpr n, Context ctx) {
        // Here, we want to replace this with new XXX(), where XXX is the class name without _Extracted
        // BDK should to do the same as well
        if (!n.getTypeName().isPresent() && ctx.canCreateThis) {
            return new ObjectCreationExpr(
                    null,
                    new ClassOrInterfaceType(null, ctx.className.replace("_Extracted", "")),
                    new NodeList<>()
            );
        }
        return super.visit(n, ctx);
    }
}