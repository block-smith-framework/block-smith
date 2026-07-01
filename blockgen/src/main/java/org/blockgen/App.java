package org.blockgen;

import org.blockgen.generation.*;
import org.blockgen.mutation.MutationScore;
import org.blockgen.mutation.Mutator;
import org.blockgen.mutation.TestRunner;
import org.blockgen.strategies.Exli;
import org.blockgen.strategies.Genie;
import org.blockgen.utils.CoverageScore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class App {
    /**
     * Add log statement
     *
     * @param args
     */
    public static void main(String[] args) throws IOException {
        if (System.getenv("USE_FQN") != null) {
            Constant.useFQN = System.getenv("USE_FQN").equalsIgnoreCase("true");
        }
        
        String task = args[0];

        if (System.getenv("BLOCKGEN_THREADS") != null) {
            Constant.THREAD_POOL_SIZE = Integer.parseInt(System.getenv("BLOCKGEN_THREADS"));
        }

        if (task.equals("instrument") || task.equals("i")) {
            // Instrument
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String startLineNumber = args[5];
            String endLineNumber = args[6];
            String logFilePath = args[7];
            String classesDirectory = args[8];

            String r0TestPath;
            if (args.length >= 10) {
                r0TestPath = args[9];
            } else {
                r0TestPath = Paths.get(logFilePath).getParent().toString() + "/blocktest-r0.txt";
            }
            String r1TestPath;
            if (args.length >= 11) {
                r1TestPath = args[10];
            } else {
                r1TestPath = Paths.get(logFilePath).getParent().toString() + "/blocktest-r1.txt";
            }

            if (args.length >= 12) {
                Constant.exceptionTesting = args[11].equalsIgnoreCase("true");
            }

            Exli.instrument(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumber, endLineNumber, logFilePath, classesDirectory, r0TestPath, r1TestPath);
        } else if (task.equals("change-modifier") || task.equals("m")) {
            // Change modifier to public (help Randoop generate more tests).
            String filePath = args[1];
            String lineNumber = args[2];
//            Parser.changeModifier(filePath, lineNumber);
        } else if (task.equals("add-block-test") || task.equals("a")) {
            // Add block tests from log file, the log file contains constructed block test
            String testPath = args[1];
            Parser.addBlockTest(testPath);
        } else if (task.equals("mutation")) {
            // Mutation (Create mutants then perform reduction)
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String injectedSrc = args[5];
            int startLine = Utils.parseLineNumber(args[6]);
            int endLine = Utils.parseLineNumber(args[7]);
            String r0TestPath = args[8];
            String r1TestPath = args[9];
            String generateMutant = args.length == 11 ? args[10] : "false"; // if it is false, then we stop after removing failing  tests

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            try {
                Mutator mutator = new Mutator(r0TestPath, r1TestPath);
                // Assumption: injectedSrc currently contains block tests for r0 (because we need to filter out failing tests first)
                mutator.reduce(projectDir, absPathToSrc, outputDir, blockGenDir, injectedSrc, startLine, endLine, generateMutant.equals("true"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (task.equals("mutation-reduction")) {
            // Get r2 (Given generated mutants, perform reduction)
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String injectedSrc = args[5];
            int startLine = Utils.parseLineNumber(args[6]);
            int endLine = Utils.parseLineNumber(args[7]);
            String r0TestPath = args[8];
            String r1TestPath = args[9];

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            try {
                Mutator mutator = new Mutator(r0TestPath, r1TestPath);
                // Assumption: injectedSrc currently contains block tests for r0 (because we need to filter out failing tests first)
                mutator.reduce(projectDir, absPathToSrc, outputDir, blockGenDir, injectedSrc, startLine, endLine, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (task.equals("mutation-score")) {
            // Given generated mutants from "mutants" task, compute mutation score
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String injectedSrc = args[5];
            String mutants = args[6];
            int startLine = Utils.parseLineNumber(args[7]);
            int endLine = Utils.parseLineNumber(args[8]);
            String r2TestPath = args[9];
            if (r2TestPath.equals("manual") && System.getenv("FRAGMENT_ID") != null) {
                MutationScore.manuallyWritten = true;
            }

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            try {
                MutationScore mutator = new MutationScore();
                // Assumption: injectedSrc currently contains block tests for r2 (because we need to filter out failing tests first)
                mutator.calculate(projectDir, absPathToSrc, outputDir, blockGenDir, injectedSrc, mutants, startLine, endLine, r2TestPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (task.equals("mutants")) {
            // Create mutants
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            int startLine = Utils.parseLineNumber(args[5]);
            int endLine = Utils.parseLineNumber(args[6]);

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            try {
                MutationScore mutator = new MutationScore();
                MutationScore.createMutants(projectDir, absPathToSrc, outputDir, blockGenDir, startLine, endLine, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (task.equals("coverage-score")) {
            // Evaluation: compute coverage
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String injectedSrc = args[5];
            int startLine = Utils.parseLineNumber(args[6]);
            int endLine = Utils.parseLineNumber(args[7]);
            String r2TestPath = args[8];

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            try {
                CoverageScore coverage = new CoverageScore();
                coverage.calculate(projectDir, absPathToSrc, outputDir, blockGenDir, injectedSrc, startLine, endLine, r2TestPath);
                // Assumption: injectedSrc currently contains block tests for r0
//                mutator.calculate(projectDir, absPathToSrc, outputDir, blockGenDir, injectedSrc, startLine, endLine, r2TestPath);
            } catch (Exception e) {
                e.printStackTrace();
            }

//            String execFile = args[1];
//            String outputFile = args[2];
//            String projectDir = args[3];
//            String relpathToSrc = args[4];
//            int startLine = Integer.parseInt(args[5]);
//            int endLine = Integer.parseInt(args[6]);
//            boolean checkBranch = false;
//            if (args.length > 7) {
//                checkBranch = Boolean.parseBoolean(args[7]);
//            }
//            if (!new File(execFile).exists()) {
//                return;
//            }
//            CoverageScore coverageChecker = new CoverageScore(relpathToSrc, startLine, endLine, new File(execFile), projectDir, new File(projectDir + File.separator + "target" + File.separator + "classes"));
//            coverageChecker.check(outputFile, checkBranch);
        } else if (task.equals("generation")) {
            // Test generation
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String tool = args[5];
            String relpathToSrc = Paths.get(projectDir).relativize(Paths.get(absPathToSrc)).toString();

            int lineNumber = -1;
            if (args.length == 7) {
                lineNumber = Integer.parseInt(args[6]);
            }

            if (lineNumber == -1 && isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            if (tool.equalsIgnoreCase("evosuite")) {
                EvoSuiteGenerator generator = new EvoSuiteGenerator(projectDir, relpathToSrc, outputDir, blockGenDir, lineNumber);
                generator.generateEvosuiteTests();
            } else if (tool.equalsIgnoreCase("randoop")) {
                RandoopGenerator generator = new RandoopGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.generateRandoopTests(false);
            } else if (tool.equalsIgnoreCase("randoop-class")) {
                RandoopGenerator generator = new RandoopGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.generateRandoopTests(true);
            } else if (tool.equalsIgnoreCase("kex")) {
                KexGenerator generator = new KexGenerator(projectDir, relpathToSrc, outputDir, blockGenDir, lineNumber);
                generator.generateKexTests();
            } else if (tool.equalsIgnoreCase("jdoop")) {
                JDoopGenerator generator = new JDoopGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.generateJDoopTests();
            } else if (tool.equalsIgnoreCase("jdart")) {
                JDartGenerator generator = new JDartGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.generateJDartTests();
            } else {
                System.out.println("Invalid generation tool");
                System.exit(1);
            }
        } else if (task.equals("run")) {
            // Run generated tests
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String tool = args[5];
            String relpathToSrc = Paths.get(projectDir).relativize(Paths.get(absPathToSrc)).toString();

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            if (tool.equalsIgnoreCase("evosuite")) {
                EvoSuiteGenerator generator = new EvoSuiteGenerator(projectDir, relpathToSrc, outputDir, blockGenDir, -1);
                Set<String> classes = generator.compile();
                generator.execute(classes);
            } else if (tool.equalsIgnoreCase("randoop") || tool.equalsIgnoreCase("randoop-class")) {
                RandoopGenerator generator = new RandoopGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.compile();
                generator.execute();
            } else if (tool.equalsIgnoreCase("kex")) {
                KexGenerator generator = new KexGenerator(projectDir, relpathToSrc, outputDir, blockGenDir, -1);
                Set<String> classes = generator.compile();
                generator.execute(classes);
            } else if (tool.equalsIgnoreCase("jdoop")) {
                JDoopGenerator generator = new JDoopGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                Set<String> classes = generator.compile();
                generator.execute(classes);
            } else if (tool.equalsIgnoreCase("jdart")) {
                JDartGenerator generator = new JDartGenerator(projectDir, relpathToSrc, outputDir, blockGenDir);
                generator.compile();
                generator.execute();
            } else {
                System.out.println("Invalid generation tool");
                System.exit(1);
            }
        } else if (task.equals("extraction")) {
            // Extract fragment into a new method
            String projectDir = args[1];
            String absPathToSrc = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String startLineNumber = args[5];
            String endLineNumber = args[6];
            String logFilePath = args[7];
            String classesDirectory = args[8];

            String r0TestPath;
            if (args.length >= 10) {
                r0TestPath = args[9];
            } else {
                r0TestPath = Paths.get(logFilePath).getParent().toString() + "/blocktest-r0.txt";
            }
            String r1TestPath;
            if (args.length >= 11) {
                r1TestPath = args[10];
            } else {
                r1TestPath = Paths.get(logFilePath).getParent().toString() + "/blocktest-r1.txt";
            }

            if (args.length >= 12) {
                Constant.exceptionTesting = args[11].equalsIgnoreCase("true");
            }

            Genie.instrument(projectDir, absPathToSrc, outputDir, blockGenDir, startLineNumber, endLineNumber, logFilePath, classesDirectory, r0TestPath, r1TestPath);
        } else if (task.equals("run-block-tests") || task.equals("rb")) {
            String projectDir = args[1];
            String r2TestPath = args[2];
            String outputDir = args[3];
            String blockGenDir = args[4];
            String absPathToSrc = args[5];

            if (isDependenciesMissing(blockGenDir)) {
                System.exit(1);
            }

            new File(outputDir + File.separator + "tests-reports").mkdirs();
            new File(outputDir + File.separator + "logs").mkdirs();

            Parser.addBlockTest(r2TestPath);
            TestRunner.runBlockTest(projectDir, outputDir, blockGenDir, "run-all.log", absPathToSrc, -1);
        } else {
            System.out.println("Invalid task");
        }
    }

    private static boolean isDependenciesMissing(String blockGenDir) {
        Path path = Paths.get(blockGenDir + File.separator + "extension" + File.separator + "target" + File.separator + "blockgen-extension-1.0.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing Block Gen extension");
            return true;
        }

        path = Paths.get(blockGenDir + File.separator + "libs" + File.separator + "blocktest-1.0.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing Block Test library");
            return true;
        }

        path = Paths.get(blockGenDir + File.separator + "libs" + File.separator + "evosuite-master-1.2.1-SNAPSHOT.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing EvoSuite library");
            return true;
        }

        path = Paths.get(blockGenDir + File.separator + "libs" + File.separator + "randoop-all-4.3.3.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing Randoop library");
            return true;
        }

        path = Paths.get(blockGenDir + File.separator + "libs" + File.separator + "org.jacoco.agent-0.8.14-runtime.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing JaCoCO runtime library");
            return true;
        }

        path = Paths.get(blockGenDir + File.separator + "libs" + File.separator + "junit-platform-console-standalone-1.12.0.jar");
        if (!Files.exists(path)) {
            System.out.println("Missing JaCoCO runtime library");
            return true;
        }
        return false;
    }
}
