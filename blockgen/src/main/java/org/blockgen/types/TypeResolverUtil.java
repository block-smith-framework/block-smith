package org.blockgen.types;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import org.blockgen.Constant;

import java.util.*;
import java.util.stream.Collectors;

public class TypeResolverUtil {
    public static String depClassPaths;
    public static String appSrcPath;
    public static boolean useSpoonFirst = false;

    public static Map<String, Set<String>> inferredTypesMap = new HashMap<>();

    public static String calculateType(Expression target) {
        String type = calculateTypeFromExpression(target);
        if (!Constant.removeFQNType.isEmpty()) {
            // If prefix matches, drop the prefix and the dot. (e.g., Dictionary is in removeFQNType and we have Dictionary.TxtWord -> TxtWord)
            for (String prefix : Constant.removeFQNType) {
                if (type.startsWith(prefix + ".")) {
                    type = type.substring(prefix.length() + 1);
                    break;
                }
            }
        }

        if ((type.equals("Object") || type.equals("java.lang.Object")) && !inferredTypesMap.containsKey(target.toString())) {
            System.out.println("Type is Object, checking inferred types...");
            Set<String> inferredTypes = findInferredTypes(target);
            System.out.println("Inferred types: " + inferredTypes);
            inferredTypesMap.put(target.toString(), inferredTypes);
        }
        return type;
    }

    public static String calculateTypeFromExpression(Expression target) {
        if (useSpoonFirst) {
            if (SpoonResolver.setUpOK && SpoonResolver.variableTypes.containsKey(target.toString())) {
                String type = SpoonResolver.variableTypes.get(target.toString());
                System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                if (type.equals("?"))
                    return "Object";
                return type;
            } else if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(target.toString())) {
                String type = SpoonResolver.methodCalls.get(target.toString()).returnType;
                System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                if (type.equals("?"))
                    return "Object";
                return type;
            }
        }

        try {
            String type = TypeResolver.sSymbolResolver.calculateType(target).describe();
            type = getTypeFromStr(type).toString();
            System.out.println("calculateType: " + target.toString() + " has type " + type);
            if (type.contains("InferenceVariable_0") || type.length() == 1 || type.contains("<?>")) {
                System.out.println("Type is unlikely to be correct, checking SpoonResolver...");
                if (SpoonResolver.setUpOK && SpoonResolver.variableTypes.containsKey(target.toString())) {
                   type = SpoonResolver.variableTypes.get(target.toString());
                    System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                    if (type.equals("?"))
                        return "Object";
                    return type;
                } else if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(target.toString())) {
                    type = SpoonResolver.methodCalls.get(target.toString()).returnType;
                    System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                    if (type.equals("?"))
                        return "Object";
                    return type;
                }
            }
            return type;
        } catch (Exception ex) {
            if (SpoonResolver.setUpOK && SpoonResolver.variableTypes.containsKey(target.toString())) {
                String type = SpoonResolver.variableTypes.get(target.toString());
                System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                if (type.equals("?"))
                    return "Object";
                return type;
            } else if (SpoonResolver.setUpOK && SpoonResolver.methodCalls.containsKey(target.toString())) {
                String type = SpoonResolver.methodCalls.get(target.toString()).returnType;
                System.out.println("calculateType (from SpoonResolver): " + target.toString() + " has type " + type);
                if (type.equals("?"))
                    return "Object";
                return type;
            }
            throw ex;
        }
    }

    public static Type calculateTypeT(Expression target) {
        String type = TypeResolver.sSymbolResolver.calculateType(target).describe();
        return getTypeFromStr(type);
    }

    public static Type getTypeFromStr(String input) {
        input = input.trim();

        // Handle primitive types
        switch (input) {
            case "int":
                return new PrimitiveType(PrimitiveType.Primitive.INT);
            case "boolean":
                return new PrimitiveType(PrimitiveType.Primitive.BOOLEAN);
            case "char":
                return new PrimitiveType(PrimitiveType.Primitive.CHAR);
            case "byte":
                return new PrimitiveType(PrimitiveType.Primitive.BYTE);
            case "short":
                return new PrimitiveType(PrimitiveType.Primitive.SHORT);
            case "long":
                return new PrimitiveType(PrimitiveType.Primitive.LONG);
            case "float":
                return new PrimitiveType(PrimitiveType.Primitive.FLOAT);
            case "double":
                return new PrimitiveType(PrimitiveType.Primitive.DOUBLE);
            // Add cases for other primitives as needed
        }

        if (input.equals("T") || input.equals("? extends T") || input.equals("? super T"))
            return new ClassOrInterfaceType().setName("Object");

        // Handle array types
        if (input.endsWith("[]")) {
            // Handle wildcard types
            if (input.startsWith("?")) {
                if (input.startsWith("? extends ")) {
                    input = input.substring(10);
                } else if (input.startsWith("? super ")) {
                    input = input.substring(8);
                }
            }

            return new ArrayType(getTypeFromStr(input.substring(0, input.length() - 2)));
        }

        // Handle wildcard types
        if (input.startsWith("?")) {
            WildcardType wildcardType = new WildcardType();
            if (input.startsWith("? extends ")) {
//                wildcardType.setExtendedType(new ClassOrInterfaceType().setName(input.substring(10)));
                return new ClassOrInterfaceType().setName(input.substring(10));
            } else if (input.startsWith("? super ")) {
//                wildcardType.setSuperType(new ClassOrInterfaceType().setName(input.substring(8)));
                return new ClassOrInterfaceType().setName(input.substring(8));
            }
            return wildcardType;
        }

        // Handle union types (for multi-catch)
        if (input.contains("|")) {
            return new UnionType((NodeList<ReferenceType>) Arrays.stream(input.split("\\|"))
                    .map(String::trim)
                    .map(str -> (new ClassOrInterfaceType().setName(str)).asReferenceType())
                    .collect(Collectors.toList()));
        }

        // Handle intersection types (for generics)
        if (input.contains("&")) {
            return new IntersectionType((NodeList<ReferenceType>) Arrays.stream(input.split("&"))
                    .map(String::trim)
                    .map(str -> (new ClassOrInterfaceType().setName(str)).asReferenceType())
                    .collect(Collectors.toList()));
        }

        // Handle generic types (simplified example)
        if (input.contains("<") && input.contains(">")) {
            String baseType = input.substring(0, input.indexOf('<'));
            String typeParam = input.substring(input.indexOf('<') + 1, input.lastIndexOf('>'));
            return new ClassOrInterfaceType()
                    .setName(baseType)
                    .setTypeArguments(getTypeFromStr(typeParam));
        }

        // Handle simple class or interface types
        return new ClassOrInterfaceType().setName(input);
    }

    public static Expression getTypeDefaultValue(String input) {
        if (input.equals("int")) {
            return new IntegerLiteralExpr();
        } else if (input.equals("boolean")) {
            return new BooleanLiteralExpr();
        } else if (input.equals("char")) {
            return new CharLiteralExpr();
        } else if (input.equals("byte")) {
            return new IntegerLiteralExpr();
        } else if (input.equals("short")) {
            return new IntegerLiteralExpr();
        } else if (input.equals("long")) {
            return new LongLiteralExpr();
        } else if (input.equals("float")) {
            return new DoubleLiteralExpr();
        } else if (input.equals("double")) {
            return new DoubleLiteralExpr();
        } else {
            return new NullLiteralExpr();
        }
    }

    public static Set<String> findInferredTypes(Expression targetExpr) {
        Set<String> types = new LinkedHashSet<String>();

        // Walk up to the enclosing method declaration
        MethodDeclaration method = targetExpr
                .findAncestor(MethodDeclaration.class)
                .orElse(null);
        if (method == null || !method.getBody().isPresent()) {
            return types;
        }

        String targetStr = targetExpr.toString();

        // instanceof checks: expr instanceof Foo
        List<InstanceOfExpr> instanceOfs = method.findAll(InstanceOfExpr.class);
        for (InstanceOfExpr ioe : instanceOfs) {
            if (ioe.getExpression().toString().equals(targetStr)) {
                types.add(ioe.getType().asString());
            }
        }

        // cast expressions: (Foo) expr
        List<CastExpr> casts = method.findAll(CastExpr.class);
        for (CastExpr cast : casts) {
            Expression inner = unwrapEnclosed(cast.getExpression());
            if (inner.toString().equals(targetStr)) {
                types.add(cast.getType().asString());
            }
        }

        return types;
    }

    /**
     * Unwraps any number of EnclosedExpr layers: ((expr)) -> expr
     */
    private static Expression unwrapEnclosed(Expression expr) {
        while (expr instanceof EnclosedExpr) {
            expr = ((EnclosedExpr) expr).getInner();
        }
        return expr;
    }
}
