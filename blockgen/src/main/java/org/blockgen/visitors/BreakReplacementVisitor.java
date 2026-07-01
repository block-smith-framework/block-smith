package org.blockgen.visitors;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.blockgen.Constant;
import org.blockgen.Context;
import org.blockgen.Utils;
import org.blockgen.helpers.FragmentCollector;
import org.blockgen.strategies.Exli;
import org.blockgen.types.SpoonResolver;
import org.blockgen.types.TypeResolver;
import org.blockgen.types.TypeResolverUtil;

import java.util.*;

// From Block Tests
public class BreakReplacementVisitor extends VoidVisitorAdapter<Void> {

    private static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait", "finalize", "clone"
    ));

    private final BlockStmt afterBlock;
    private final BlockStmt endBlock;
    private final Context ctx;
    public boolean shouldBeReplaced = false;

    private int returnNumber = 0;

    private boolean isExliMode;

    public BreakReplacementVisitor(boolean isExliMode, BlockStmt afterBlock, BlockStmt endBlock, Context context) {
        this.isExliMode = isExliMode;
        this.afterBlock = afterBlock;
        this.endBlock = endBlock;
        this.ctx = context;
    }

    @Override
    public void visit(BreakStmt breakStmt, Void arg) {
        super.visit(breakStmt, arg);

        if (withoutLoop(breakStmt)) {
            System.out.println("Found breakStmt @ " + breakStmt);
            // Replace break with return
//            ReturnStmt returnStmt = new ReturnStmt();
//            breakStmt.replace(returnStmt);
            if (afterBlock != null) {
                System.out.println("Replacing break statement with return statement: " + breakStmt);
                replaceWithReturnAndInsert(breakStmt, afterBlock, null);
            }
            shouldBeReplaced = true;
        }
    }

    public void visit(ReturnStmt returnStmt, Void arg) {
        super.visit(returnStmt, arg);
        if (endBlock != null && ctx != null && returnStmt.asReturnStmt().getExpression().isPresent()) {
            // Capture return value
            Type predictedReturnType = ctx.returnType;
            VariableDeclarator declarator = new VariableDeclarator(predictedReturnType,  "_BLOCKGEN_RETURN_" + returnNumber,  returnStmt.asReturnStmt().getExpression().get().clone());
            Statement varDeclStmt = new ExpressionStmt(new VariableDeclarationExpr(declarator));
            Statement logStmt = Utils.buildLogStatementWithType(Constant.TARGET_STMT_RETURN, "_BLOCKGEN_RETURN_" + returnNumber, predictedReturnType.toString(), ctx);
            BlockStmt newBlock = endBlock.clone();
            newBlock.addStatement(0, varDeclStmt);
            newBlock.addStatement(1, logStmt);
            Statement newReturnStmt = null;
            if (isExliMode) {
                newReturnStmt = new ReturnStmt(new NameExpr("_BLOCKGEN_RETURN_" + returnNumber));
                System.out.println("Found return statement " + returnStmt + ", replace return value with variable and capture it");
                Exli.noVariableToLog = false;
            } else {
                System.out.println("Found return statement " + returnStmt + ", capture it");
                FragmentCollector.noVariableToLog = false;
            }
            replaceWithReturnAndInsert(returnStmt, newBlock, newReturnStmt);
            returnNumber += 1;
        } else if (afterBlock != null && !returnStmt.asReturnStmt().getExpression().isPresent()){
            System.out.println("Found bare return statement " + returnStmt + ", capture all variables");
            replaceWithReturnAndInsert(returnStmt, afterBlock, null);
        }
        shouldBeReplaced = true;
    }

    public static Type getReturnType(ReturnStmt returnStmt) {
        Node current = returnStmt;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();

            System.out.println("PARENT IS " + parent);

            if (parent instanceof MethodDeclaration) {
                // Regular method
                System.out.println("Regular method");
                return ((MethodDeclaration) parent).getType();

            } else if (parent instanceof ConstructorDeclaration) {
                // Constructor has no return type
                System.out.println("Constructor");
                return new VoidType();

            } else if (parent instanceof LambdaExpr) {
                // Lambda — return type is inferred, need to resolve
                LambdaExpr lambda = (LambdaExpr) parent;
                String lambdaType = getLambdaReturnType(lambda);
                System.out.println("LAMBDA RETURN TYPE IS " + lambdaType);
                return TypeResolverUtil.getTypeFromStr(lambdaType);
            } else if (parent instanceof ObjectCreationExpr) {
                // Anonymous class — find the method inside it that contains the return
                if (((ObjectCreationExpr) parent).getAnonymousClassBody().isPresent()) {
                    // walk back down to find the enclosing method in the anon class
                    Node child = current;
                    while (!(child instanceof MethodDeclaration) && child.getParentNode().isPresent() && child.getParentNode().get() != parent) {
                        child = child.getParentNode().get();
                    }
                    if (child instanceof MethodDeclaration) {
                        System.out.println("Anonymous class");
                        return ((MethodDeclaration) child).getType();
                    }
                }
                throw new RuntimeException("Could not find enclosing callable for return statement: " + returnStmt);
            }

            current = parent;
        }

        throw new RuntimeException("Could not find enclosing callable for return statement: " + returnStmt);
    }

    private static String getLambdaReturnType(LambdaExpr lambda) {
        if (TypeResolverUtil.useSpoonFirst && SpoonResolver.lambdaReturnType != null) {
            System.out.println("The return type of the lambda has been set by SpoonResolver, using it: " + SpoonResolver.lambdaReturnType);
            return SpoonResolver.lambdaReturnType;
        }

        // Strategy 1: infer from return statements inside lambda (bottom-up)
        Set<String> potentialSolutions = new HashSet<>();
        try {
            List<ReturnStmt> returns = lambda.findAll(ReturnStmt.class);
            for (ReturnStmt ret : returns) {
                if (ret.getExpression().isPresent()) {
                    String type = TypeResolver.sSymbolResolver
                            .calculateType(ret.getExpression().get())
                            .describe();
                    if (type != null && !type.equals("null")) {
                        if (type.startsWith("? super ")) {
                            type = type.substring("? super ".length());
                        }
                        if (type.startsWith("? extends ")) {
                            type = type.substring("? extends ".length());
                        }
                        potentialSolutions.add(type);
                    }
                }
            }

            if (potentialSolutions.isEmpty()) {
                System.out.println("No return statement");
                System.out.println("Falling back to SAM method resolution");
            } else if (potentialSolutions.size() == 1) {
                return potentialSolutions.iterator().next();
            } else {
                System.out.println("Multiple return types found in lambda, unable to determine return type: " + potentialSolutions);
                System.out.println("Falling back to SAM method resolution");
            }
        } catch (Exception ex) {
            System.out.println("Unable to determine type using JavaParser: " + ex.getMessage());
        }

        // Strategy 2: find SAM manually
        try {
            ResolvedType lambdaType = TypeResolver.sSymbolResolver.calculateType(lambda);
            ResolvedReferenceTypeDeclaration typeDecl = lambdaType
                    .asReferenceType()
                    .getTypeDeclaration()
                    .get();

            // find the single abstract method manually
            ResolvedMethodDeclaration sam = typeDecl.getDeclaredMethods()
                    .stream()
                    .filter(m -> m.isAbstract() && !m.isStatic())
                    .filter(m -> !isObjectMethod(m))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No SAM found"));

            return sam.getReturnType().describe();
        } catch (Exception ex) {
            System.out.println("Unable to determine type using SAM: " + ex.getMessage());
        }

        if (SpoonResolver.lambdaReturnType != null) {
            System.out.println("The return type of the lambda has been set by SpoonResolver, using it: " + SpoonResolver.lambdaReturnType);
            return SpoonResolver.lambdaReturnType;
        }

        System.out.println("Default to object");
        return "Object";
    }
    @Override
    public void visit(ContinueStmt continueStmt, Void arg) {
        super.visit(continueStmt, arg);

        if (withoutLoop(continueStmt)) {
            System.out.println("Found continue @ " + continueStmt);
            // Replace break with return
//            ReturnStmt returnStmt = new ReturnStmt();
//            continueStmt.replace(returnStmt);
            if (afterBlock != null) {
                System.out.println("Replacing continue statement with return statement: " + continueStmt);
                replaceWithReturnAndInsert(continueStmt, afterBlock, null);
            }
            shouldBeReplaced = true;
        }
    }

    /**
     * Replaces a target statement with a ReturnStmt, and inserts a sequence of
     * statements before it in the parent BlockStmt.
     *
     * @param targetStmt    The statement to replace with a ReturnStmt
     */
    private void replaceWithReturnAndInsert(Statement targetStmt, BlockStmt insertedBlock, Statement returnStmt) {
        if (!targetStmt.getParentNode().isPresent()) {
            throw new IllegalArgumentException("Target statement has no parent node");
        }

        Node parent = targetStmt.getParentNode().get();
        NodeList<Statement> oldStmts;
        if (parent instanceof BlockStmt) {
            oldStmts = ((BlockStmt) parent).getStatements();
        } else if (parent instanceof SwitchEntry) {
            oldStmts = ((SwitchEntry) parent).getStatements();
        } else {
            throw new IllegalArgumentException("Parent of target statement is neither BlockStmt nor SwitchEntry, it is: " + parent.getClass().getSimpleName());
        }

        // Find the index of the target statement
        int targetIndex = -1;
        for (int i = 0; i < oldStmts.size(); i++) {
            if (oldStmts.get(i) == targetStmt) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex == -1) {
            throw new IllegalStateException("Target statement not found in parent BlockStmt");
        }

        // Build a new NodeList with the changes applied
        NodeList<Statement> newStmts = new NodeList<>();

        // Add all statements before the target
        for (int i = 0; i < targetIndex; i++) {
            newStmts.add(oldStmts.get(i));
        }

        // Insert the new statements before the return
        for (Statement s : insertedBlock.getStatements()) {
            newStmts.add(s);
        }

        // Add the return statement in place of the target
        if (returnStmt != null) {
            newStmts.add(returnStmt);
        } else if (!isExliMode) {
            newStmts.add(new ReturnStmt());
        } else {
            // is Exli mode, we do not want to skip any statements, so we add the original statement back if returnStmt is null
            targetIndex -= 1;
        }

        // Add all statements after the target
        for (int i = targetIndex + 1; i < oldStmts.size(); i++) {
            newStmts.add(oldStmts.get(i));
        }


        // Replace the entire statement list at once
        if (parent instanceof BlockStmt) {
            ((BlockStmt) parent).setStatements(newStmts);
        } else {
            ((SwitchEntry) parent).setStatements(newStmts);
        }
    }

    private boolean withoutLoop(Statement breakStmt) {
        Node parent = breakStmt.getParentNode().orElse(null);

        while (parent != null) {
            if (parent instanceof ForStmt ||
                    parent instanceof ForEachStmt ||
                    parent instanceof WhileStmt ||
                    parent instanceof DoStmt) {
                System.out.println("Break is in LOOP");
                return false;
            }

            System.out.println("BREAK PARENT IS " + parent);
            // Stop if we hit a switch statement
            if (parent instanceof SwitchStmt) {
                System.out.println("Break is not in LOOP (it is in Switch)");
                return false;
            }
            parent = parent.getParentNode().orElse(null);
        }
        System.out.println("Break is not in LOOP/SWITCH");
        return true;
    }

    private static boolean isObjectMethod(ResolvedMethodDeclaration m) {
        return OBJECT_METHODS.contains(m.getName());
    }
}
