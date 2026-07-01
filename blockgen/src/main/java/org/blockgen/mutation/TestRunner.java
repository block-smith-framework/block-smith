package org.blockgen.mutation;

import org.apache.maven.shared.invoker.*;
import org.blockgen.Constant;
import org.blockgen.Utils;
import org.blockgen.helpers.BlockTestRemover;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRunner {

    private static void extractBlockTests(String projectDir, String outputDir, String blockGenDir, String src, int mutantID, boolean coverage) throws Exception {
        String extractedTestsDir = outputDir + File.separator + "extracted-tests" + File.separator + (mutantID >= 0 ? "mutant-" + mutantID : "all");
        if (!new File(extractedTestsDir).exists()) {
            new File(extractedTestsDir).mkdirs();
        }

        Utils.getDepsContent(projectDir, outputDir, blockGenDir);

        String testDir = null;
        String framework = "";
        String workingDir = null;
        // Read the first 3 lines of src for additional information
        try (BufferedReader reader = new BufferedReader(new FileReader(src))) {
            for (int i = 0; i < 3; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (line.contains("FRAMEWORK")) {
                    framework = "--junit_version=" + line.split(":")[1].trim();
                }
                if (line.contains("TEST_DIR")) {
                    testDir = line.split(":")[1].trim();
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (Paths.get(outputDir + File.separator + "logs" + File.separator + "extract-" + (mutantID >= 0 ? mutantID : "all") + ".log").toFile().exists()) {
            int i = 0;
            while (Paths.get(outputDir + File.separator + "logs" + File.separator + "extract-" + (mutantID >= 0 ? mutantID : "all") + ".log." + i).toFile().exists()) { i++; }
            Files.move(Paths.get(outputDir + File.separator + "logs" + File.separator + "extract-" + (mutantID >= 0 ? mutantID : "all") + ".log"), Paths.get(outputDir + File.separator + "logs" + File.separator + "extract-" + (mutantID >= 0 ? mutantID : "all") + ".log." + i));
        }


        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(blockGenDir + File.separator + "libs" + File.separator + "blocktest-1.0.jar");
        command.add("--input_file=" + src);
        command.add("--output_dir=" + extractedTestsDir);
        command.add("--dep_file_path=" + projectDir + File.separator + "deps.txt");
        if (coverage) {
            command.add("--coverage=true");
        }
        Constant.TESTING_FRAMEWORK testingFramework = Utils.getTestingFramework(projectDir, outputDir, blockGenDir);
        System.out.println("Detected testing framework: " + testingFramework);
        if (testingFramework.equals(Constant.TESTING_FRAMEWORK.JUNIT5)) {
            command.add("--junit_version=junit5");
        } else if (testingFramework.equals(Constant.TESTING_FRAMEWORK.TESTNG)) {
            command.add("--junit_version=testng");
        }

        if (new File(projectDir + File.separator + Utils.getSourcePath()).exists()) {
            // standard src/main/java
            command.add("--app_src_path=" + projectDir + File.separator + Utils.getSourcePath());
        } else {
            // TODO: handle non-standard source directory structure
            command.add("--app_src_path=" + projectDir + File.separator + Utils.getSourcePath());
            throw new RuntimeException("Non-standard source directory structure is not supported yet. Please use src/main/java as the source directory.");
        }
        command.add("--loadXml=true");
        command.add("--public_var=true");
        command.add("--generated_tests=true");
//        command.add("--rewriteStaticVar=false"); // TODO: this option makes some fragment to fail (e.g., albfernandez-itext2-5ac684e2140a3d9a5b4c34dfd2639dcc87884ad0-BmpImage-L401_L415)
        if (!framework.isEmpty()) {
            command.add(framework);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(blockGenDir));
        pb.redirectOutput(new File(outputDir + File.separator + "logs" + File.separator + "extract-" + (mutantID >= 0 ? mutantID : "all") + ".log"));
        pb.redirectErrorStream(true);

        Process ps = pb.start();
        int exitCode = ps.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to extract block tests. See log for details.");
        }

        String testParentForBTest = new File(src).getParentFile().getAbsolutePath().replace(Utils.getSourcePath() + File.separator, Utils.getTestPath() + File.separator);
        if (testDir != null) {
            testParentForBTest = projectDir + File.separator + testDir;
        }
        if (!new File(testParentForBTest).exists()) {
            new File(testParentForBTest).mkdirs();
        }
        try {
            Files.copy(Paths.get(extractedTestsDir + File.separator + new File(src).getName().replace(".java", "BlockTest.java")), Paths.get(testParentForBTest + File.separator + new File(src).getName().replace(".java", "BlockTest.java")), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static int runBlockTest(String projectDir, String outputDir, String blockGenDir, String log, String src, int mutantID) {
        return runBlockTest(projectDir, outputDir, blockGenDir, log, src, mutantID, false);
    }

    public static int runBlockTestWithCoverage(String projectDir, String outputDir, String blockGenDir, String log, String src, int mutantID) {
        return runBlockTest(projectDir, outputDir, blockGenDir, log, src, mutantID, true);
    }

//    private static int runBlockTest(String projectDir, String outputDir, String blockGenDir, String log, String src, int mutantID, boolean coverage) {
//        System.out.println("===== Running BlockTest =====");
//
//        try {
//            extractBlockTests(projectDir, outputDir, blockGenDir, src, mutantID, coverage);
//
//
//            InvocationRequest request = new DefaultInvocationRequest();
//            request.setPomFile(new File(projectDir + File.separator + "pom.xml"));
//            if (System.getenv("MAVEN_REPO") != null) {
//                request.addArg("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
//            }
//            request.addArg("-l");
//            request.addArg(outputDir + File.separator + "logs" + File.separator + log);
//
//            request.addArg("clean");
//            request.addArg("test");
//            request.addArg("-Dtest=*BlockTest");
//            request.addArg("-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar");
//            if (coverage) {
//                request.addShellEnvironment("ADD_JACOCO", "1");
//            }
//            request.addShellEnvironment("SUREFIRE_REPORT", outputDir + File.separator + "tests-reports" + File.separator + (mutantID >= 0 ? "mutant-" + mutantID : "all"));
//            request.addArgs(coverage ? Constant.SKIPS_WITH_JACOCO : Constant.SKIPS);
//            request.setBatchMode(true);
//            request.setTimeoutInSeconds(600);
//
//            Invoker invoker = new DefaultInvoker();
//            InvocationResult result = invoker.execute(request);
//
//            return result.getExitCode();
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//        return -1;
//    }

    private static int runBlockTest(String projectDir, String outputDir, String blockGenDir, String log, String src, int mutantID, boolean coverage) {
        System.out.println("===== Running BlockTest =====");

        try {
            Utils.replaceFile(outputDir + File.separator + "backup-injected-" + mutantID + ".java", src);
            extractBlockTests(projectDir, outputDir, blockGenDir, src, mutantID, coverage);
            BlockTestRemover.removeBlockTests(src);
            Utils.replaceFile(outputDir + File.separator + "backup-removed-" + mutantID + ".java", src);
            String tmpDir = Utils.getTemporaryDir(mutantID) + "-tmp";
            new File(tmpDir).mkdirs();

            List<String> cmd = new ArrayList<>();
            cmd.add("mvn");
//            cmd.add("-f");
//            cmd.add(projectDir + File.separator + "pom.xml");
            cmd.add("-B"); // batch mode
            cmd.add("-l");
            cmd.add(outputDir + File.separator + "logs" + File.separator + log);
            cmd.add("clean");
            cmd.add("test");
            cmd.add("-Dtest=*BlockTest");
            cmd.add("-Dmaven.ext.class.path=" + blockGenDir + File.separator + "extension"
                    + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar");
            cmd.add("-Djava.io.tmpdir=" + tmpDir);
            cmd.add("-DtempDir=" + tmpDir + "-surefire");
            cmd.add("-Dbasedir=" + projectDir);

            if (System.getenv("MAVEN_REPO") != null) {
                cmd.add("-Dmaven.repo.local=" + System.getenv("MAVEN_REPO"));
            }
            cmd.addAll(coverage ? Constant.SKIPS_WITH_JACOCO : Constant.SKIPS);

            System.out.println("Running command " + cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(projectDir));

            String surefireDir = outputDir + File.separator + "tests-reports"
                    + File.separator + (mutantID >= 0 ? "mutant-" + mutantID : "all") + File.separator + "tmp";
            new File(surefireDir).mkdirs();
            pb.environment().put("SUREFIRE_REPORT", surefireDir);

            pb.environment().put("TMPDIR", tmpDir + "-surefire");
            if (coverage) {
                pb.environment().put("ADD_JACOCO", "1");
            }
            pb.inheritIO();

            Process process = pb.start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                if (!coverage)
                    Utils.replaceFile(src, outputDir + File.separator + "backup-injected-" + mutantID + ".java");
                process.destroyForcibly();
                return -1;
            }

            if (!coverage)
                Utils.replaceFile(src, outputDir + File.separator + "backup-injected-" + mutantID + ".java");
            return process.exitValue();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (!coverage)
            Utils.replaceFile(src, outputDir + File.separator + "backup-injected-" + mutantID + ".java");
        return -1;
    }

    public static Set<String> identifyBrokenTests(String projectDir, String outputDir, String blockGenDir, String log, String src, int mutantID) {
        Set<String> failedToCompileTests = new HashSet<>();

        try {
            // Parse the log file for "is never thrown" errors
            Path logPath = Paths.get(outputDir + File.separator + "logs" + File.separator + log);
            List<String> logLines = Files.readAllLines(logPath);

            List<Integer> errorLines = new ArrayList<>();
            Set<String> methods = new HashSet<>();

            Pattern alreadyDefined = Pattern.compile("already defined in method (AUTO_GEN_\\S+)\\s*\\(");


            Pattern errorPattern = Pattern.compile("\\.java:\\[(\\d+),\\d+\\].*is never thrown in body of corresponding try statement");
            Pattern errorPattern2 = Pattern.compile("\\.java:\\[(\\d+),\\d+\\].*is ambiguous");
            Pattern errorPattern3 = Pattern.compile("\\.java:\\[(\\d+),\\d+\\].*might not have been initialized");
            Pattern errorPattern4 = Pattern.compile("\\.java:\\[(\\d+),\\d+\\].*cannot be converted to");
            Pattern errorPattern5 = Pattern.compile("\\.java:\\[(\\d+),\\d+\\].*cannot find symbol");

            for (String line : logLines) {
                Matcher m = errorPattern.matcher(line);
                if (m.find()) {
                    errorLines.add(Integer.parseInt(m.group(1)));
                }

                Matcher m2 = errorPattern2.matcher(line);
                if (m2.find()) {
                    errorLines.add(Integer.parseInt(m2.group(1)));
                }

                Matcher m3 = errorPattern3.matcher(line);
                if (m3.find()) {
                    errorLines.add(Integer.parseInt(m3.group(1)));
                }

                Matcher m4 = errorPattern4.matcher(line);
                if (m4.find()) {
                    errorLines.add(Integer.parseInt(m4.group(1)));
                }

                Matcher m5 = errorPattern5.matcher(line);
                if (m5.find()) {
                    errorLines.add(Integer.parseInt(m5.group(1)));
                }

                Matcher m6 = alreadyDefined.matcher(line);
                if (m6.find()) {
                    methods.add(m6.group(1));
                }
            }

            if (errorLines.isEmpty() && methods.isEmpty()) return failedToCompileTests;

            System.out.println("Error lines in log: " + errorLines);

            // Open the BlockTest.java file
            String extractedTestsDir = outputDir + File.separator + "extracted-tests" + File.separator + "all";
            Path blockTestPath = Paths.get(extractedTestsDir + File.separator + new File(src).getName().replace(".java", "BlockTest.java"));
            List<String> sourceLines = Files.readAllLines(blockTestPath);

            Pattern methodPattern = Pattern.compile("^\\s+public\\s+void\\s+(AUTO_GEN_\\S+)\\s*\\(");

            // For each error line, search upward for the enclosing method
            for (int errorLine : errorLines) {
                String foundMethod = null;
                for (int i = errorLine - 1; i >= 0; i--) {
                    Matcher m = methodPattern.matcher(sourceLines.get(i));
                    if (m.find()) {
                        foundMethod = m.group(1);
                        break;
                    }
                }
                if (foundMethod != null) {
                    methods.add(foundMethod);
                }
            }

            if (methods.isEmpty()) {
                System.out.println("Cannot find any test method");
                return failedToCompileTests;
            }

            // Remove broken tests
            List<String> srcLines = Files.readAllLines(Paths.get(src));

            List<String> filteredLines = new ArrayList<>();
            for (String line : srcLines) {
                boolean shouldRemove = false;
                for (String method : methods) {
                    if (line.contains(method)) {
                        shouldRemove = true;
                        break;
                    }
                }
                if (!shouldRemove) {
                    filteredLines.add(line);
                }
            }

            Files.write(Paths.get(src), filteredLines);
            failedToCompileTests = methods;

            Files.copy(Paths.get(outputDir + File.separator + "logs" + File.separator + log),
                    Paths.get(outputDir + File.separator + "logs" + File.separator + log + ".old"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return failedToCompileTests;
    }

}
