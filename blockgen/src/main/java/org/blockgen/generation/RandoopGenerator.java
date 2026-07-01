package org.blockgen.generation;

import org.blockgen.Constant;
import org.blockgen.Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public class RandoopGenerator {


    /** A directory containing logs generated when running randoop. */
    protected String randoopLogDir;

    /** A directory that contains randoop-genereated unit tests. */
    protected String randoopTestsDir;

    /** The log that keeps the output of the randoop generation process. */
    protected String randoopGenerationLog;

    private String projectDir;
    private String relpathToSrc;
    private String outputDir;
    private String blockGenDir;

    public RandoopGenerator(String projectDir, String relpathToSrc, String outputDir, String blockGenDir) {
        this.projectDir = projectDir;
        this.relpathToSrc = relpathToSrc;
        this.outputDir = outputDir;
        this.blockGenDir = blockGenDir;
    }

    /** Generates Randoop tests. If singleClass is true, only generates tests for the class specified by relpathToSrc. */
    public void generateRandoopTests(boolean singleClass) {
        System.out.println("===== Generating Randoop tests" + (singleClass ? " (single class)" : "") + " =====");

        // Create mutant directory if not exists
        new File(outputDir).getParentFile().mkdirs();
        new File(outputDir).mkdirs();

        int exitCode = -1;
        this.randoopLogDir = outputDir + File.separator + "randoop-logs";
        this.randoopTestsDir = projectDir + File.separator + "randoop-tests";
        this.randoopGenerationLog = randoopLogDir + File.separator + "generation-log.txt";
        File randoopLogFile = new File(randoopGenerationLog);
        if (!randoopLogFile.exists()) {
            randoopLogFile.getParentFile().mkdirs();
        }
        File randoopTestsFile = new File(randoopTestsDir);
        if (!randoopTestsFile.exists()) {
            randoopTestsFile.mkdirs();
        } else {
            System.out.println("Randoop tests found, using existing ones and skipping generation.");
            return;
        }

        String className = Utils.getClassNameFromSrc(relpathToSrc);
        String seedFile = outputDir + File.separator + "randoop-seeds.txt";
        Utils.writeRandoopSeeds(seedFile, className);

        String randoopClasspathList = outputDir + File.separator + "randoop-classpath-list.txt";
        long timeout;
        if (singleClass) {
            try (PrintWriter pw = new PrintWriter(randoopClasspathList)) {
                pw.println(className);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }
            timeout = Constant.RANDOOP_SINGLE_CLASS_TIMEOUT_S;
        } else {
            String classpathList = outputDir + File.separator + "classpath-list.txt";
            Utils.writeClasspathList(projectDir, outputDir, blockGenDir, classpathList);
            Utils.writeRandoopClasspathList(classpathList, randoopClasspathList);
            long classpathCount = 0;
            try (Stream<String> stream = Files.lines(Paths.get(classpathList), StandardCharsets.UTF_8)) {
                classpathCount = stream.count();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            timeout = Constant.RANDOOP_TIMEOUT_S;
            if (classpathCount * Constant.RANDOOP_SINGLE_CLASS_TIMEOUT_S < Constant.RANDOOP_TIMEOUT_S) {
                timeout = classpathCount * Constant.RANDOOP_SINGLE_CLASS_TIMEOUT_S;
            }
            if (timeout > 3600) {
                timeout = 3600;
            }
        }
        if (System.getenv("GENERATION_TIMEOUT") != null) {
            timeout = Integer.parseInt(System.getenv("GENERATION_TIMEOUT"));
        }

        System.out.println("Timeout for Randoop generation: " + timeout + " seconds");

        String randoopJar = blockGenDir + File.separator + "libs" + File.separator + "randoop-all-4.3.3.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";
        try {
            List<String> command = new ArrayList<>(Arrays.asList("java", "-Xmx512G", "-classpath", randoopJar + File.pathSeparator + Utils.getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                            + junitStandaloneJar,
                    "randoop.main.Main", "gentests", "--time-limit=" + timeout, "--usethreads=true",
                    "--randomseed=" + Constant.DEFAULT_SEED, "--classlist=" + randoopClasspathList, "--literals-file=" + seedFile, "--literals-level=ALL"));
            if (System.getenv("JAVA_AWT_HEADLESS") != null && System.getenv("NO_EVOSUITE_MOCKING").equals("true")) {
                System.out.println();
                command.add(1, "-Djava.awt.headless=true");
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(randoopTestsDir));
//            pb.inheritIO();
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(this.randoopGenerationLog)));
            Process process = pb.start();
            exitCode = process.waitFor(timeout+600, TimeUnit.SECONDS) ? 0 : 1;
            int actualExit = process.exitValue();
            System.out.println("Exit code for Randoop generation: " + actualExit + " and " + exitCode);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("There was an issue with Randoop test generation, check " + randoopGenerationLog + " for details.");
        }

        String randoopRegressionTestDriver = randoopTestsDir + File.separator + "RegressionTest.java";
        if (!Files.exists(Paths.get(randoopRegressionTestDriver))) {
            System.out.println("Missing regression test driver (RegressionTest.java), amending.");
            try (PrintWriter pw = new PrintWriter(new FileWriter(randoopRegressionTestDriver, false))) {
                pw.println("import org.junit.runner.RunWith;");
                pw.println("import org.junit.runners.Suite;");
                pw.println();
                pw.println("@RunWith(Suite.class)");
                pw.print("@Suite.SuiteClasses({ ");
                try (Stream<Path> stream = Files.walk(Paths.get(randoopTestsDir))) {
                    List<String> regressionTestClasses = stream.map(Path::getFileName)
                            .map(Path::toString)
                            .filter(fileName -> fileName.startsWith("RegressionTest")
                                    && fileName.endsWith(".java") && !fileName.equals("RegressionTest.java"))
                            .map(fileName -> fileName.replace(".java", ".class"))
                            .collect(Collectors.toList());
                    pw.print(String.join(", ", regressionTestClasses));
                }
                pw.println(" })");
                pw.println("public class RegressionTest {}");
            } catch (IOException ex) {
                System.out.println("Failed to write regression test driver (RegressionTest.java).");
                ex.printStackTrace();
            }
        }

        try {
            Utils.copyRecursively(Paths.get(randoopTestsDir),
                    Paths.get(outputDir + File.separator + "randoop-tests"));
        } catch (IOException ex) {
            System.out.println( "Error copying Randoop tests.");
        }
    }

    public void compile() {
        System.out.println("===== Compiling Randoop tests =====");

        String sourcePath = projectDir + File.separator + Utils.getSourcePath();
        String randoopSources = outputDir + File.separator + "randoop-sources.txt";
        String randoopCompilationLog = outputDir + File.separator + "randoop-compilation-log.txt";
        // Caution! It is VERY important to compile the project again before executing generated unit tests.
        // Otherwise, the bytecode for the extracted class may be the version that is un-instrumented!
        try (PrintWriter writer = new PrintWriter(randoopSources)) {
            try (Stream<Path> sources = Files.walk(Paths.get(projectDir + File.separator + "randoop-tests"))) {
                sources.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(Path::toString).forEach(writer::println);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        }
        List<String> compilationCommand = new ArrayList<>(Arrays.asList("javac", "-classpath", Utils.getDepsContent(projectDir, outputDir, blockGenDir),
                "@" + randoopSources, "-sourcepath", sourcePath));

        System.out.println("Running command: " + String.join(" ", compilationCommand));
        try {
            ProcessBuilder pb = new ProcessBuilder(compilationCommand);
            pb.directory(new File(projectDir));
            pb.redirectOutput(new File(randoopCompilationLog));
            pb.redirectErrorStream(true); // Merge stderr into stdout
            pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    public void execute() {
        System.out.println("===== Executing Randoop tests =====");

        File logFile = new File(outputDir, "randoop-execution.log");
        String sourcePath = projectDir + File.separator + Utils.getSourcePath();
        String jacocoAgentJar = blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";
        File randoopRegressionTestFile = new File(projectDir + File.separator + "randoop-tests" + File.separator + "RegressionTest.class");
        File randoopErrorTestFile = new File(projectDir + File.separator + "randoop-tests" + File.separator + "ErrorTest.class");
        boolean hasRegressionTest = randoopRegressionTestFile.exists() && !randoopRegressionTestFile.isDirectory();
        boolean hasErrorTest = randoopErrorTestFile.exists() && !randoopErrorTestFile.isDirectory();
        if (!hasRegressionTest && !hasErrorTest) {
            System.out.println("No RegressionTest or ErrorTest found, skipping execution.");
            return;
        }

        try {
            List<String> command = new ArrayList<>(Arrays.asList("java", "-javaagent:" + jacocoAgentJar,
                    "-classpath",
//                    jacocoAgentJar + File.pathSeparator + sourcePath + File.pathSeparator // Do we need sourcePath???
                    jacocoAgentJar + File.pathSeparator + File.pathSeparator
                            + Utils.getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                            + projectDir + File.separator + "randoop-tests" + File.pathSeparator
                            + junitStandaloneJar,
                    "org.junit.runner.JUnitCore"));
            if (hasRegressionTest) {
                command.add("RegressionTest");
            }
            if (hasErrorTest) {
                command.add("ErrorTest");
            }
            if (System.getenv("JAVA_AWT_HEADLESS") != null && System.getenv("NO_EVOSUITE_MOCKING").equals("true")) {
                command.add(1, "-Djava.awt.headless=true");
            }
            System.out.println("Running command: " + String.join(" ", command));
            ProcessBuilder regressionTestPb = new ProcessBuilder(command);
            regressionTestPb.directory(new File(projectDir));
            regressionTestPb.redirectOutput(logFile);
            regressionTestPb.redirectErrorStream(true); // Merge stderr into stdout
            regressionTestPb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}
