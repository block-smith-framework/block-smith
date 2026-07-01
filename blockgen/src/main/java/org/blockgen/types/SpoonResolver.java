package org.blockgen.types;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class SpoonResolver {

    public static boolean setUpOK = false;

    private static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait", "finalize", "clone"
    ));

    private static CtModel model;
    private static String filePath;
    private static int startLine;
    private static int endLine;

    public static Map<String, String> variableTypes = new LinkedHashMap<>();
    public static Map<String, MethodCallInfo> methodCalls = new LinkedHashMap<>();
    public static Set<String> classesUsed = new LinkedHashSet<>();
    public static Set<String> staticFieldsUsed = new LinkedHashSet<>();
    public static String lambdaReturnType = null;
    public static Set<String> allFields = null;

    public static void setup(String filePath, int start, int end) throws Exception {
        pretty(filePath, start, end, true);

        if (!SpoonResolver.filePath.equals(filePath)) {
            Files.copy(Paths.get(filePath), Paths.get(SpoonResolver.filePath + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Paths.get(SpoonResolver.filePath), Paths.get(filePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Launcher launcher = new Launcher();

            // Add source
            launcher.addInputResource(filePath);

            // Build classpath
            List<String> classpath = new ArrayList<>();

            String javaHome = System.getProperty("java.home");
            File rtJar = new File(javaHome + File.separator + "lib" + File.separator + "rt.jar");
            classpath.add(rtJar.getAbsolutePath());

            // Maven dependencies — run: mvn dependency:build-classpath -Dmdep.outputFile=deps.txt
            // mvn test-compile, then add target/classes
            if (TypeResolverUtil.depClassPaths == null) {
                throw new RuntimeException("Dependency classpath not set. Run: mvn dependency:build-classpath -Dmdep.outputFile=classpath.txt");
            }

            classpath.addAll(Arrays.asList(TypeResolverUtil.depClassPaths.split(File.pathSeparator)));


            launcher.getEnvironment().setNoClasspath(false);
            launcher.getEnvironment().setSourceClasspath(classpath.toArray(new String[0]));

            try {
                SpoonResolver.model = launcher.buildModel();
            } catch (Exception ex) {
                if (!SpoonResolver.filePath.equals(filePath)) {
                    System.out.println("Retrying with no pretty...");
                    Files.copy(Paths.get(SpoonResolver.filePath + ".bak"), Paths.get(filePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    pretty(filePath, start, end, false);

                    Launcher launcher2 = new Launcher();
                    launcher2.addInputResource(filePath);
                    launcher2.getEnvironment().setNoClasspath(false);
                    launcher2.getEnvironment().setSourceClasspath(classpath.toArray(new String[0]));
                    SpoonResolver.model = launcher2.buildModel();
                }
            }

            processMethods();
            System.out.println("methodCalls: " + methodCalls);

            processVariables();
            System.out.println("variableTypes: " + variableTypes);

            processClasses();
            System.out.println("classesUsed: " + classesUsed);
            System.out.println("staticFieldsUsed: " + staticFieldsUsed);

            processLambdas();
            System.out.println("lambdaReturnType: " + lambdaReturnType);

            processAllFields();
            System.out.println("allFields: " + allFields);

            SpoonResolver.setUpOK = true;
        } finally {
            if (!SpoonResolver.filePath.equals(filePath)) {
                // Restore file
                System.out.println("Restoring file...");
                Files.copy(Paths.get(SpoonResolver.filePath + ".bak"), Paths.get(filePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static void pretty(String filePath, int start, int end, boolean pretty) throws Exception {
        if (!pretty) {
            System.out.println("Pretty is disabled. Using original line numbers, but type resolution may be less accurate.");
            SpoonResolver.startLine = start;
            SpoonResolver.endLine = end;
            SpoonResolver.filePath = filePath;
            return;
        }

        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath));
            String tmpFile = Files.createTempFile("pretty", ".java").toString();

            lines.add(end, " // END_LINE_INDICATOR");
            lines.add(start - 1, " // START_LINE_INDICATOR");
            Files.write(Paths.get(tmpFile), lines);

            CompilationUnit cu = StaticJavaParser.parse(Paths.get(tmpFile));

            // handle a few common Lombok annotations
            cu.findAll(FieldDeclaration.class).forEach(field -> {
                field.removeModifier(Modifier.Keyword.FINAL);
            });
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                boolean hasSlf4j = cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Slf4j"));
                boolean hasLog4j2 = cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Log4j2"));
                if (hasSlf4j) {
                    FieldDeclaration loggerField = StaticJavaParser.parseBodyDeclaration("private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(" + cls.getNameAsString() + ".class);").asFieldDeclaration();
                    cls.getMembers().add(0, loggerField);
                } else if (hasLog4j2) {
                    FieldDeclaration loggerField = StaticJavaParser.parseBodyDeclaration("private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(" + cls.getNameAsString() + ".class);").asFieldDeclaration();
                    cls.getMembers().add(0, loggerField);
                }
                boolean hasData = cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Data"));
                boolean hasGetter = cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Getter"));
                boolean hasSetter = cls.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Setter"));
                List<FieldDeclaration> fields = cls.findAll(FieldDeclaration.class);
                if (hasData || hasGetter) {
                    fields.forEach(field -> {
                        String fieldName = field.getVariable(0).getNameAsString();
                        String fieldType = field.getVariable(0).getTypeAsString();
                        String capitalised = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                        boolean isBoolean = fieldType.equals("boolean") || fieldType.equals("Boolean");
                        String prefix = isBoolean ? "is" : "get";
                        String getter = "public " + fieldType + " " + prefix + capitalised + "() { return this." + fieldName + "; }";
                        cls.getMembers().add(StaticJavaParser.parseBodyDeclaration(getter));
                    });
                }
                if (hasData || hasSetter) {
                    fields.stream().filter(field -> !field.isFinal())
                        .forEach(field -> {
                            String fieldName = field.getVariable(0).getNameAsString();
                            String fieldType = field.getVariable(0).getTypeAsString();
                            String capitalised = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                            String setter = "public void set" + capitalised + "(" + fieldType + " " + fieldName + ") { this." + fieldName + " = " + fieldName + "; }";
                            cls.getMembers().add(StaticJavaParser.parseBodyDeclaration(setter));
                        });
                }
            });
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            String reformatted = printer.print(cu);

            System.out.println("Tmp file at " + tmpFile);
            FileWriter writer = new FileWriter(tmpFile);
            writer.write(reformatted);
            writer.close();

            System.out.println("Original range: " + start + "-" + end);
            int newStart = -1;
            int newEnd = -1;
            List<String> newLines = Files.readAllLines(Paths.get(tmpFile));
            for (int i = 0; i < newLines.size(); i++) {
                if (newLines.get(i).contains("START_LINE_INDICATOR")) {
                    if (newLines.get(i).trim().equals("// START_LINE_INDICATOR")) {
                        newStart = i + 1;
                    } else {
                        newStart = i;
                    }
                }
                if (newLines.get(i).contains("END_LINE_INDICATOR")) {
                    if (newLines.get(i).trim().equals("// END_LINE_INDICATOR")) {
                        newEnd = i + 1;
                    } else {
                        newEnd = i + 2;
                    }
                }
            }

            if (newStart == -1 || newEnd == -1) {
                System.out.println("Need to retry: New range: " + newStart + "-" + newEnd + " in file " + tmpFile);

                lines = Files.readAllLines(Paths.get(filePath));
                lines.set(start - 1, lines.get(start - 1) + " // START_LINE_INDICATOR");
                lines.set(end - 1, lines.get(end - 1) + " // END_LINE_INDICATOR");
                Files.write(Paths.get(tmpFile), lines);

                cu = StaticJavaParser.parse(Paths.get(tmpFile));

                printer = new DefaultPrettyPrinter();
                reformatted = printer.print(cu);

                writer = new FileWriter(tmpFile);
                writer.write(reformatted);
                writer.close();

                newStart = -1;
                newEnd = -1;
                newLines = Files.readAllLines(Paths.get(tmpFile));
                for (int i = 0; i < newLines.size(); i++) {
                    if (newLines.get(i).contains("START_LINE_INDICATOR")) {
                        newStart = i + 1;
                    }
                    if (newLines.get(i).contains("END_LINE_INDICATOR")) {
                        newEnd = i + 2;
                    }
                }
            }
            System.out.println("New range: " + newStart + "-" + newEnd + " in file " + tmpFile);

            if (newStart == -1 || newEnd == -1) {
                throw new RuntimeException("Failed to find line indicators after pretty-printing.");
            } else {
                SpoonResolver.startLine = newStart;
                SpoonResolver.endLine = newEnd;
                SpoonResolver.filePath = tmpFile;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            SpoonResolver.startLine = start;
            SpoonResolver.endLine = end;
            SpoonResolver.filePath = filePath;
        }
    }

    private static void processMethods() {
        Set<CtInvocation<?>> seen = new HashSet<>();
        model.getAllTypes().forEach(type -> {
            type.filterChildren((CtElement e) -> {
                        if (!e.getPosition().isValidPosition()) return false;
                        int line = e.getPosition().getLine();
                        return line >= startLine && line <= endLine;
                    })
                    .filterChildren((CtElement e) -> e instanceof CtInvocation)
                    .forEach((CtElement e) -> {
                        CtInvocation<?> invocation = (CtInvocation<?>) e;
                        if (seen.add(invocation)) { // only process each node once
                            String methodName = invocation.getExecutable().getSimpleName();
                            CtTypeReference<?> returnType = invocation.getType();
                            boolean isStatic = invocation.getExecutable().isStatic();
                            String prefix = getInvocationPrefix(invocation.getTarget());

                            List<CtExpression<?>> args = invocation.getArguments();
                            String params = args.stream()
                                    .map(SpoonResolver::getSourceText)
                                    .collect(Collectors.joining(", "));
                            methodCalls.put(prefix + methodName + "(" + params + ")", new MethodCallInfo(prefix + methodName + "(" + params + ")", isStatic, returnType != null ? returnType.toString() : "unknown"));
                        }
                    });
        });
    }

    private static void processVariables() {
        model.getAllTypes().forEach(type -> {
            type.filterChildren((CtElement e) -> {
                        if (!e.getPosition().isValidPosition()) return false;
                        int line = e.getPosition().getLine();
                        return line >= startLine && line <= endLine;
                    })
                    .filterChildren((CtElement e) ->
                            e instanceof CtVariableRead
                                    || e instanceof CtVariableWrite
                                    || e instanceof CtFieldRead
                                    || e instanceof CtFieldWrite
                    )
                    .forEach((CtElement e) -> {
                        String name = null;
                        CtTypeReference<?> varType = null;

                        if (e instanceof CtFieldWrite) {
                            CtFieldWrite<?> fieldWrite = (CtFieldWrite<?>) e;
                            String simpleName = fieldWrite.getVariable().getSimpleName();
                            CtTypeReference<?> fieldType = fieldWrite.getType();
                            String typeName = fieldType != null ? fieldType.toString() : "unknown";
                            String prefix = getInvocationPrefix(fieldWrite.getTarget());
                            variableTypes.put(prefix + simpleName, typeName);
                            variableTypes.put(simpleName, typeName);
                            variableTypes.put(getSourceText(fieldWrite), typeName);
                        } else if (e instanceof CtFieldRead) {
                            CtFieldRead<?> fieldRead = (CtFieldRead<?>) e;
                            String simpleName = fieldRead.getVariable().getSimpleName();
                            CtTypeReference<?> fieldType = fieldRead.getType();
                            String typeName = fieldType != null ? fieldType.toString() : "unknown";
                            String prefix = getInvocationPrefix(fieldRead.getTarget());
                            variableTypes.put(prefix + simpleName, typeName);
                            variableTypes.put(simpleName, typeName);
                            variableTypes.put(getSourceText(fieldRead), typeName);
                        } else if (e instanceof CtVariableRead) {
                            CtVariableRead<?> varRead = (CtVariableRead<?>) e;
                            name = varRead.getVariable().getSimpleName();
                            varType = varRead.getType();
                            variableTypes.put(getSourceText(varRead), varType != null ? varType.toString() : "unknown");
                        } else if (e instanceof CtVariableWrite) {
                            CtVariableWrite<?> varWrite = (CtVariableWrite<?>) e;
                            name = varWrite.getVariable().getSimpleName();
                            varType = varWrite.getType();
                            variableTypes.put(getSourceText(varWrite), varType != null ? varType.toString() : "unknown");
                        }

                        if (name != null) {
                            variableTypes.put(name, varType != null ? varType.toString() : "unknown");
                        }
                    });
        });
    }

    private static void processClasses() {
        model.getAllTypes().forEach(type -> {
            type.filterChildren((CtElement e) -> {
                        if (!e.getPosition().isValidPosition()) return false;
                        int line = e.getPosition().getLine();
                        return line >= startLine && line <= endLine;
                    })
                    .filterChildren((CtElement e) -> e instanceof CtTypeAccess)
                    .forEach((CtElement e) -> {
                        CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) e;
                        if (!typeAccess.isImplicit()) {
                            classesUsed.add(typeAccess.getAccessedType().getQualifiedName());
                            classesUsed.add(typeAccess.getAccessedType().getSimpleName());
                        }
                    });
        });

        model.getAllTypes().forEach(type -> {
            type.filterChildren((CtElement e) -> {
                        if (!e.getPosition().isValidPosition()) return false;
                        int line = e.getPosition().getLine();
                        return line >= startLine && line <= endLine;
                    })
                    .filterChildren((CtElement e) -> e instanceof CtFieldRead)
                    .forEach((CtElement e) -> {
                        CtFieldRead<?> fieldRead = (CtFieldRead<?>) e;
                        if (fieldRead.getVariable().isStatic() && fieldRead.getTarget() instanceof CtTypeAccess) {
                            CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) fieldRead.getTarget();
                            CtType<?> typeDecl = typeAccess.getAccessedType().getTypeDeclaration();
                            if (typeDecl instanceof CtEnum) {
                                String typeName = typeAccess.getAccessedType().getSimpleName();
                                String fieldName = fieldRead.getVariable().getSimpleName();
                                classesUsed.add(typeName);
                                classesUsed.add(typeName + "." + fieldName);
                                classesUsed.add(fieldName);
                            } else {
                                String typeName = typeAccess.getAccessedType().getSimpleName();
                                String fieldName = fieldRead.getVariable().getSimpleName();
                                staticFieldsUsed.add(typeName + "." + fieldName);
                                if (type.getSimpleName().equals(typeName)) {
                                    staticFieldsUsed.add(fieldName);
                                }
                            }
                        }
                    });

            type.filterChildren((CtElement e) -> {
                        if (!e.getPosition().isValidPosition()) return false;
                        int line = e.getPosition().getLine();
                        return line >= startLine && line <= endLine;
                    })
                    .filterChildren((CtElement e) -> e instanceof CtCase)
                    .forEach((CtElement e) -> {
                        CtCase<?> ctCase = (CtCase<?>) e;
                        ctCase.getCaseExpressions().forEach(expr -> {
                            if (expr instanceof CtFieldRead) {
                                CtFieldRead<?> fieldRead = (CtFieldRead<?>) expr;
                                CtTypeReference<?> declaringType = fieldRead.getVariable().getDeclaringType();
                                if (declaringType != null) {
                                    CtType<?> typeDecl = declaringType.getTypeDeclaration();
                                    boolean isEnum = typeDecl instanceof CtEnum;
                                    String fieldName = fieldRead.getVariable().getSimpleName();
                                    String typeName = declaringType.getSimpleName();
                                    if (isEnum) {
                                        classesUsed.add(typeName);
                                        classesUsed.add(typeName + "." + fieldName);
                                        classesUsed.add(fieldName);
                                    }
                                }
                            }
                        });
                    });
        });
    }

    private static void processLambdas() {
        CtLambda<?> best = null;
        int bestSize = Integer.MAX_VALUE;

        for (CtLambda<?> lambda : model.getElements(new TypeFilter<>(CtLambda.class))) {
            SourcePosition pos = lambda.getPosition();
            if (!pos.isValidPosition()) {
                continue;
            }
            int start = pos.getLine();
            int end   = pos.getEndLine();
            int lineNumber = (endLine - startLine)/2 + startLine;

            if (lineNumber >= start && lineNumber <= end) {
                int size = end - start;
                if (size < bestSize) {
                    bestSize = size;
                    best = lambda;
                }
            }
        }

        if (best != null) {
            System.out.println("Innermost lambda is at lines " + best.getPosition().getLine() + "-" + best.getPosition().getEndLine());
            System.out.println("lambda inferred return: " + resolveLambdaReturnType(best));
            CtTypeReference<?> lambdaType = resolveLambdaReturnType(best);
            if (lambdaType != null)
                lambdaReturnType = lambdaType.toString();
        }
    }

    private static void processAllFields() {
        try {
            Optional<CtType<?>> targetType = model.getAllTypes().stream()
                    .filter(type -> {
                        if (!type.getPosition().isValidPosition()) return false;
                        int start = type.getPosition().getLine();
                        int end = type.getPosition().getEndLine();
                        return start <= startLine && end >= endLine;
                    })
                    .min(Comparator.comparingInt(type ->
                            type.getPosition().getEndLine() - type.getPosition().getLine()
                    ));

            if (targetType.isPresent()) {
                allFields = new LinkedHashSet<>();

                for (CtFieldReference<?> fieldRef : targetType.get().getAllFields()) {
                    allFields.add(fieldRef.getSimpleName());
                }
            }
        } catch (Exception ex) {
            System.out.println("Error processing fields: " + ex.getMessage());
        }
    }

    private static String getInvocationPrefix(CtExpression<?> target) {
        if (target == null) {
            return "";
        } else if (target instanceof CtThisAccess) {
            CtThisAccess<?> thisAccess = (CtThisAccess<?>) target;
            // implicit this — no prefix (covers static imports too)
            if (thisAccess.isImplicit()) {
                return "";
            }
            return "this.";
        } else if (target instanceof CtTypeAccess) {
            CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) target;
            // implicit type access — static import, no prefix
            if (typeAccess.isImplicit()) {
                return "";
            }
            return typeAccess.getAccessedType().getSimpleName() + ".";
        } else if (target instanceof CtFieldRead) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) target;
            return getInvocationPrefix(fieldRead.getTarget()) + fieldRead.getVariable().getSimpleName() + ".";
        } else if (target instanceof CtVariableRead) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) target;
            return varRead.getVariable().getSimpleName() + ".";
        } else if (target instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) target;
            String args = inv.getArguments().stream()
                    .map(SpoonResolver::getSourceText)
                    .collect(Collectors.joining(", "));
            return getInvocationPrefix(inv.getTarget()) + inv.getExecutable().getSimpleName() + "(" + args + ").";
        } else {
            return "";
        }
    }

    private static String getVariablePrefix(CtExpression<?> target) {
        if (target instanceof CtThisAccess) {
            return "this.";
        } else if (target instanceof CtTypeAccess) {
            CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) target;
            return typeAccess.getAccessedType().getSimpleName() + ".";
        } else {
            return "";
        }
    }

    private static String getSourceText(CtExpression<?> arg) {
        // TODO: use JavaParser to get exact source text instead of reading from file (handles formatting, comments, etc.)
        SourcePosition pos = arg.getPosition();
        if (pos.isValidPosition()) {
            try {
                List<String> lines = Files.readAllLines(Paths.get(pos.getFile().getAbsolutePath()));
                if (pos.getLine() == pos.getEndLine()) {
                    return lines.get(pos.getLine() - 1)
                            .substring(pos.getColumn() - 1, pos.getEndColumn());
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = pos.getLine(); i <= pos.getEndLine(); i++) {
                        String line = lines.get(i - 1);
                        if (i == pos.getLine()) {
                            sb.append(line.substring(pos.getColumn() - 1));
                        } else if (i == pos.getEndLine()) {
                            sb.append(line, 0, pos.getEndColumn());
                        } else {
                            sb.append(line.trim());
                        }
                    }
                    return sb.toString().trim();
                }
            } catch (Exception ex) {
                return arg.toString();
            }
        }
        return arg.toString();
    }

    private static CtTypeReference<?> resolveLambdaReturnType(CtLambda<?> lambda) {
        CtTypeReference<?> funcType = lambda.getType();
        if (funcType == null) return null;

        CtType<?> typeDecl = funcType.getTypeDeclaration();
        if (typeDecl == null) return null;

        // find the SAM
        Optional<CtMethod<?>> sam = typeDecl.getMethods().stream()
                .filter(m -> !OBJECT_METHODS.contains(m.getSimpleName()))
                .filter(m -> !m.hasModifier(ModifierKind.STATIC))
                .filter(m -> m.getBody() == null) // abstract methods have no body
                .findFirst();

        if (!sam.isPresent()) return null;

        CtTypeReference<?> rawReturnType = sam.get().getType();

        // if not a type parameter, return as-is
        if (!(rawReturnType instanceof CtTypeParameterReference)) {
            return rawReturnType;
        }

        // map type parameter name to actual type argument
        // e.g. Function<T, R> -> map T->Expression, R->Function<CharSequence, CharSequence>
        List<CtTypeParameter> typeParams = typeDecl.getFormalCtTypeParameters();
        List<CtTypeReference<?>> typeArgs = funcType.getActualTypeArguments();

        for (int i = 0; i < typeParams.size() && i < typeArgs.size(); i++) {
            if (typeParams.get(i).getSimpleName().equals(rawReturnType.getSimpleName())) {
                return typeArgs.get(i);
            }
        }

        return rawReturnType;
    }

    public static class MethodCallInfo {
        public String methodName;
        public boolean isStatic;
        public String returnType;

        public MethodCallInfo(String methodName, boolean isStatic, String returnType) {
            this.methodName = methodName;
            this.isStatic = isStatic;
            this.returnType = returnType;
        }

        public String toString() {
            return methodName + " returns: " + returnType + (isStatic ? " [static]" : "");
        }
    }
}
