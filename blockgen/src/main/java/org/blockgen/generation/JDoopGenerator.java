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

public class JDoopGenerator {

    /** A directory that contains jdoop-genereated unit tests. */
    protected String jdoopTestsDir;

    /** The log that keeps the output of the jdoop generation process. */
    protected String jdoopGenerationLog;

    private String projectDir;
    private String relpathToSrc;
    private String outputDir;
    private String blockGenDir;

    public JDoopGenerator(String projectDir, String relpathToSrc, String outputDir, String blockGenDir) {
        this.projectDir = projectDir;
        this.relpathToSrc = relpathToSrc;
        this.outputDir = outputDir;
        this.blockGenDir = blockGenDir;
    }

    /** Generates JDoop tests for classes specified in the JDoop classpath list file. */
    public void generateJDoopTests() {
        System.out.println("===== Generating JDoop tests =====");

        // Create mutant directory if not exists
        new File(outputDir).getParentFile().mkdirs();
        new File(outputDir).mkdirs();

        int exitCode = -1;
        String jdoopLogDir = outputDir + File.separator + "jdoop-logs";
        this.jdoopTestsDir = projectDir + File.separator + "jdoop-tests";
        this.jdoopGenerationLog = jdoopLogDir + File.separator + "generation-log.txt";
        File jdoopLogFile = new File(jdoopGenerationLog);
        if (!jdoopLogFile.exists()) {
            jdoopLogFile.getParentFile().mkdirs();
        }
        File jdoopTestsFile = new File(jdoopTestsDir);
        if (!jdoopTestsFile.exists()) {
            jdoopTestsFile.mkdirs();
        } else {
            System.out.println("JDoop tests found, using existing ones and skipping generation.");
            return;
        }

        String jdoopPath = System.getenv("JDOOP_PATH");
        if (jdoopPath == null) {
            System.out.println("JDOOP_PATH environment variable is not set. Please set it to the path of the JDoop installation.");
            System.exit(1);
        }
        String jpfCorePath = System.getenv("JPF_CORE_PATH");
        if (jpfCorePath == null) {
            System.out.println("JPF_CORE_PATH environment variable is not set. Please set it to the path of the JPF Core installation.");
            System.exit(1);
        }
        String jdartPath = System.getenv("JDART_PATH");
        if (jdartPath == null) {
            System.out.println("JDART_PATH environment variable is not set. Please set it to the path of the JDart installation.");
            System.exit(1);
        }

        String sourcePath = projectDir + File.separator + Utils.getSourcePath();
        String targetClasses = Utils.getClassesDirectory(projectDir, outputDir, blockGenDir, false);

//        String classpathList = outputDir + File.separator + "classpath-list.txt";
//        Utils.writeClasspathList(projectDir, outputDir, blockGenDir, classpathList);
//        long classpathCount = 0;
//        try (Stream<String> stream = Files.lines(Paths.get(classpathList), StandardCharsets.UTF_8)) {
//            classpathCount = stream.count();
//        } catch (IOException ex) {
//            ex.printStackTrace();
//        }
//        long timeout = Constant.RANDOOP_TIMEOUT_S;
//        if (classpathCount * Constant.RANDOOP_SINGLE_CLASS_TIMEOUT_S < Constant.RANDOOP_TIMEOUT_S) {
//            timeout = classpathCount * Constant.RANDOOP_SINGLE_CLASS_TIMEOUT_S;
//        }
//        if (System.getenv("GENERATION_TIMEOUT") != null) {
//            timeout = Integer.parseInt(System.getenv("GENERATION_TIMEOUT"));
//        } else if (timeout > 3600) {
//            timeout = 3600;
//        }
        int timeout = 600;

        System.out.println("Timeout for JDoop generation: " + timeout + " seconds");

        try {
            List<String> command = new ArrayList<>(Arrays.asList("python2", "jdoop.py",
                    "--jpf-core-path", jpfCorePath, "--jdart-path", jdartPath,
                    "--root", sourcePath,
                    "--sut-compilation", targetClasses,
                    "--test-compilation", outputDir + File.separator + "test-compilation",
                    "--junit-path", jdoopPath + "/lib/junit4.jar",
                    "--hamcrest-path", jdoopPath + "/lib/hamcrest-core-1.3.jar",
                    "--randoop-path", jdoopPath + "/lib/randoop.jar",
                    "--jacoco-path", jdoopPath + "/lib/jacocoant.jar",
                    "--classpath", Utils.getDepsContent(projectDir, outputDir, blockGenDir),
                    "--generate-report", "--timelimit", "600", "--randoop-time", "60"));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(jdoopPath));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(this.jdoopGenerationLog)));
            Process process = pb.start();
            exitCode = process.waitFor(timeout+600, TimeUnit.SECONDS) ? 0 : 1;
            int actualExit = process.exitValue();
            System.out.println("Exit code for JDoop generation: " + actualExit + " and " + exitCode);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("There was an issue with JDoop test generation, check " + jdoopGenerationLog + " for details.");
        }

        try {
            if (Files.exists(Paths.get(jdoopPath + File.separator + "concrete-values-jdart.txt"))) {
                Files.copy(Paths.get(jdoopPath + File.separator + "concrete-values-jdart.txt"),
                        Paths.get(jdoopLogDir + File.separator + "concrete-values-jdart.txt"));
            }

            if (Files.exists(Paths.get(jdoopPath + File.separator + "concrete-values.txt"))) {
                Files.copy(Paths.get(jdoopPath + File.separator + "concrete-values.txt"),
                        Paths.get(jdoopLogDir + File.separator + "concrete-values.txt"));
            }

            if (Files.exists(Paths.get(jdoopPath + File.separator + "classlist.txt"))) {
                Files.copy(Paths.get(jdoopPath + File.separator + "classlist.txt"),
                        Paths.get(jdoopLogDir + File.separator + "classlist.txt"));
            }

            if (Files.exists(Paths.get(jdoopPath + File.separator + "darted"))) {
                Utils.copyRecursively(Paths.get(jdoopPath + File.separator + "darted"),
                        Paths.get(jdoopLogDir + File.separator + "darted"));
            }

            for (int i = 1; i < 999; i++) {
                if (Files.exists(Paths.get(jdoopPath + File.separator + "randooped" + i))) {
                    Utils.copyRecursively(Paths.get(jdoopPath + File.separator + "randooped" + i),
                            Paths.get(jdoopLogDir + File.separator + "randooped" + i));
                    continue;
                }
                break;
            }

            for (int i = 1; i < 999; i++) {
                if (Files.exists(Paths.get(jdoopPath + File.separator + "tests-round-" + i))) {
                    Utils.copyRecursively(Paths.get(jdoopPath + File.separator + "tests-round-" + i),
                            Paths.get(outputDir + File.separator + "tests-round-" + i));
                    continue;
                }
                break;
            }
        } catch (IOException ex) {
            System.out.println( "Error copying JDoop tests.");
            ex.printStackTrace();
        }
    }

    public Set<String> compile() {
        System.out.println("===== Compiling JDoop tests =====");
        String jdoopPath = System.getenv("JDOOP_PATH");

        Set<String> srcSet = new HashSet<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "bash", "-c",
                    "grep -rl 'extractedTest' tests-round-*/ | cut -d '.' -f 1 | cut -d '/' -f 2"
            );
            pb.directory(new File(jdoopPath));
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                String test = line.trim();
                // JDoop will not compile error tests
                if (test.startsWith("Regression"))
                    srcSet.add(test);
            }
            p.waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
        return srcSet;
    }

    public void execute(Set<String> classes) {
        System.out.println("===== Executing JDoop tests =====");
        File logFile = new File(outputDir, "jdoop-execution.log");
        String jacocoAgentJar = blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar";
        String jdoopPath = System.getenv("JDOOP_PATH");
        if (classes.isEmpty()) {
            System.out.println("No generated tests.");
            return;
        }

        try {
            List<String> command = new ArrayList<>(Arrays.asList("java", "-javaagent:" + jacocoAgentJar,
                    "-classpath",
                    jacocoAgentJar + File.pathSeparator
                            + Utils.getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                            + outputDir + File.separator + "test-compilation" + File.pathSeparator
                            + jdoopPath + "/lib/junit4.jar",
                    "org.junit.runner.JUnitCore"));
            command.addAll(classes);

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
