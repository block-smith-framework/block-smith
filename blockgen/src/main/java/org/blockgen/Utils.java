package org.blockgen;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import org.apache.commons.text.StringEscapeUtils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.apache.maven.shared.invoker.*;
import org.blockgen.helpers.BlockTestRemover;
import org.blockgen.types.TypeResolverUtil;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

    private static String classesDirectory = "";
    private static String testClassesDirectory = "";

    // Generation (Source: from Exli)

    public static int parseLineNumber(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        } catch (NullPointerException e) {
            return -1;
        }
    }

    public static String createDir(String dirName) {
        String currentDir = System.getProperty("user.dir");
        String dir = currentDir + "/" + dirName;
        // create directory for inline tests if not exist
        try {
            Files.createDirectories(Paths.get(dir));
            return dir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isConstant(String name) {
        return name.toUpperCase().equals(name);
    }

    public static boolean isValidVariableName(String name) {
        String pattern = "^[a-zA-Z_$][a-zA-Z0-9_$]*$";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(name);
        return m.matches();
    }

    public static boolean isStartWithLowerCase(String name) {
        return (Character.isLowerCase(name.charAt(0)) || (name.charAt(0) == '_' && Character.isLowerCase(name.charAt(1))));
    }

    public static String rename(String input) {
        if (input.startsWith("this.")) {
            input = input.substring(5);
        }
        if (input.startsWith("super.")) {
            input = input.substring(6);
        }
        return input.replace("*", "time").replace("+", "plus").replace("-", "minus").replace("/", "divide")
                .replace("=", "equal").replace("!", "not").replace(">", "greater").replace("<", "less")
                .replace("&", "and").replace("|", "or").replace("^", "xor").replace("%", "mod").replace("?", "question")
                .replace("(", "_").replace(")", "_").replace(" ", "").replace("[", "__").replace("]", "")
                .replace("\"", "_")
                .replace(".", "__").replace(",", "_").replace("'", "_").replace(":","_");
    }

    public static Statement buildLogStatement(String prompt, String variable, Context ctx) {
        if (prompt.equals(Constant.TARGET_STMT_FLOW)) {
            String logStmtStr = (Constant.useFQN ? Constant.LOG_CLASS_IMPORT : Constant.LOG_CLASS_NAME) + ".logVariableAndGenerateTest("
                    + "\"" + prompt + "\""
                    + ", " + "\"" + ctx.logPath + "\""
                    + ", " + "\"" + ctx.r0TestPath + "\""
                    + ", " + "\"" + ctx.r1TestPath + "\""
                    + ", " + "\"" + ctx.srcPath + "\""
                    + ", " + (ctx.startLineNumber)
                    + ", " + (ctx.endLineNumber)
                    + ", BLOCKGEN_FLOW_INTERNAL.toString()"
                    + ", " + "\"" + Utils.escapeString(variable) + "\""
                    + ", " + ctx.className + ".class"
                    + ", " + "\"" + ctx.classesDirectory + "\""
                    + ", " + "\"" + ctx.message + "\""
                    + ");";
            return StaticJavaParser.parseStatement(logStmtStr);
        }

        String logStmtStr = (Constant.useFQN ? Constant.LOG_CLASS_IMPORT : Constant.LOG_CLASS_NAME) + ".logVariableAndGenerateTest("
                + "\"" + prompt + "\""
                + ", " + "\"" + ctx.logPath + "\""
                + ", " + "\"" + ctx.r0TestPath + "\""
                + ", " + "\"" + ctx.r1TestPath + "\""
                + ", " + "\"" + ctx.srcPath + "\""
                + ", " + (ctx.startLineNumber)
                + ", " + (ctx.endLineNumber)
                + ", " + (prompt.equals(Constant.MOCKING) ? "null" : variable)
                + ", " + "\"" + Utils.escapeString(variable) + "\""
                + ", " + ctx.className + ".class"
                + ", " + "\"" + ctx.classesDirectory + "\""
                + ", " + "\"" + ctx.message + "\""
                + ");";
        return StaticJavaParser.parseStatement(logStmtStr);
    }

    public static Statement buildLogStatementWithType(String prompt, String variable, String type, Context ctx) {
        if (type == null) {
            return buildLogStatement(prompt, variable, ctx);
        }

        /*
        // Handle generic type (evaluate type at runtime), does not work (e.g., List<F> can't be checked)
        boolean isGeneric = false;
        if (!ctx.genericTypes.isEmpty()) {
            for (TypeParameter genericType : ctx.genericTypes) {
                if (type.equals(genericType.getNameAsString())) {
                    isGeneric = true;
                    break;
                }
            }
        }
        if (!ctx.classGenericTypes.isEmpty()) {
            for (TypeParameter genericType : ctx.classGenericTypes) {
                if (type.equals(genericType.getNameAsString())) {
                    isGeneric = true;
                    break;
                }
            }
        }
        String variableName;
        if (isGeneric) {
            System.out.println("The type " + type + " is a generic type.");
            variableName = Utils.escapeString(variable); // get type at runtime instead
        } else {
            variableName = Utils.escapeString(variable + "@TYPE@" + type);
        }
         */
        String variableName = Utils.escapeString(variable + "@TYPE@" + type);

        String logStmtStr = (Constant.useFQN ? Constant.LOG_CLASS_IMPORT : Constant.LOG_CLASS_NAME) + ".logVariableAndGenerateTest("
                + "\"" + prompt + "\""
                + ", " + "\"" + ctx.logPath + "\""
                + ", " + "\"" + ctx.r0TestPath + "\""
                + ", " + "\"" + ctx.r1TestPath + "\""
                + ", " + "\"" + ctx.srcPath + "\""
                + ", " + (ctx.startLineNumber)
                + ", " + (ctx.endLineNumber)
                + ", " + (prompt.equals(Constant.MOCKING) ? "null" : variable)
                + ", " + "\"" + variableName + "\""
                + ", " + ctx.className + ".class"
                + ", " + "\"" + ctx.classesDirectory + "\""
                + ", " + "\"" + ctx.message + "\""
                + ");";
        return StaticJavaParser.parseStatement(logStmtStr);
    }

    public static Statement buildPromptStatement(String prompt, Context ctx) {
        String logStmtStr = (Constant.useFQN ? Constant.LOG_CLASS_IMPORT : Constant.LOG_CLASS_NAME) + ".logVariableAndGenerateTest("
                + "\"" + prompt + "\""
                + ", " + "\"" + ctx.logPath + "\""
                + ", " + "\"" + ctx.r0TestPath + "\""
                + ", " + "\"" + ctx.r1TestPath + "\""
                + ", " + "\"" + ctx.srcPath + "\""
                + ", " + (ctx.startLineNumber)
                + ", " + (ctx.endLineNumber)
                + ", " + "null"
                + ", " + "null"
                + ", " + ctx.className + ".class"
                + ", " + "\"" + ctx.classesDirectory + "\""
                + ", " + "\"" + ctx.message + "\""
                + ");";
        return StaticJavaParser.parseStatement(logStmtStr);
    }

    public static String escapeString(String str) {
        return StringEscapeUtils.escapeJava(str);
    }

    public static boolean compileMavenProject(String projectDir, String outputDir, String blockGenDir) {
        try {
            if (Files.exists(Paths.get(outputDir + File.separator + "compile-all.log"))) {
                int counter = 1;
                while (Files.exists(Paths.get(outputDir + File.separator + "compile-all.log." + counter))) {
                    counter += 1;
                }
                Files.copy(Paths.get(outputDir + File.separator + "compile-all.log"), Paths.get(outputDir + File.separator + "compile-all.log." + counter), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(Paths.get(outputDir + File.separator + "compile-all.log"));
            }

            try (FileWriter fw = new FileWriter(outputDir + File.separator + "compile-all.log", true);
                 BufferedWriter bw = new BufferedWriter(fw);
                 PrintWriter out = new PrintWriter(bw)) {

                InvocationRequest request = new DefaultInvocationRequest();
                request.setPomFile(new File(projectDir + File.separator + "pom.xml"));
                if (System.getenv("MAVEN_REPO") != null) {
                    request.addArg("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
                }
                request.addArg("test-compile");
                request.addArg("-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar");
                request.addArgs(Constant.SKIPS);
                request.setOutputHandler(out::println);
                request.setErrorHandler(out::println);
                request.setBatchMode(true);

                Invoker invoker = new DefaultInvoker();

                InvocationResult result = invoker.execute(request);
                return result.getExitCode() == 0;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /*
     * Mutation (Source: from Block Tests and Genie papers)
     */

    public static int compileSingleSource(String projectDir, String outputDir, String blockGenDir, String source, int mutantId) {
        Utils.replaceFile(outputDir + File.separator + "backup-tmp-" + mutantId + ".java", source);

        boolean removeOK = BlockTestRemover.removeBlockTests(source);
        if (!removeOK) {
            System.out.println("Failed to remove block tests, probably will not compile as well.");
        }
        List<String> command = new ArrayList<>(Arrays.asList("javac", "-cp",
                getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                        //  + projectDir + File.separator + Utils.getSourcePath() + File.pathSeparator (cause problem with Lombok)
                        + blockGenDir + File.separator + "libs" + File.separator + "blocktest-1.0.jar",
                "-d", getClassesDirectory(projectDir, outputDir, blockGenDir, false, false), source)); // do not cache getClassesDirectory, because we are using multiple threads
        System.out.println("Running the following command to compile single source: " + command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(projectDir));
        pb.redirectOutput(new File(outputDir + File.separator + "logs" + File.separator + "compile-mutant-" + mutantId + ".log"));
        pb.redirectErrorStream(true);
        try {
            return pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        } finally {
            Utils.moveFile(source, outputDir + File.separator + "backup-tmp-" + mutantId + ".java");
        }
        return -1;
    }

    /**
     * Write the classpath list to a file.
     * @param projectDir The project directory.
     * @param outputPathStr The output path string.
     */
    public static void writeClasspathList(String projectDir, String outputDir, String blockGenDir, String outputPathStr) {
        try (PrintWriter writer = new PrintWriter(outputPathStr)) {
            try (Stream<Path> stream = Files.walk(Paths.get(getClassesDirectory(projectDir, outputDir, blockGenDir, false)))) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        // package-info.class needs to be filtered out.
                        .filter(path -> !path.getFileName().toString().contains("package-info"))
                        .forEach(path -> // This lambda function turns a path into Java fqn.
                                writer.println(path.toString()
                                        .split(getClassesDirectory(projectDir, outputDir, blockGenDir, false) + File.separator)[1]
                                        .replace(".class", "")
                                        .replace(File.separatorChar, '.')
                                )
                        );
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /** This method builds the classpath list file for Randoop by removing all inner classes. */
    public static void writeRandoopClasspathList(String classpathList, String randoopClasspathList) {
        try {
            PrintWriter writer = new PrintWriter(randoopClasspathList);
            BufferedReader reader;
            try {
                reader = new BufferedReader(new FileReader(classpathList));
                String classpath = reader.readLine();
                while (classpath != null) {
                    // Exclude all inner classes and anonymous inner classes.
                    if (!classpath.contains("$")) {
                        writer.println(classpath);
                    }
                    classpath = reader.readLine();
                }
                reader.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            writer.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static boolean buildDeps(String projectDir, String outputDir, String blockGenDir) {
        ProcessBuilder pb;
        if (System.getenv("MAVEN_REPO") != null) {
            pb = new ProcessBuilder("mvn", "-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"), "-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar", "dependency:build-classpath", "-Dmdep.outputFile=" + projectDir + File.separator + "deps.txt");
        } else {
            pb = new ProcessBuilder("mvn", "-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar", "dependency:build-classpath", "-Dmdep.outputFile=" + projectDir + File.separator + "deps.txt");
        }
        pb.directory(new File(projectDir));
        try {
            pb.redirectOutput(new File(outputDir, "deps-build.log"));
            pb.redirectErrorStream(true);
            pb.start().waitFor();
            try {
                File depsFile = new File(projectDir + File.separator + "deps.txt");
                if (depsFile.exists()) {
                    FileWriter fw = new FileWriter(depsFile, true);
                    if (new File(projectDir + File.separator + "target" + File.separator + "classes").exists()) {
                        System.out.println("Adding project path: " + projectDir + File.separator + "target" + File.separator + "classes");
                        fw.write(File.pathSeparator + projectDir + File.separator + "target" + File.separator + "classes");
                    } else {
                        System.out.println("Target classes directory not found at " + projectDir + File.separator + "target" + File.separator + "classes. Attempting to get the correct target classes directory from Maven...");
                        String targetClasses = getClassesDirectory(projectDir, outputDir, blockGenDir, false);
                        if (targetClasses != null) {
                            System.out.println("Got target classes directory: " + targetClasses);
                            fw.write(File.pathSeparator + targetClasses);
                        } else {
                            System.out.println("Unable to get target classes directory. Please check the deps.txt file and make sure the classpath is correct.");
                            fw.close();
                            return false;
                        }
                    }

                    if (new File(projectDir + File.separator + "target" + File.separator + "test-classes").exists()) {
                        System.out.println("Adding project path: " + projectDir + File.separator + "target" + File.separator + "test-classes");
                        fw.write(File.pathSeparator + projectDir + File.separator + "target" + File.separator + "test-classes");
                    } else {
                        System.out.println("Target test classes directory not found at " + projectDir + File.separator + "target" + File.separator + "test-classes. Attempting to get the correct target test classes directory from Maven...");
                        String targetTestClasses = getClassesDirectory(projectDir, outputDir, blockGenDir, true);
                        if (targetTestClasses != null) {
                            System.out.println("Got target test classes directory: " + targetTestClasses);
                            fw.write(File.pathSeparator + targetTestClasses);
                        } else {
                            System.out.println("Unable to get target test classes directory. Please check the deps.txt file and make sure the classpath is correct.");
                            fw.close();
                            return false;
                        }
                    }
                    fw.close();
                } else {
                    System.out.println("Deps file not found at " + projectDir + File.separator + "deps.txt. Please check the Maven output log for errors.");
                    return false;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            return true;
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    public static String getClassesDirectory(String projectDir, String outputDir, String blockGenDir, boolean isTest) {
        return getClassesDirectory(projectDir, outputDir, blockGenDir, isTest, true);
    }

    public static String getClassesDirectory(String projectDir, String outputDir, String blockGenDir, boolean isTest, boolean cache) {
        if (cache && !isTest && !classesDirectory.isEmpty()) return classesDirectory;
        if (cache && isTest && !testClassesDirectory.isEmpty()) return testClassesDirectory;

        List<String> cmd = new ArrayList<>(Arrays.asList(
                "mvn", "help:evaluate", "-Dexpression=project.build." + (isTest ? "testOutputDirectory" : "outputDirectory"), "-q", "-DforceStdout"
        ));
        if (System.getenv("MAVEN_REPO") != null) {
            cmd.add("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(projectDir));
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String outputDirectory = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Picked up ")) {
                    continue;
                }
                outputDirectory = line;
            }
            process.waitFor();
            reader.close();
            String res = outputDirectory != null ? outputDirectory.trim() : null;
            if (res != null) {
                if (isTest) {
                    System.out.println("testClassesDirectory is " + res);
                    testClassesDirectory = res;
                } else {
                    System.out.println("classesDirectory is " + res);
                    classesDirectory = res;
                }
                return res;
            }
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
            return null;
        }

        List<String> cmd2 = new ArrayList<>(Arrays.asList(
                "mvn", "org.apache.maven.plugins:maven-help-plugin:3.4.0:evaluate", "-Dexpression=project.build." + (isTest ? "testOutputDirectory" : "outputDirectory"), "-q", "-DforceStdout"
        ));
        if (System.getenv("MAVEN_REPO") != null) {
            cmd2.add("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
        }
        ProcessBuilder pb2 = new ProcessBuilder(cmd2);
        pb2.directory(new File(projectDir));
        pb2.redirectErrorStream(true);

        try {
            Process process = pb2.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String outputDirectory = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Picked up ")) {
                    continue;
                }
                outputDirectory = line;
            }
            process.waitFor();
            reader.close();
            String res = outputDirectory != null ? outputDirectory.trim() : null;
            if (res == null) {
                System.out.println("Unable to find classes directory.");
            }

            if (isTest) {
                System.out.println("testClassesDirectory is " + res);
                testClassesDirectory = res;
            } else {
                System.out.println("classesDirectory is " + res);
                classesDirectory = res;
            }
            return res;
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    public static String getDepsContent(String projectDir, String outputDir, String blockGenDir) {
        String cpString = null;
        if (!new File(projectDir + File.separator + "deps.txt").exists()) {
            buildDeps(projectDir, outputDir, blockGenDir);
        }

        try {
            String raw = new String(Files.readAllBytes(Paths.get(projectDir + File.separator + "deps.txt")));
            if (raw.contains(".pom")) {
                System.out.println("Raw classpath contains .pom files, which may cause issues for Randoop. Attempting to filter out .pom files...");
                String filtered = Arrays.stream(raw.trim().split(File.pathSeparator))
                        .filter(p -> !p.endsWith(".pom") && !p.endsWith(".zip"))
                        .collect(Collectors.joining(File.pathSeparator));
                Files.write(Paths.get(projectDir + File.separator + "deps.txt"), filtered.getBytes());
            }

            if (!new File(projectDir + File.separator + "orig-deps.txt").exists()) {
                String filtered = Arrays.stream(raw.trim().split(File.pathSeparator))
                        .filter(p -> !p.contains("blocktest-1.0.jar") && !p.contains("blockgen-1.0.jar"))
                        .filter(p -> !p.endsWith(".pom") && !p.endsWith(".zip"))
                        .collect(Collectors.joining(File.pathSeparator));
                Files.write(Paths.get(projectDir + File.separator + "orig-deps.txt"), filtered.getBytes());
            }
        } catch (Exception ignored) {}


        try {
            cpString = Files.readAllLines(Paths.get(projectDir + File.separator + "deps.txt")).get(0);
        } catch (IOException | ArrayIndexOutOfBoundsException ex) {
            ex.printStackTrace();
        }
        return cpString;
    }

    public static Constant.TESTING_FRAMEWORK getTestingFramework(String projectDir, String outputDir, String blockGenDir) {
        if (System.getenv("BLOCKGEN_TESTING_FRAMEWORK") != null) {
            System.out.println("Testing framework specified: " + System.getenv("BLOCKGEN_TESTING_FRAMEWORK"));
            switch (System.getenv("BLOCKGEN_TESTING_FRAMEWORK").toLowerCase()) {
                case "junit4":
                    return Constant.TESTING_FRAMEWORK.JUNIT4;
                case "junit5":
                    return Constant.TESTING_FRAMEWORK.JUNIT5;
                case "testng":
                    return Constant.TESTING_FRAMEWORK.TESTNG;
                default:
                    break;
            }
        }

        String deps = getDepsContent(projectDir, outputDir, blockGenDir).toLowerCase();
        if (!deps.contains("junit-4")) {
            if (deps.contains("testng")) {
                return Constant.TESTING_FRAMEWORK.TESTNG;
            }
            if (deps.contains("junit-jupiter")) {
                return Constant.TESTING_FRAMEWORK.JUNIT5;
            }
        }
        return Constant.TESTING_FRAMEWORK.JUNIT4;
    }

    /**
     * Compile the project with the BTest extension.
     * @param projectDir The project directory.
     * @param outputDir The output directory.
     * @param blockGenDir Block Gen directory.
     * @return The result of the compilation. 0 if successful, otherwise -1.
     */
    public static int compileProjectWithBTest(String projectDir, String originalFilePath, String outputDir, String blockGenDir) {
//        ProcessBuilder pb = new ProcessBuilder("mvn", "clean", "test-compile", "-Dmaven.ext.class.path=" + resourcesDir + File.separator + "blocktest-extension-1.0.jar", Constant.SKIPS);
//        pb.directory(new File(projectDir));
//        pb.redirectOutput(new File(outputDir + File.separator + "logs" + File.separator + "compile-project-with-btest.log"));
//        pb.redirectErrorStream(true);
//        try {
//            return pb.start().waitFor();
//        } catch (IOException | InterruptedException ex) {
//            ex.printStackTrace();
//        }
//        return -1;
        try {
            Files.copy(Paths.get(originalFilePath), Paths.get(outputDir + File.separator + "tmp.java"), StandardCopyOption.REPLACE_EXISTING);
            BlockTestRemover.removeBlockTests(originalFilePath);

            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(new File(projectDir + File.separator + "pom.xml"));
            if (System.getenv("MAVEN_REPO") != null) {
                request.addArg("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
            }
            request.addArg("test-compile");
            request.addArg("-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar");
            request.addArg("-l" + outputDir + File.separator + "logs" + File.separator + "compile-project-with-btest.log");
            request.addArgs(Constant.SKIPS);
            request.setOutputHandler(output -> {});
            request.setErrorHandler(error -> {});
            request.setBatchMode(true);

            Invoker invoker = new DefaultInvoker();
            InvocationResult result = invoker.execute(request);
            int exitCode = result.getExitCode();
            Files.copy(Paths.get(outputDir + File.separator + "tmp.java"), Paths.get(originalFilePath), StandardCopyOption.REPLACE_EXISTING);

            return exitCode;
        } catch (Exception ex) {
            ex.printStackTrace();
            return -1;
        }
    }

    /**
     * Replace file
     * @param dest Destination
     * @param src Srouce
     */
    public static void replaceFile(String dest, String src) {
        try {
            Files.copy(Paths.get(src), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void moveFile(String dest, String src) {
        try {
            Files.move(Paths.get(src), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void deleteFile(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getClassNameFromSrc(String relpathToSrc) {
        return relpathToSrc.replace(Utils.getSourcePath() + File.separator, "").replace(Utils.getTestPath() + File.separator, "").replace(".java", "").replace(File.separatorChar, '.');
    }

    public static void updateEvosuiteTestTimeout(String src, int newTimeout) {
        File file = new File(src);
        try {
            CompilationUnit cu = StaticJavaParser.parse(Paths.get(src));
            cu.accept(new ModifierVisitor<Void>() {
                @Override
                public MethodDeclaration visit(MethodDeclaration md, Void arg) {
                    md.getAnnotations().forEach(annotation -> {
                        if (annotation.getNameAsString().equals("Test")) {
                            annotation.asNormalAnnotationExpr().getPairs().forEach(timeout -> {
                                if (timeout.getNameAsString().equals("timeout")) {
                                    timeout.getValue().asIntegerLiteralExpr().setValue("" + newTimeout);
                                }
                            });
                        }
                    });
                    return (MethodDeclaration) super.visit(md, arg);
                }
            }, null);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(cu.toString());
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    public static void copyRecursively(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(srcPath -> {
            try {
                Path targetPath = dest.resolve(src.relativize(srcPath));
                Files.copy(srcPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        });
    }

    public static void copyDirectory(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void writeRandoopSeeds(String randoopSeedsPath, String packageName) {
        String[] seeds = {
            "byte:22",
            "short:222",
            "int:222",
            "char:22",
            "long:222",
            "float:222",
            "double:222",
            "String:\"222\"",
            "String:\"22e2\"",
            "String:\"abcde\"",
            "String:\"foo.png\"",
            "String:\"1.2345\"",
            "String:\"12345\"",
            "String:\"" + randoopSeedsPath + "\""
        };

        try (PrintWriter writer = new PrintWriter(randoopSeedsPath)) {
            writer.println("START CLASSLITERALS");
            writer.println("CLASSNAME");
            writer.println(packageName);
            writer.println("LITERALS");
            for (String seed : seeds) {
                writer.println(seed);
            }
            writer.println("END CLASSLITERALS");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void writeToJSON(Map<String, Object> data, String path) {
        JSONObject jsonObject = new JSONObject(data);
        String json = jsonObject.toString(2);
        try {
            FileWriter file = new FileWriter(path);
            file.write(json);
            file.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean deleteDirectory(Path targetPath)  {
        try {
            if (Files.exists(targetPath)) {
                Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                            throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static void patchJar(String jarPath) {
        URI jarFile = URI.create("jar:file:" + jarPath);
        try (FileSystem fs = FileSystems.newFileSystem(jarFile, new HashMap<String, String>())) {
            // Delete multi-release jar
            // zip -d "${JAR}" "META-INF/versions/*"
            Path targetPath = fs.getPath("META-INF/versions");
            deleteDirectory(targetPath);

            // Delete signature
            // zip -d ${JAR} "META-INF/*.SF" "META-INF/*.DSA" "META-INF/*.RSA" "META-INF/*.EC"
            try (Stream<Path> paths = Files.walk(fs.getPath("META-INF"), 1)) {
//                PathMatcher matcher = fs.getPathMatcher("glob:META-INF/*.{SF,DSA,RSA,EC}");
                paths
//                    .filter(matcher::matches)  // Only works on macOS
                        .forEach(p -> {
                            try {
                                String f = p.toString();
                                if (f.endsWith(".SF") || f.endsWith(".DSA") || f.endsWith(".RSA") || f.endsWith(".EC")) {
                                    System.out.println("DELETE signature file " + f + " in " + jarPath);
                                    Files.delete(p);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            }
        } catch (IOException ex) {
            System.out.println("Unable to patch jar " + jarPath);
        }
    }

    public static String getSourcePath() {
        if (System.getenv("SRC_DIRECTORY") != null) {
            return System.getenv("SRC_DIRECTORY");
        } else {
            return "src" + File.separator + "main" + File.separator + "java";
        }
    }

    public static String getTestPath() {
        if (System.getenv("TEST_DIRECTORY") != null) {
            return System.getenv("TEST_DIRECTORY");
        } else {
            return "src" + File.separator + "test" + File.separator + "java";
        }
    }

    public static Optional<Expression> getRootCaller(MethodCallExpr methodCall) {
        if (!methodCall.getScope().isPresent()) return Optional.empty();

        Expression scope = methodCall.getScope().get();

        while (true) {
            if (scope instanceof MethodCallExpr) {
                Optional<Expression> inner = ((MethodCallExpr) scope).getScope();
                if (inner.isPresent()) {
                    scope = inner.get();
                } else {
                    break;
                }
            } else if (scope instanceof FieldAccessExpr) {
                scope = ((FieldAccessExpr) scope).getScope();
            } else {
                break;
            }
        }
        return Optional.of(scope);
    }

    public static Optional<Expression> getRootCaller(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();

        while (scope instanceof FieldAccessExpr) {
            scope = ((FieldAccessExpr) scope).getScope();
        }

        return Optional.of(scope);
    }

    public static String getTemporaryDir(int mutantID) {
        String tempBase = System.getProperty("java.io.tmpdir");
        return Paths.get(tempBase, "project-" + mutantID).toString();
    }

    public static String getFullName(ClassOrInterfaceDeclaration cls) {
        String name = cls.getNameAsString();
        Node parent = cls.getParentNode().orElse(null);

        while (parent != null) {
            if (parent instanceof ClassOrInterfaceDeclaration) {
                name = ((ClassOrInterfaceDeclaration) parent).getNameAsString() + "." + name;
            } else if (parent instanceof CompilationUnit) {
                break;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return name;
    }

    public static String removeInstrumentation(CompilationUnit cu) {
        cu.findAll(ExpressionStmt.class).forEach(stmt -> {
            stmt.findAll(MethodCallExpr.class).forEach(mce -> {
                Optional<Expression> scope = mce.getScope();
                if (scope.isPresent() && mce.getNameAsString().equals("logVariableAndGenerateTest")) {
                    if (scope.get().toString().equals("org.blockgen.helpers.InstrumentHelper") || scope.get().toString().equals("InstrumentHelper")) {
                        stmt.replace(new EmptyStmt());
                    }
                }
                // Remove BLOCKGEN_FLOW_INTERNAL.append(...)
                if (scope.isPresent() && mce.getNameAsString().equals("append")) {
                    if (scope.get().toString().equals("BLOCKGEN_FLOW_INTERNAL")) {
                        stmt.replace(new EmptyStmt());
                    }
                }
            });

            // Remove: java.lang.StringBuilder BLOCKGEN_FLOW_INTERNAL = new java.lang.StringBuilder();
            stmt.findAll(VariableDeclarationExpr.class).forEach(vde -> {
                vde.getVariables().forEach(v -> {
                    if (v.getNameAsString().equals("BLOCKGEN_FLOW_INTERNAL")) {
                        stmt.replace(new EmptyStmt());
                    }
                });
            });
        });

        cu.getImports().removeIf(importDecl ->
                importDecl.getNameAsString().equals("org.blockgen.helpers.InstrumentHelper")
        );
        return cu.toString();
    }

    public static String useMainMethod(CompilationUnit cu, String methodName) {
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);

        if (clazz == null) {
            System.out.println("Error: no class found in the source file.");
            return "";
        }

        Optional<MethodDeclaration> first = clazz.getMethods().stream().filter(m -> m.getNameAsString().equals(methodName)).findFirst();
        if (!first.isPresent()) {
            System.out.println("Error: method " + methodName + " not found in class " + clazz.getNameAsString());
            return "";
        }

        MethodDeclaration original = first.get();
        NodeList<Parameter> params = original.getParameters();

        MethodDeclaration main = new MethodDeclaration();
        main.setName("main");
        main.setType(new VoidType());
        main.setModifiers(com.github.javaparser.ast.Modifier.Keyword.PUBLIC, com.github.javaparser.ast.Modifier.Keyword.STATIC);
        main.addParameter(new ArrayType(new ClassOrInterfaceType(null, "String")), "args");
        main.addThrownException(new ClassOrInterfaceType(null, "Exception"));

        BlockStmt body = new BlockStmt();
        NodeList<Expression> callArgs = new NodeList<>();

        for (Parameter param : params) {
            String typeName = param.getType().asString();
            Expression defaultExpr = TypeResolverUtil.getTypeDefaultValue(typeName);
            callArgs.add(defaultExpr);
        }

        MethodCallExpr callExpr = new MethodCallExpr(null, original.getNameAsString(), callArgs);
        body.addStatement(new com.github.javaparser.ast.stmt.ExpressionStmt(callExpr));

        main.setBody(body);
        clazz.addMember(main);

        return Utils.removeInstrumentation(cu);
    }

    public static String getParameters(String src) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(Paths.get(src));
            ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
            if (cls == null) {
                System.out.println("Error: no class found in the source file.");
                return "";
            }
            Optional<MethodDeclaration> first = cls.getMethods().stream().filter(m -> m.getNameAsString().equals("extractedTest")).findFirst();
            if (!first.isPresent()) {
                System.out.println("Error: extracted method not found in class " + cls.getNameAsString());
                return "";
            }
            MethodDeclaration method = first.get();
            List<String> parameters = new ArrayList<>();
            for (Parameter param : method.getParameters()) {
                parameters.add(param.getNameAsString() + ":" + param.getType().asString());
            }
            return String.join(",", parameters);
        } catch (IOException ex) {
            ex.printStackTrace();
            return "";
        }

    }

    /**
     * Reads the instrumented source file at srcPath and returns [physicalStartLine, physicalEndLine]
     * pairs (1-based) for each valid fragment. Physical lines correspond to the instrumented source
     * and therefore match the JaCoCo line table in the compiled class.
     *
     * - end after target-fragment-return: skip but remember as last candidate
     * - end after target-fragment-throw: use last candidate end, fall back to this line if none
     * - any other end: use as-is (e.g. finally block)
     */
    public static List<int[]> scanFragmentRanges(String srcPath) {
        List<int[]> ranges = new ArrayList<>();
        if (srcPath == null || srcPath.isEmpty()) {
            return ranges;
        }
        Path path = Paths.get(srcPath);
        if (!Files.exists(path)) {
            System.out.println("Warning: srcPath does not exist, skipping fragment range scan: " + srcPath);
            return ranges;
        }
        Pattern startPat  = Pattern.compile("logVariableAndGenerateTest\\s*\\(\\s*\"" + Constant.TARGET_STMT_START + "\"");
        Pattern returnPat = Pattern.compile("logVariableAndGenerateTest\\s*\\(\\s*\"" + Constant.TARGET_STMT_RETURN + "\"");
        Pattern throwPat  = Pattern.compile("logVariableAndGenerateTest\\s*\\(\\s*\"" + Constant.TARGET_STMT_THROW + "\"");
        Pattern endPat    = Pattern.compile("logVariableAndGenerateTest\\s*\\(\\s*\"" + Constant.TARGET_STMT_END + "\"");
        try {
            List<String> lines = Files.readAllLines(path);
            int openAtLine = -1;
            int lastEndLine = -1;
            boolean lastWasReturn = false;
            boolean lastWasThrow  = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int physicalLine = i + 1;
                if (startPat.matcher(line).find()) {
                    openAtLine = physicalLine;
                    lastEndLine = -1;
                    lastWasReturn = false;
                    lastWasThrow  = false;
                } else if (returnPat.matcher(line).find()) {
                    lastWasReturn = true;
                    lastWasThrow  = false;
                } else if (throwPat.matcher(line).find()) {
                    lastWasThrow  = true;
                    lastWasReturn = false;
                } else if (endPat.matcher(line).find()) {
                    if (lastWasReturn) {
                        lastEndLine = physicalLine;
                        lastWasReturn = false;
                    } else if (lastWasThrow && openAtLine != -1) {
                        int endLine = (lastEndLine != -1) ? lastEndLine : physicalLine;
                        ranges.add(new int[]{openAtLine, endLine});
                        System.out.println("Found fragment range in " + srcPath + ": physical lines " + openAtLine + "-" + endLine);
                        openAtLine = -1;
                        lastEndLine = -1;
                        lastWasThrow = false;
                    } else if (openAtLine != -1) {
                        ranges.add(new int[]{openAtLine, physicalLine});
                        System.out.println("Found fragment range in " + srcPath + ": physical lines " + openAtLine + "-" + physicalLine);
                        openAtLine = -1;
                        lastEndLine = -1;
                        lastWasReturn = false;
                    }
                } else if (line.contains("logVariableAndGenerateTest")) {
                    lastWasReturn = false;
                    lastWasThrow  = false;
                }
            }
        } catch (IOException e) {
            System.out.println("Warning: could not scan fragment ranges from " + srcPath);
            e.printStackTrace();
        }
        System.out.println("Total fragment ranges found in " + srcPath + ": " + ranges.size());
        return ranges;
    }

    public static List<List<String>> cartesianProduct(List<List<String>> lists) {
        List<List<String>> result = new ArrayList<List<String>>();
        result.add(new ArrayList<String>());

        for (List<String> candidates : lists) {
            List<List<String>> newResult = new ArrayList<List<String>>();
            for (List<String> existing : result) {
                for (String candidate : candidates) {
                    List<String> newCombination = new ArrayList<String>(existing);
                    newCombination.add(candidate);
                    newResult.add(newCombination);
                }
            }
            result = newResult;
        }

        return result;
    }
}
