package org.blockgen.generation;

import org.blockgen.Constant;
import org.blockgen.Utils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileNotFoundException;
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

public class EvoSuiteGenerator {


    /** A directory containing logs generated when running evosuite. */
    protected String evosuiteLogDir;

    /** A directory that contains evosuite-genereated unit tests for the extracted program. */
    protected String evosuiteTestsDir;

    /** A directory that contains test execution reports that are generated from running evosuite-generated tests. */
    protected String evosuiteReportDir;

    /** The log that keeps the output of the evosuite generation process. */
    protected String evosuiteGenerationLog;


    private String projectDir;
    private String relpathToSrc;
    private String outputDir;
    private String blockGenDir;
    private int lineNumber;

    public EvoSuiteGenerator(String projectDir, String relpathToSrc, String outputDir, String blockGenDir, int lineNumber) {
        this.projectDir = projectDir;
        this.relpathToSrc = relpathToSrc;
        this.outputDir = outputDir;
        this.blockGenDir = blockGenDir;
        this.lineNumber = lineNumber;
    }

    /** Generates evosuite tests for classes specified in the evosuite classpath list file. */
    public void generateEvosuiteTests() {
        System.out.println("===== Generating EvoSuite tests =====");

        // Create mutant directory if not exists
        if (!new File(outputDir).getParentFile().exists()) {
            new File(outputDir).getParentFile().mkdirs();
        }
        if (!new File(outputDir).exists()) {
            new File(outputDir).mkdirs();
        }

        int exitCode = -1;
        this.evosuiteLogDir = outputDir + File.separator + "evosuite-logs";
        this.evosuiteTestsDir = projectDir + File.separator + "evosuite-tests";
        this.evosuiteReportDir = projectDir + File.separator + "evosuite-report";
        this.evosuiteGenerationLog = evosuiteLogDir + File.separator + "generation-log.txt";
        File evosuiteLogFile = new File(evosuiteGenerationLog);
        if (!evosuiteLogFile.exists()) {
            evosuiteLogFile.getParentFile().mkdirs();
        }
        if (new File(evosuiteTestsDir).exists() && new File(evosuiteReportDir).exists()) {
            System.out.println("Evosuite tests found, using existing ones and skipping generation.");
            return;
        }

        Utils.getDepsContent(projectDir, outputDir, blockGenDir);

        String deps = projectDir + File.separator + "orig-deps.txt";
        String evosuiteJar = blockGenDir + File.separator + "libs" + File.separator + "evosuite-master-1.2.1-SNAPSHOT.jar";

        try {
            List<String> file = Files.readAllLines(Paths.get(projectDir, "orig-deps.txt"));
            if (file.size() > 0) {
                for (String jar : file.get(0).split(File.pathSeparator)) {
                    if (jar.endsWith("jar") && jar.contains("repo")) {
                        System.out.println("Patching JAR: " + jar);
                        Utils.patchJar(jar);
                    }
                }
            }
        } catch (Exception ex) {

        }

        String method = "";
        if (lineNumber >= 0) {
            // TODO: FIX TARGET
            String bytecode = projectDir + File.separator + "target" + File.separator + "classes" + File.separator + relpathToSrc.replace(Utils.getSourcePath() + File.separator, "").replace(".java", ".class");
            System.out.println("Bytecode path: " + bytecode);
            try {
                method = GenerationUtils.findMethodByLine(bytecode, lineNumber);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // Patch JAR to avoid multi-release (prevent Unsupported class file major version)
        try {
            List<String> command = new ArrayList<>(Arrays.asList("java", "-jar", evosuiteJar, "-DCP_file_path", deps, "-class",
                    Utils.getClassNameFromSrc(relpathToSrc), "-seed", String.valueOf(Constant.DEFAULT_SEED), "-Dsearch_budget=" + Constant.EVOSUITE_TIMEOUT_S,
                    "-Duse_separate_classloader=false", "-Dminimize=false", "-Dassertion_strategy=all",
                    "-Dfilter_assertions=true", "-Dvirtual_fs=false", "-Dvirtual_net=false",
                    "-Dsandbox_mode=OFF", "-Dfilter_sandbox_tests=true", "-Dmax_loop_iterations=-1", "-Dstop_zero=false"));

            if (System.getenv("JAVA_AWT_HEADLESS") != null && System.getenv("NO_EVOSUITE_MOCKING").equals("true")) {
                System.out.println("Running EvoSuite in headless mode due to environment variable settings.");
                command.add(1, "-Djava.awt.headless=true");
            }
            if (System.getenv("EVOSUITE_REPLACE_CALLS") != null && System.getenv("EVOSUITE_REPLACE_CALLS").equals("false")) {
                System.out.println("Disabling EvoSuite call replacement due to environment variable settings.");
                command.add("-Dreplace_calls=false");
            }
            if (System.getenv("NO_EVOSUITE_MOCKING") != null && System.getenv("NO_EVOSUITE_MOCKING").equals("true")) {
                command.add("-Dmock_if_no_generator=false");
                command.add("-Dp_functional_mocking=0");
                command.add("-Dfunctional_mocking_input_limit=0");
                command.add("-Dfunctional_mocking_percent=0");
            }
            if (!method.isEmpty()) {
                command.add("-Dtarget_method=" + method );
                command.add("-Dcriterion=branch");
            }

            int timeout = Constant.EVOSUITE_TIMEOUT_S * 5;
            if (System.getenv("GENERATION_TIMEOUT") != null) {
                timeout = Integer.parseInt(System.getenv("GENERATION_TIMEOUT"));
            }
            System.out.println("Timeout for EvoSuite generation: " + timeout + " seconds");

            System.out.println("Running command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            pb.directory(new File(projectDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(this.evosuiteGenerationLog)));
            Process process = pb.start();
            exitCode = process.waitFor(timeout, TimeUnit.SECONDS) ? 0 : 1;
            System.out.println("Exit code for EvoSuite generation: " + exitCode);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("There was an issue with EvoSuite test generation, check " + evosuiteGenerationLog + " for details.");
        }

        // Exit early if no tests were generated.
        if (!Files.exists(Paths.get(evosuiteTestsDir))) {
            System.out.println("No tests were generated, check " + evosuiteLogDir);
            System.exit(1);
        }

        // For each generated test method, modify its timeout.
        try (Stream<Path> stream = Files.walk(Paths.get(evosuiteTestsDir))) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> Utils.updateEvosuiteTestTimeout(path.toString(), Constant.EVOSUITE_SINGLE_TEST_TIMEOUT_MS));
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // Copy the generated tests to the generated tests directory.
        try {
            Utils.copyRecursively(Paths.get(evosuiteTestsDir),
                    Paths.get(outputDir + File.separator + "evosuite-tests"));
            Utils.copyRecursively(Paths.get(evosuiteReportDir),
                    Paths.get(outputDir + File.separator + "evosuite-report"));
        } catch (IOException ex) {
            System.out.println("Error copying EvoSuite tests.");
            ex.printStackTrace();
        }
    }

    public Set<String> compile() {
        System.out.println("===== Compiling EvoSuite tests =====");

        if (!Utils.compileMavenProject(projectDir, outputDir, blockGenDir)) {
            System.out.println("Failed to compile project");
            System.exit(1);
        }

        Set<String> srcSet = new HashSet<>();
        try (Stream<Path> stream = Files.walk(Paths.get(new File(projectDir, "evosuite-tests").getAbsolutePath()))) {
            srcSet = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        String evosuiteJar = blockGenDir + File.separator + "libs" + File.separator + "evosuite-master-1.2.1-SNAPSHOT.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";
        try {
            File logFile = new File(outputDir, "evosuite-compilation.log");
            List<String> command = new ArrayList<>();
            command.addAll(Arrays.asList("javac", "-cp", evosuiteJar + File.pathSeparator + junitStandaloneJar
                    + File.pathSeparator + Utils.getDepsContent(projectDir, outputDir, blockGenDir)));
            command.addAll(srcSet);
            System.out.println("Running command: " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectDir));
            pb.redirectOutput(logFile);
            pb.redirectErrorStream(true); // Merge stderr into stdout
            pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        Set<String> classes = null;
        try (Stream<Path> stream = Files.walk(Paths.get(new File(projectDir, "evosuite-tests").getAbsolutePath()))) {
            classes = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Test.java"))
                    .map(path -> path.toString().replace(new File(projectDir, "evosuite-tests").getAbsolutePath() + File.separator, "").replace(".java", "")
                            .replace('/', '.'))
                    .collect(Collectors.toSet());
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        return classes;
    }

    public void execute(Set<String> classes) {
        System.out.println("===== Executing EvoSuite tests =====");

        File logFile = new File(outputDir, "evosuite-execution.log");
        String jacocoAgentJar = blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar";
        String evosuiteJar = blockGenDir + File.separator + "libs" + File.separator + "evosuite-master-1.2.1-SNAPSHOT.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";
        try {
            List<String> command = new ArrayList<>();
            command.addAll(Arrays.asList("java", "-javaagent:" + jacocoAgentJar, "-cp",
                    jacocoAgentJar + File.pathSeparator
                            + new File(projectDir, "evosuite-tests").getAbsolutePath() + File.pathSeparator
                            + evosuiteJar + File.pathSeparator + junitStandaloneJar + File.pathSeparator
                            + Utils.getDepsContent(projectDir, outputDir, blockGenDir),
                    "org.junit.runner.JUnitCore"));
            command.addAll(classes);
            if (System.getenv("JAVA_AWT_HEADLESS") != null && System.getenv("NO_EVOSUITE_MOCKING").equals("true")) {
                command.add(1, "-Djava.awt.headless=true");
            }
            System.out.println("Running command: " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectDir));
            pb.redirectOutput(logFile);
            pb.redirectErrorStream(true); // Merge stderr into stdout
            pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }

}
