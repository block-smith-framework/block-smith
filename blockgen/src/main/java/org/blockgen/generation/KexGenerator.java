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

public class KexGenerator {


    /** A directory containing logs generated when running kex. */
    protected String kexLogDir;

    /** The log that keeps the output of the kex generation process. */
    protected String kexGenerationLog;


    private String projectDir;
    private String relpathToSrc;
    private String outputDir;
    private String blockGenDir;
    public KexGenerator(String projectDir, String relpathToSrc, String outputDir, String blockGenDir, int lineNumber) {
        this.projectDir = projectDir;
        this.relpathToSrc = relpathToSrc;
        this.outputDir = outputDir;
        this.blockGenDir = blockGenDir;
    }

    /** Generates kex tests for classes specified in the kex classpath list file. */
    public void generateKexTests() {
        System.out.println("===== Generating Kex tests =====");

        // Create mutant directory if not exists
        if (!new File(outputDir).getParentFile().exists()) {
            new File(outputDir).getParentFile().mkdirs();
        }
        if (!new File(outputDir).exists()) {
            new File(outputDir).mkdirs();
        }

        int exitCode = -1;
        this.kexLogDir = outputDir + File.separator + "kex-logs";
        this.kexGenerationLog = kexLogDir + File.separator + "generation-log.txt";
        File kexLogFile = new File(kexGenerationLog);
        if (!kexLogFile.exists()) {
            kexLogFile.getParentFile().mkdirs();
        }
        if (Files.exists(Paths.get(projectDir, "kex-generated", "tests")) && Files.exists(Paths.get(projectDir, "kex-generated", "compiled"))) {
            System.out.println("Kex tests found, using existing ones and skipping generation.");
            return;
        }

        if (System.getenv("KEX_PATH") == null) {
            System.out.println("KEX_PATH environment variable is not set. Please set it to the path of the Kex installation.");
            System.exit(1);
        }

        Utils.getDepsContent(projectDir, outputDir, blockGenDir);

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

        // Patch JAR to avoid multi-release (prevent Unsupported class file major version)
        try {
            List<String> command = new ArrayList<>(Arrays.asList("python3.9", System.getenv("KEX_PATH"), "--classpath", Utils.getDepsContent(projectDir, outputDir, blockGenDir), "--target",
                    Utils.getClassNameFromSrc(relpathToSrc), "--output", "kex-generated", "--mode", "symbolic"));

            int timeout = Constant.EVOSUITE_TIMEOUT_S * 5;
            if (System.getenv("GENERATION_TIMEOUT") != null) {
                timeout = Integer.parseInt(System.getenv("GENERATION_TIMEOUT"));
            }
            System.out.println("Timeout for Kex generation: " + timeout + " seconds");

            System.out.println("Running command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            pb.directory(new File(projectDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(this.kexGenerationLog)));
            Process process = pb.start();
            exitCode = process.waitFor(timeout, TimeUnit.SECONDS) ? 0 : 1;
            System.out.println("Exit code for Kex generation: " + exitCode);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("There was an issue with Kex test generation, check " + kexGenerationLog + " for details.");
        }

        // Exit early if no tests were generated.
        if (!Files.exists(Paths.get(projectDir, "kex-generated", "tests")) || !Files.exists(Paths.get(projectDir, "kex-generated", "compiled"))) {
            System.out.println("No tests were generated, check " + kexLogDir);
            System.exit(1);
        }

        // Copy the generated tests to the generated tests directory.
        try {
            Utils.copyRecursively(Paths.get(projectDir, "kex-generated"),
                    Paths.get(outputDir + File.separator + "kex-tests"));
        } catch (IOException ex) {
            System.out.println("Error copying Kex tests.");
            ex.printStackTrace();
        }
    }

    public Set<String> compile() {
        Set<String> classes = null;
        Set<String> allowedClasses = new HashSet<>();
        try (Stream<Path> stream = Files.walk(Paths.get(projectDir, "kex-generated", "compiled"))) {
            allowedClasses = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("extractedTest"))
                    .map(path -> path.getFileName().toString().replace(".class", ""))
                    .collect(Collectors.toSet());
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        try (Stream<Path> stream = Files.walk(Paths.get(projectDir, "kex-generated", "tests"))) {
            classes = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("extractedTest"))
                    .map(path -> path.toString().replace(Paths.get(projectDir, "kex-generated", "tests").toFile().getAbsolutePath() + File.separator, "").replace(".java", "")
                            .replace('/', '.'))
                    .collect(Collectors.toSet());
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        Set<String> filterClasses = new HashSet<>();
        for (String klass : classes) {
            String[] tmp = klass.split("\\.");
            if (tmp.length > 1) {
                if (allowedClasses.contains(tmp[tmp.length - 1])) {
                    filterClasses.add(klass);
                } else {
                    System.out.println("Class " + klass + " failed to compile, excluding...");
                }
            } else if (tmp.length == 1) {
                if (allowedClasses.contains(tmp[0])) {
                    filterClasses.add(klass);
                } else {
                    System.out.println("Class " + klass + " failed to compile, excluding...");
                }
            }
        }
        return filterClasses;
    }

    public void execute(Set<String> classes) {
        System.out.println("===== Executing Kex tests =====");

        File logFile = new File(outputDir, "kex-execution.log");
        String jacocoAgentJar = blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar";
        String junitStandaloneJar = blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar";
        try {
            List<String> command = new ArrayList<>();
            command.addAll(Arrays.asList("java", "-javaagent:" + jacocoAgentJar, "-cp",
                    jacocoAgentJar + File.pathSeparator
                            + junitStandaloneJar + File.pathSeparator
                            + Utils.getDepsContent(projectDir, outputDir, blockGenDir) + File.pathSeparator
                            + Paths.get(projectDir, "kex-generated", "compiled").toFile().getAbsolutePath(),
                    "org.junit.runner.JUnitCore"));
            command.addAll(classes);
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
