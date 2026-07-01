package org.blockgen.mutation;

import org.blockgen.Constant;
import org.blockgen.Utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;


// Source: from Block Tests and Genie papers
// Assumed universalmutator is installed (pip install universalmutator)
public class Mutator {

    private String r0TestFile;
    private String r1TestFile;
    private String r2TestFile;

    private final ConcurrentHashMap<String, Set<Integer>> r0BlockTestToKilledMutants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Integer>> r1BlockTestToKilledMutants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<String>> mutantToR0Test = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<String>> mutantToR1Test = new ConcurrentHashMap<>();

    private final List<String> tests = new ArrayList<>();
    private final Set<String> r0Tests = new HashSet<>();
    private final Set<String> r1Tests = new HashSet<>();

    private final Map<String, String> nameToTest = new HashMap<>();

    private int testCount = 0;

    public Mutator(String r0TestFile, String r1TestFile) {
        this.r0TestFile = r0TestFile;
        this.r1TestFile = r1TestFile;
        Path r0Path = Paths.get(r0TestFile);
        this.r2TestFile = r0Path.getParent().toString() + File.separator + "blocktest-r2.txt";

        try {
            for (String test : Files.readAllLines(r0Path)) {
                String testName = test.split(";")[2].split("\"")[1];
                r0Tests.add(testName);
                tests.add(test);
                nameToTest.put(testName, test);
                testCount += 1;
            }
            System.out.println("r0: " + r0Tests);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Failed to read test file r0.");
        }

        try {
            for (String test : Files.readAllLines(Paths.get(r1TestFile))) {
                String testName = test.split(";")[2].split("\"")[1];
                r1Tests.add(testName);
            }
            System.out.println("r1: " + r1Tests);
        } catch (Exception ex) {
            System.out.println("Failed to read test file r1.");
        }
    }

    /**
     * @param projectDir The root directory of the project to be mutated, which contains the src directory and pom.xml file.
     * @param absPathToSrc The absolute path to the source file to be mutated. For example, /home/user/project/src/main/java/com/example/MyClass.java
     * @param outputDir The directory to store the generated mutants and compilation results. For example, /home/user/output
     * @param blockGenDir BlockGen directory
     * @param injectedSrc The absolute path to the source file with block tests injected.
     * @param startLine The start line number of the target code fragment to be mutated. The line numbers are based on the original source file before block test injection.
     * @param endLine The end line number of the target code fragment to be mutated. The line numbers are based on the original source file before block test injection.
     */
    public void reduce(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String injectedSrc, int startLine, int endLine, boolean generateMutant) throws Exception {
        // Create mutant directory if not exists
        new File(outputDir).getParentFile().mkdirs();
        new File(outputDir).mkdirs();
        new File(outputDir + File.separator + "tests-reports").mkdirs();
        new File(outputDir + File.separator + "logs").mkdirs();
        new File(outputDir + File.separator + "failed-mutants").mkdirs();
        Utils.replaceFile(outputDir + File.separator + "backup.java", absPathToSrc);
        Utils.replaceFile(absPathToSrc, injectedSrc);

        int numRemovedTest = removeFailingTests(projectDir, outputDir, blockGenDir, absPathToSrc);
        Set<String> removedTests = new HashSet<>();
        tests.removeIf(test -> {
            String testName = test.split(";")[2].split("\"")[1];
            if (!r0Tests.contains(testName)) {
                System.out.println("Removed failing test " + testName + " from r0 tests.");
                removedTests.add(testName);
                return true;
            }
            return false;
        });
        if (!removedTests.isEmpty()) {
            try {
                for (String testFilePath : new String[]{r0TestFile, r1TestFile}) {
                    Path testPath = Paths.get(testFilePath);
                    Files.copy(testPath, Paths.get(testFilePath + ".old"), StandardCopyOption.REPLACE_EXISTING);
                    List<String> filtered = Files.readAllLines(testPath).stream()
                        .filter(line -> removedTests.stream().noneMatch(t -> line.contains("\"" + t + "\"")))
                        .collect(Collectors.toList());
                    Files.write(testPath, filtered);
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        Utils.replaceFile(absPathToSrc, outputDir + File.separator + "backup.java");

        if (!generateMutant) {
            System.out.println("Mutant generation is skipped as generateMutant is false.");
            return;
        }

        // TODO: if hasMutants is true, we want to insert block tests to mutants, similar to how we calculate mutation score
        boolean hasMutants = new File(outputDir + File.separator + "compilable-mutants.txt").exists() && new File(outputDir + File.separator + "mutants").listFiles() != null;
        if (!hasMutants) {
            // Does not have mutants yet
//            int mutantCountRaw = generateMutants(projectDir, absPathToSrc, outputDir, startLine, endLine, testCount, numRemovedTest);
//            int mutantCountPassedCompilation = compileMutants(projectDir, absPathToSrc, injectedSrc, outputDir + File.separator + "mutants", blockGenDir, outputDir);
//
            MutationScore.createMutants(projectDir, absPathToSrc, outputDir, blockGenDir, startLine, endLine, false);
        }

        List<Integer> mutantsID = Files.readAllLines(Paths.get(outputDir + File.separator + "compilable-mutants.txt"))
            .stream()
            .map(Integer::parseInt)
            .collect(Collectors.toList());

        List<Mutant> mutants = new ArrayList<>();
        List<String> mutantFiles = Arrays.stream(new File(outputDir + File.separator + "mutants").listFiles()).map(File::getName).collect(Collectors.toList());
        for (Integer id : mutantsID) {
            String mutantFileName = "mutant." + id + ".java";
            String matchingFile = mutantFiles.stream()
                .filter(name -> name.endsWith(mutantFileName))
                .findFirst()
                .orElse(null);
            if (matchingFile != null) {
                String absPathToMutant = outputDir + File.separator + "mutants" + File.separator + matchingFile;
                String originalContent = String.join("\n", Files.readAllLines(Paths.get(absPathToSrc)));
                String mutatedContent = String.join("\n", Files.readAllLines(Paths.get(absPathToMutant)));
                mutants.add(new Mutant(id, absPathToSrc, absPathToMutant, originalContent, mutatedContent));
            }
        }

        MutantRunner mutantRunner = new MutantRunner(r0Tests, r1Tests, r0BlockTestToKilledMutants, r1BlockTestToKilledMutants, mutantToR0Test, mutantToR1Test);
        mutantRunner.runAllMutants(mutants, projectDir, outputDir, blockGenDir, tests);

        System.out.println("r0BlockTestToKilledMutants: " + r0BlockTestToKilledMutants);
        System.out.println("r1BlockTestToKilledMutants: " + r1BlockTestToKilledMutants);
        System.out.println("mutantToR0Test: " + mutantToR0Test);
        System.out.println("mutantToR1Test: " + mutantToR1Test);

        selectR2();
    }

    // Goal: Remove failing block tests from r0/r1, we only need to run r1
    private int removeFailingTests(String projectDir, String outputDir, String blockGenDir, String absPathToSrc) {
        System.out.println("===== Removing failing tests from r0 and r1 =====");
        // TODO: update BDK to add the ability to generate test per class, so we can identify which test fails to compile

        int status = TestRunner.runBlockTest(projectDir, outputDir, blockGenDir, "run-all.log", absPathToSrc, -1);
        System.out.println("Status for r0 is " + status);
        Set<String> passingTests = new HashSet<>();
        // Process test result
        if (new File(outputDir + File.separator + "tests-reports" + File.separator + "all").listFiles() == null ||
                new File(outputDir + File.separator + "tests-reports" + File.separator + "all" + File.separator + "tmp").listFiles() == null ||
        new File(outputDir + File.separator + "tests-reports" + File.separator + "all" + File.separator + "tmp").listFiles().length == 0) {
            // BDK generated tests fail to compile
            System.out.println("BDK generated tests fail to compile");
            Set<String> brokenTests = TestRunner.identifyBrokenTests(projectDir, outputDir, blockGenDir, "run-all.log", absPathToSrc, -1);
            if (!brokenTests.isEmpty()) {
                System.out.println("Removed tests that fail to compile: " + brokenTests);
                status = TestRunner.runBlockTest(projectDir, outputDir, blockGenDir, "run-all.log", absPathToSrc, -1);
                System.out.println("Status for new r0 is " + status);
            } else {
                System.out.println("Failed to repair");
            }
        }
        Arrays.stream(new File(outputDir + File.separator + "tests-reports" + File.separator + "all" + File.separator + "tmp").listFiles())
            .filter(file -> file.getName().endsWith(".xml") && file.getName().startsWith("TEST-")).forEach(file ->{
                try {
                    JSONObject root = XML.toJSONObject(new String(Files.readAllBytes(file.getAbsoluteFile().toPath())));
                    JSONObject testsuite = root.getJSONObject("testsuite");
                    try {
                        JSONArray testcases = testsuite.getJSONArray("testcase");
                        for (int i = 0; i < testcases.length(); i++) {
                            addPassingTests(testcases.getJSONObject(i), passingTests);
                        }
                    } catch (JSONException ex) {
                        try {
                            JSONObject testcase = testsuite.getJSONObject("testcase");
                            addPassingTests(testcase, passingTests);
                        } catch (JSONException ex2) {
                            System.out.println("removeFailingTests - Malformed test report. Cannot identify testcase.");
                            passingTests.add("ERROR");
                        }
                    }
                } catch (JSONException ignored) {
                    System.out.println("runWithMutant - Malformed test report.");
                } catch (Exception ex) {
                    System.out.println("removeFailingTests - Malformed test report.");
                    passingTests.add("ERROR");
                }
            });

        if (passingTests.contains("ERROR") && status == 0) {
            System.out.println("Will not remove test because status is 0 anyway");
            return 0;
        }

        Set<String> removedTests = new HashSet<>(r0Tests); // All r0 - passing = failing
        removedTests.removeAll(passingTests);

        r0Tests.retainAll(passingTests);
        r1Tests.retainAll(passingTests);

        System.out.println("There are " + removedTests.size() + " failing tests in r0: " + removedTests);
        System.out.println("After filtering failing tests, the remaining tests are:");
        System.out.println("r0: " + r0Tests);
        System.out.println("r1: " + r1Tests);

//      // KEEP ONLY ONE R0 FOR EXPERIMENT
//      if (r0Tests.size() > 1) {
//          Iterator<String> iter = r0Tests.iterator();
//          iter.next();
//          while (iter.hasNext()) {
//              String testToRemove = iter.next();
//              removedTests.add(testToRemove);
//          }
//      }

        if (removedTests.isEmpty())
            return 0;

        // Use removedTestsWithQuote to avoid partial match. For example "AUTO_GEN_11" should not match "AUTO_GEN_1"
        Set<String> removedTestsWithQuote = new HashSet<>();
        for (String removedTest : removedTests) {
            removedTestsWithQuote.add("\"" + removedTest + "\"");
        }
        // Now we have to update the injected file to remove failing tests
        try {
            Path path = Paths.get(absPathToSrc);
            List<String> filtered = Files.readAllLines(path)
                    .stream()
                    .filter(line -> removedTestsWithQuote.stream().noneMatch(line::contains))
                    .collect(Collectors.toList());
            Files.write(path, filtered);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Failed to update injected source file after removing failing tests.");
        }

        return removedTests.size();
    }

    private static Map<Integer, Integer> getLineMap(String absPathToSrc, String injectedSrc) {
        Map<Integer, Integer> lineNumberMap = new HashMap<>();
        List<String> originalLines = new ArrayList<>();
        List<String> injectedLines = new ArrayList<>();
        try {
            originalLines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(absPathToSrc));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        try {
            injectedLines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(injectedSrc));
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        Map<String, List<Integer>> injectedLineMap = new HashMap<>();
        for (int injectedLineNumber = 1; injectedLineNumber <= injectedLines.size(); injectedLineNumber++) {
            String injectedLine = injectedLines.get(injectedLineNumber - 1).replaceAll("\\s+", "");
            injectedLineMap.computeIfAbsent(injectedLine, key -> new ArrayList<>()).add(injectedLineNumber);
        }

        for (int originalLineNumber = 1; originalLineNumber <= originalLines.size(); originalLineNumber++) {
            String originalLine = originalLines.get(originalLineNumber - 1).replaceAll("\\s+", "");
            List<Integer> matches = injectedLineMap.get(originalLine);
            if (matches != null && !matches.isEmpty()) {
                // Use the first match and remove it to avoid duplicate mapping
                lineNumberMap.put(originalLineNumber, matches.remove(0));
            } else {
                lineNumberMap.put(originalLineNumber, -1);
            }
        }
        // System.out.println("Line number map: " + lineNumberMap);
        return lineNumberMap;
    }

    public static int generateMutants(String projectDir, String absPathToSrc, String outputDir, int startLine, int endLine, int testCount, int numRemovedTest) {
        String mutantsDir = outputDir + File.separator + "mutants";
        if (!new File(mutantsDir).exists()) {
            new File(mutantsDir).mkdirs();
        }
        Mutator.prepareLineNumbersFile(outputDir, absPathToSrc, startLine, endLine, testCount, numRemovedTest);
        int mutantCount = mutate(absPathToSrc, outputDir, mutantsDir);
        return mutantCount;
    }

    private static void prepareLineNumbersFile(String outputDir, String absPathToSrc, int startLine, int endLine, int testCount, int numRemovedTest) {
        String lineNumbersFile = outputDir + File.separator + "line-numbers.txt";

        if (Files.exists(Paths.get(lineNumbersFile))) {
            System.out.println("Line numbers file already exists at " + lineNumbersFile + ", skipping preparation.");
            return;
        }

        System.out.println("===== Preparing line numbers file =====");

        // startLine+4+testCount
        // startLine+4+testCount+(endLine-startLine)

        int startAt = startLine + 4 + testCount - numRemovedTest; // 4 imports
        int endAt = startLine + 4 + testCount + (endLine - startLine) - numRemovedTest;

        Utils.replaceFile(outputDir + File.separator + "before-mutation.java", absPathToSrc);

        try {
            boolean startedTests = false;
            boolean startedFragment = false;
            boolean isEnded = false;
            int startAtFromFile = -1;
            int endAtFromFile = -1;
            int lineNum = 0;
            for (String line : Files.readAllLines(Paths.get(absPathToSrc))) {
                lineNum += 1;
                if (line.contains("blocktest(")) {
                    if (line.contains("given(") || line.contains("mock(")) {
                        startedTests = true;
                    } else if (line.contains("end()")) {
                        if (startedFragment) {
                            endAtFromFile = lineNum - 1;
                            break;
                        }
                    }
                } else {
                    if (startedTests) {
                        startedTests = false;
                        startedFragment = true;
                        startAtFromFile = lineNum;

                        if (isEnded) {
                            endAtFromFile = lineNum;
                            break;
                        }
                    }
                }

                if (line.contains("BLOCKTEST_FRAGMENT_END_HERE")) {
                    if (startedFragment) {
                        endAtFromFile = lineNum + 1; // no -1, it will get push up one line
                        break;
                    } else {
                        // We see BLOCKTEST_FRAGMENT_END_HERE before we finished the tests, so when we finished tests we should also end the fragment
                        isEnded = true;
                    }
                }
            }

            if (testCount == -1 || numRemovedTest == -1) {
                startAt = startAtFromFile;
                endAt = endAtFromFile;
            }

            if (startAtFromFile == -1) {
                System.out.println("Unable to find the last blocktest().given()");
            }

            if (endAtFromFile == -1) {
                System.out.println("Unable to find the first blocktest().end()");
            }

            if (startAtFromFile != startAt) {
                System.out.println("Warning: The computed start line number " + startAt + " does not match the line number " + startAtFromFile + " identified from the file");
                startAt = startAtFromFile;
            }

            if (endAtFromFile != endAt) {
                System.out.println("Warning: The computed end line number " + endAt + " does not match the line number " + endAtFromFile + " identified from the file");
                endAt = endAtFromFile;
            }

            try (PrintWriter writer = new PrintWriter(new File(lineNumbersFile))) {
                for (int i = startAt; i <= endAt; i++) {
                    writer.println(i);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception ex) {

        }
    }

    private static int  mutate(String absPathToSrc, String outputDir, String mutantsDir) {
        System.out.println("===== Generating mutants with universalmutator =====");

        String lineNumbersList = outputDir + File.separator + "line-numbers.txt";
        String umLog = outputDir + File.separator + "universalmutator-log.txt";
        List<String> command = new ArrayList<>(Arrays.asList("mutate", absPathToSrc, "--noCheck",
                "--mutantDir", mutantsDir, "--lines", lineNumbersList));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(outputDir));
        pb.redirectOutput(new File(umLog));
        pb.redirectErrorStream(true);
        try {
            pb.start().waitFor();
        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
        }
        File mutantsDirectory = new File(mutantsDir);
        File[] mutantFiles = mutantsDirectory.listFiles();
        int mutantCount = (mutantFiles == null) ? 0 : mutantFiles.length;
        if (mutantCount == 0) {
            System.out.println("No mutants are generated.");
//            Utils.earlyExitWithLog(outputDir, "No mutants generated. Exiting.");
        }
        System.out.println("Generated a total of " + mutantCount + " mutants.");
        return mutantCount;
    }

    /**
     * @return The number of mutants that compiled successfully.
     */
    public static int compileMutants(String projectDir, String originalFilePath, String injectedSrc, String mutantsDir, String blockGenDir, String outputDir) {
        System.out.println("===== Compiling mutants =====");

        Utils.compileProjectWithBTest(projectDir, originalFilePath, outputDir, blockGenDir);
        File[] mutantFiles = new File(mutantsDir).listFiles();
        int beforeCount = mutantFiles.length;

        Set<Integer> compilableMutants = MutantCompiler.compileMutants(projectDir, originalFilePath, injectedSrc, mutantsDir, blockGenDir, outputDir);

        mutantFiles = new File(mutantsDir).listFiles();
        int afterCount = mutantFiles.length;
        System.out.println(afterCount + " out of " + beforeCount + " mutants compiled successfully.");
        String compilableMutantsFile = outputDir + File.separator + "compilable-mutants.txt";
        try (PrintWriter writer = new PrintWriter(new File(compilableMutantsFile))) {
            for (Integer mutantId : compilableMutants) {
                writer.println(mutantId);
            }
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        }
        return afterCount;
    }

    // Goal: map block test to killed mutants
    private void runWithMutant(String projectDir, String outputDir, String blockGenDir, Mutant mutant) {
        System.out.println("Running mutant " + mutant.getSerial());
        Utils.replaceFile(mutant.getOriginalFilePath(), mutant.getMutantFilePath());
        int status = TestRunner.runBlockTest(projectDir, outputDir, blockGenDir, "run-mutant-" + mutant.getSerial() + ".log", mutant.getOriginalFilePath(), mutant.getSerial());
        System.out.println("Status for mutant " + mutant.getSerial() + " is " + status);


        File directory = new File(outputDir + File.separator + "tests-reports" + File.separator + "mutant-" + mutant.getSerial());
        if (!directory.exists() || !directory.isDirectory()) {
            // Ignore mutant (does not compile)
            return;
        }

        // Process test result
        Arrays.stream(new File(outputDir + File.separator + "tests-reports" + File.separator + "mutant-" + mutant.getSerial()).listFiles())
            .filter(file -> file.getName().endsWith(".xml") && file.getName().startsWith("TEST-")).forEach(file ->{
                    try {
                        JSONObject root = XML.toJSONObject(new String(Files.readAllBytes(file.getAbsoluteFile().toPath())));
                        JSONObject testsuite = root.getJSONObject("testsuite");
                        try {
                            JSONArray testcases = testsuite.getJSONArray("testcase");
                            for (int i = 0; i < testcases.length(); i++) {
                                processTestCase(testcases.getJSONObject(i), mutant.getSerial());
                            }
                        } catch (JSONException ex) {
                            try {
                                JSONObject testcase = testsuite.getJSONObject("testcase");
                                processTestCase(testcase, mutant.getSerial());
                            } catch (JSONException ex2) {
                                ex2.printStackTrace();
                                System.out.println("runWithMutant - Malformed test report. Cannot identify testcase.");
                            }
                        }
                    } catch (JSONException ignored) {
                        System.out.println("runWithMutant - Malformed test report.");
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        System.out.println("runWithMutant - Malformed test report.");
                    }
            });
    }

    private void processTestCase(JSONObject testcase, Integer mutantId) {
        String testId = "#"
                + testcase.optString("classname").substring(testcase.optString("classname").lastIndexOf(".") + 1) + "#"
                + testcase.optString("name");
        System.out.println("Calling processTestCase with - mutant: " + mutantId + " testId: " + testId);
        String blockTestID = testcase.optString("name");
        if (!r0Tests.contains(blockTestID) && !r1Tests.contains(blockTestID)) {
            // Not a block test, ignore
            System.out.println(blockTestID + " is not in R0 or R1, ignore.");
            return;
        }

        Map<String, Set<Integer>> aggregationMap = r1Tests.contains(blockTestID) ? r1BlockTestToKilledMutants : r0BlockTestToKilledMutants;
        if (testcase.has("error") || testcase.has("failure")) {
            Set<Integer> newKilledSet = aggregationMap.getOrDefault(blockTestID, new HashSet<>());
            newKilledSet.add(mutantId);
            aggregationMap.put(blockTestID, newKilledSet);
            if (r1Tests.contains(blockTestID))
                mutantToR1Test.computeIfAbsent(mutantId, key -> new HashSet<>()).add(blockTestID);
            else
                mutantToR0Test.computeIfAbsent(mutantId, key -> new HashSet<>()).add(blockTestID);
        } else {
            aggregationMap.put(blockTestID, aggregationMap.getOrDefault(blockTestID, new HashSet<>()));
        }
    }

    // Return if a test is failing or passing
    private void addPassingTests(JSONObject testcase, Set<String> passingTests) {
        String blockTestID = testcase.optString("name");
        if (!r0Tests.contains(blockTestID) && !r1Tests.contains(blockTestID)) {
            // Not a block test, ignore
            return;
        }
        if (!testcase.has("error") && !testcase.has("failure")) {
            passingTests.add(blockTestID);
        }
    }


    // Goal: for all tests in R0 but not R1, check if it kills new mutants that are not killed by any test in R1.
    // If yes, add it to R2. Also, add R0 test to R2 if it does not kill any mutants (Still not sure why we need this)
    private void selectR2() {
        System.out.println("===== Selecting R2 tests =====");

        boolean greedyMode = "true".equals(System.getenv("MUTATION_GREEDY"));

        Set<Integer> totalKilledMutants = new HashSet<>();
        Set<String> selectedTest;

        if (greedyMode) {
            // R1 is always a subset of R0, so r0BlockTestToKilledMutants already
            // contains every R1 test — no merge needed.
            System.out.println("Greedy mode enabled: selecting from R0 tests (R1 is a subset of R0).");
            selectedTest = new HashSet<>();

            while (true) {
                String bestTest = null;
                int bestCount = 0;
                for (Map.Entry<String, Set<Integer>> entry : r0BlockTestToKilledMutants.entrySet()) {
                    String test = entry.getKey();
                    if (selectedTest.contains(test)) continue;
                    Set<Integer> newKills = new HashSet<>(entry.getValue());
                    newKills.removeAll(totalKilledMutants);
                    if (newKills.size() > bestCount) {
                        bestCount = newKills.size();
                        bestTest = test;
                    }
                }
                if (bestTest == null) break;
                System.out.println("Selected test " + bestTest + " which kills " + bestCount + " new mutants for r2.");
                selectedTest.add(bestTest);
                totalKilledMutants.addAll(r0BlockTestToKilledMutants.get(bestTest));
            }

            // Shadow run: show what non-greedy would have selected (not written to disk)
            System.out.println("===== [Shadow] Non-greedy selection (for comparison) =====");
            Set<String> shadowSelected = new HashSet<>(r1Tests);
            Set<Integer> shadowKilled = new HashSet<>();
            for (String r1Test : r1Tests) {
                System.out.println("[Shadow] Selected r1 test " + r1Test);
                shadowKilled.addAll(r1BlockTestToKilledMutants.getOrDefault(r1Test, new HashSet<>()));
            }
            while (true) {
                String bestTest = null;
                int bestCount = 0;
                for (Map.Entry<String, Set<Integer>> entry : r0BlockTestToKilledMutants.entrySet()) {
                    String test = entry.getKey();
                    if (shadowSelected.contains(test)) continue;
                    Set<Integer> newKills = new HashSet<>(entry.getValue());
                    newKills.removeAll(shadowKilled);
                    if (newKills.size() > bestCount) {
                        bestCount = newKills.size();
                        bestTest = test;
                    }
                }
                if (bestTest == null) break;
                System.out.println("[Shadow] Selected r0 test " + bestTest + " which kills " + bestCount + " new mutants for r2.");
                shadowSelected.add(bestTest);
                shadowKilled.addAll(r0BlockTestToKilledMutants.get(bestTest));
            }
            for (String r0Test : r0Tests) {
                if (!shadowSelected.contains(r0Test) && r0BlockTestToKilledMutants.getOrDefault(r0Test, new HashSet<>()).isEmpty()) {
                    System.out.println("[Shadow] Selected r0 test " + r0Test + " which does not kill any mutant for r2.");
                    shadowSelected.add(r0Test);
                }
            }
            System.out.println("[Shadow] Selected R2 tests: " + shadowSelected);
            System.out.println("[Shadow] R2 killed mutants: " + shadowKilled);

        } else {
            selectedTest = new HashSet<>(r1Tests);

            // First, use all R1 tests
            for (String r1Test : r1Tests) {
                System.out.println("Selected r1 test " + r1Test);
                Set<Integer> killedByR1Test = r1BlockTestToKilledMutants.getOrDefault(r1Test, new HashSet<>());
                totalKilledMutants.addAll(killedByR1Test);
            }

            // Find R0 tests that killed the most remaining mutants
            while (true) {
                String mostKilledTests = null;
                int mostKilledMutants = 0;
                for (Map.Entry<String, Set<Integer>> entry : r0BlockTestToKilledMutants.entrySet()) {
                    String test = entry.getKey();
                    if (selectedTest.contains(test)) continue;
                    Set<Integer> testKilledRemainingMutants = new HashSet<>(entry.getValue());
                    testKilledRemainingMutants.removeAll(totalKilledMutants);
                    if (testKilledRemainingMutants.size() > mostKilledMutants) {
                        mostKilledMutants = testKilledRemainingMutants.size();
                        mostKilledTests = test;
                    }
                }
                if (mostKilledTests != null) {
                    System.out.println("Selected r0 test " + mostKilledTests + " which kills " + mostKilledMutants + " new mutants for r2.");
                    selectedTest.add(mostKilledTests);
                    totalKilledMutants.addAll(r0BlockTestToKilledMutants.get(mostKilledTests));
                } else {
                    // Can't find tests that kill new remaining mutants, stop here
                    break;
                }
            }

            for (String r0Test : r0Tests) {
                if (!selectedTest.contains(r0Test)) {
                    if (!r0BlockTestToKilledMutants.containsKey(r0Test)) {
                        System.out.println("Cannot find test " + r0Test + " in r0BlockTestToKilledMutants, skip.");
                        continue;
                    }

                    if (r0BlockTestToKilledMutants.get(r0Test).isEmpty()) {
                        System.out.println("Selected r0 test " + r0Test + " which does not kill any mutant for r2.");
                        selectedTest.add(r0Test);
                    }
                }
            }
        }

        System.out.println("Selected R2 tests: " + selectedTest);
        System.out.println("R2 killed mutants: " + totalKilledMutants);

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(r2TestFile))) {
            for (String s : selectedTest.stream().sorted().collect(Collectors.toList())) {
                if (!nameToTest.containsKey(s)) {
                    System.out.println("Cannot find test " + s + " in the original test file, skip.");
                    continue;
                }

                writer.write(nameToTest.get(s));
                writer.newLine();
            }
            System.out.println("r2 file is saved to " + r2TestFile);
        } catch (IOException ex) {
            ex.printStackTrace();
            System.out.println("Failed to write R2 test file.");
        }
    }
}
