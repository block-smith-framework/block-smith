package org.blockgen.mutation;

import org.blockgen.Utils;
import org.blockgen.helpers.BlockTestRemover;
import org.blockgen.helpers.FragmentCollector;
import org.blockgen.utils.FailingTestRemover;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MutationScore {

    public static boolean manuallyWritten = false;

    private final Set<String> r2Tests = new HashSet<>();
    private int numMutants = 0;
    private int numKilledMutants = 0;
    private int testCount = 0;

    /*
     * Create mutants once
     * Prediction: absPathToSrc must be the original files, without any modification
     */
    // Create mutants once
    public static void createMutants(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, int startLine, int endLine, boolean shouldInit) throws Exception {
        if (shouldInit) {
            new File(outputDir).getParentFile().mkdirs();
            new File(outputDir).mkdirs();
            new File(outputDir + File.separator + "tests-reports").mkdirs();
            new File(outputDir + File.separator + "logs").mkdirs();
            new File(outputDir + File.separator + "failed-mutants").mkdirs();
            Utils.replaceFile(outputDir + File.separator + "backup.java", absPathToSrc);
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(Paths.get(absPathToSrc)));
        lines.add(endLine, "System.out.println(\"BLOCKGEN_FRAGMENT_END\");");
        lines.add(startLine - 1, "System.out.println(\"BLOCKGEN_FRAGMENT_STARTS\");");
        Files.write(Paths.get(absPathToSrc), lines);

        String injectedSrc = outputDir + File.separator + "injected.java";
        Files.copy(Paths.get(absPathToSrc), Paths.get(injectedSrc), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
//        FragmentCollector.convertToStatic(absPathToSrc);

        String lineNumbersFile = outputDir + File.separator + "line-numbers.txt";
        try (PrintWriter writer = new PrintWriter(new File(lineNumbersFile))) {
            for (int i = startLine + 1; i <= endLine + 1; i++) {
                writer.println(i);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        int mutantCountRaw = Mutator.generateMutants(projectDir, absPathToSrc, outputDir, startLine, endLine, -1, -1);
        BlockTestRemover.removeFragmentMarkers(absPathToSrc);
        int mutantCountPassedCompilation = Mutator.compileMutants(projectDir, absPathToSrc, injectedSrc, outputDir + File.separator + "mutants", blockGenDir, outputDir);
    }

    // Calculate mutation score
    public void calculate(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String injectedSrc, String mutantsDir, int startLine, int endLine, String r2TestPath) throws Exception {
        List<String> tests = new ArrayList<>();
        try {
            if (!manuallyWritten) {
                for (String test : Files.readAllLines(Paths.get(r2TestPath))) {
                    String testName = test.split(";")[2].split("\"")[1];
                    r2Tests.add(testName);
                    tests.add(test);
                    testCount += 1;
                }
            } else {
                Utils.replaceFile(outputDir + File.separator + "tmp-backup.java", injectedSrc);
                List<String> manualFileLines = new ArrayList<>(Files.readAllLines(Paths.get(injectedSrc)));
                manualFileLines.removeIf(line -> line.contains("BLOCKGEN START") || line.contains("BLOCKGEN END"));
                Files.write(Paths.get(injectedSrc), manualFileLines);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Failed to read test file r2.");
            return;
        }

        // Create mutant directory if not exists
        new File(outputDir).getParentFile().mkdirs();
        new File(outputDir).mkdirs();
        new File(outputDir + File.separator + "tests-reports").mkdirs();
        new File(outputDir + File.separator + "logs").mkdirs();
        Utils.replaceFile(outputDir + File.separator + "backup.java", absPathToSrc);
        Utils.replaceFile(absPathToSrc, injectedSrc);

        if (testCount == 0 && !MutationScore.manuallyWritten) {
            System.out.println("No tests found in r2 test file. No need to run mutation testing. Mutation score is 0%.");
            return;
        }

        int removedTests = FailingTestRemover.removeFailingTests(r2Tests, projectDir, outputDir, blockGenDir, absPathToSrc);
        tests.removeIf(test -> {
            String testName = test.split(";")[2].split("\"")[1];
            if (!r2Tests.contains(testName)) {
                System.out.println("Removed failing test " + testName + " from r2 tests.");
                return true;
            }
            return false;
        });
        if (MutationScore.manuallyWritten && removedTests != 0) {
            System.out.println("Some manually written tests are failing.");
            return;
        } else if (MutationScore.manuallyWritten) {
            Utils.moveFile(injectedSrc, outputDir + File.separator + "tmp-backup.java");
            Utils.replaceFile(absPathToSrc, injectedSrc);
        }

        List<Integer> mutantsID = new ArrayList<>();
        List<Mutant> mutants = new ArrayList<>();
        List<String> mutantFiles = Arrays.stream(new File(mutantsDir).listFiles()).map(File::getName).collect(Collectors.toList());
        for (String file : mutantFiles) {
            if (file.endsWith("SKIP")) continue;
            String[] s = file.split("\\.");
            mutantsID.add(Integer.parseInt(s[s.length-2]));
        }

        for (Integer id : mutantsID) {
            String mutantFileName = "mutant." + id + ".java";
            String matchingFile = mutantFiles.stream()
                    .filter(name -> name.endsWith(mutantFileName))
                    .findFirst()
                    .orElse(null);
            if (matchingFile != null) {
                String absPathToMutant = mutantsDir + File.separator + matchingFile;
                String originalContent = String.join("\n", Files.readAllLines(Paths.get(absPathToSrc)));
                String mutatedContent = String.join("\n", Files.readAllLines(Paths.get(absPathToMutant)));
                mutants.add(new Mutant(id, absPathToSrc, absPathToMutant, originalContent, mutatedContent));
            }
        }

        numMutants = mutants.size();
//        for (Mutant mutant : mutants) {
//            runWithMutant(projectDir, outputDir, blockGenDir, mutant, tests);
//        }

        if (!MutationScore.manuallyWritten && tests.isEmpty()) {
            System.out.println("Total mutants: " + numMutants + " and killed mutants " + numKilledMutants + " and removed " + removedTests + " broken block tests");
            return;
        }

        MutantRunner mutantRunner = new MutantRunner(null, null, null, null, null, null);
        numKilledMutants = mutantRunner.scoreAllMutants(mutants, projectDir, outputDir, blockGenDir, tests);
        System.out.println("Total mutants: " + numMutants + " and killed mutants " + numKilledMutants + " and removed " + removedTests + " broken block tests");
    }
}
