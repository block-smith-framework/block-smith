package org.blockgen.mutation;

import org.blockgen.Constant;
import org.blockgen.Utils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MutantRunner {

    private Set<String> r0Tests;
    private Set<String> r1Tests;

    private ConcurrentHashMap<String, Set<Integer>> r0BlockTestToKilledMutants;
    private ConcurrentHashMap<String, Set<Integer>> r1BlockTestToKilledMutants;
    private ConcurrentHashMap<Integer, Set<String>> mutantToR0Test;
    private ConcurrentHashMap<Integer, Set<String>> mutantToR1Test;

    private boolean resultOnly = false;


    public MutantRunner(Set<String> r0Tests, Set<String> r1Tests, ConcurrentHashMap<String, Set<Integer>> r0BlockTestToKilledMutants,
                        ConcurrentHashMap<String, Set<Integer>> r1BlockTestToKilledMutants, ConcurrentHashMap<Integer, Set<String>> mutantToR0Test,
                        ConcurrentHashMap<Integer, Set<String>> mutantToR1Test) {
        this.r0Tests = r0Tests;
        this.r1Tests = r1Tests;

        this.r0BlockTestToKilledMutants = r0BlockTestToKilledMutants;
        this.r1BlockTestToKilledMutants = r1BlockTestToKilledMutants;
        this.mutantToR0Test = mutantToR0Test;
        this.mutantToR1Test = mutantToR1Test;

        if (r0Tests == null || r1Tests == null || r0BlockTestToKilledMutants == null || r1BlockTestToKilledMutants == null || mutantToR0Test == null || mutantToR1Test == null) {
            System.out.println("MutantRunner: Input maps contain null value. Report status result only.");
            this.resultOnly = true;
        }
    }

    /*
     * Run all mutants and map block test to killed mutants (for reduction)
     */
    public void runAllMutants(List<Mutant> mutants, String projectDir, String outputDir, String blockGenDir, List<String> tests) {
        ExecutorService executor = Executors.newFixedThreadPool(Constant.THREAD_POOL_SIZE);
        List<Future<Integer>> futures = new ArrayList<>();

        for (Mutant mutant : mutants) {
            futures.add(executor.submit(() -> runWithMutant(projectDir, outputDir, blockGenDir, mutant, tests)));
        }

        for (Future<Integer> future : futures) {
            try {
                int result = future.get();
                if (result > 0) {
                } else if (result < 0) {
                    System.err.println("MutantRunner: mutant execution failed or was skipped; not counting as killed.");
                }
            } catch (Exception e) {
                System.err.println("MutantRunner: failed to retrieve mutant execution result.");
                e.printStackTrace();
            }
        }

        executor.shutdown();
    }

    /*
     * Run all mutants and calculate number of killed mutants (for mutation score)
     */
    public int scoreAllMutants(List<Mutant> mutants, String projectDir, String outputDir, String blockGenDir, List<String> tests) {
        ExecutorService executor = Executors.newFixedThreadPool(Constant.THREAD_POOL_SIZE);
        List<Future<Integer>> futures = new ArrayList<>();

        int numKilledMutants = 0;

        for (Mutant mutant : mutants) {
            futures.add(executor.submit(() -> runWithMutantStatus(projectDir, outputDir, blockGenDir, mutant, tests)));
        }

        for (Future<Integer> future : futures) {
            try {
                int result = future.get();
                if (result > 0) {
                    numKilledMutants += 1;
                } else if (result < 0) {
                    System.err.println("MutantRunner: mutant execution failed or was skipped; not counting as killed.");
                }
            } catch (Exception e) {
                System.err.println("MutantRunner: failed to retrieve mutant execution result.");
                e.printStackTrace();
            }
        }

        executor.shutdown();
        return numKilledMutants;
    }

    private boolean insertTests(Mutant mutant, List<String> tests) {
        try {
            List<String> fileLines = new ArrayList<>(Files.readAllLines(Paths.get(mutant.getMutantFilePath())));
            int startIdx = -1, endIdx = -1;
            for (int i = 0; i < fileLines.size(); i++) {
                String trimmed = fileLines.get(i).trim();
                if (trimmed.startsWith("System.out.println(\"BLOCKGEN_FRAGMENT_STARTS\")")) {
                    startIdx = i;
                } else if (trimmed.startsWith("System.out.println(\"BLOCKGEN_FRAGMENT_END\")")) {
                    endIdx = i;
                }
            }

            if (startIdx != -1 && endIdx != -1) {
                List<String> startInsertions = new ArrayList<>();
                List<String> endInsertions = new ArrayList<>();

                for (String test : tests) {
                    String[] parts = test.split(";", -1);
                    // Rejoin from index 2 to second-to-last (last element is empty from the trailing ";")
                    String testCode = String.join(";", Arrays.copyOfRange(parts, 2, parts.length - 1)) + ";";
                    startInsertions.add(testCode);
                    if (!testCode.contains(".end(")) {
                        String testName = parts[2].split("\"")[1];
                        endInsertions.add("blocktest(\"" + testName + "\").end();");
                    }
                }

                // Remove end marker and replace with end insertions (process end first — higher index, won't shift startIdx)
                fileLines.remove(endIdx);
                fileLines.addAll(endIdx, endInsertions);

                // Remove start marker and replace with start insertions
                fileLines.remove(startIdx);
                fileLines.addAll(startIdx, startInsertions);

                System.out.println("Inserting to " + mutant.getMutantFilePath());
                Files.write(Paths.get(mutant.getMutantFilePath()), fileLines);
                return true;
            } else {
                System.out.println("StartIdx is " + startIdx + " and endIdx is " + endIdx + " for mutant " + mutant.getSerial() + ". Failed to find block test fragment markers in the original file. No insertion of block tests will be done for this mutant.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean replaceOriginalWithMutant(Mutant mutant) {
        String fragmentID = System.getenv("FRAGMENT_ID");
        try {
            List<String> fileLines = new ArrayList<>(Files.readAllLines(Paths.get(mutant.getMutantFilePath())));
            List<String> mutatedFragment = new ArrayList<>();
            boolean started = false;
            for (int i = 0; i < fileLines.size(); i++) {
                String trimmed = fileLines.get(i).trim();
                if (trimmed.startsWith("System.out.println(\"BLOCKGEN_FRAGMENT_STARTS\")")) {
                    started = true;
                } else if (trimmed.startsWith("System.out.println(\"BLOCKGEN_FRAGMENT_END\")")) {
                    break;
                } else if (started) {
                    mutatedFragment.add(fileLines.get(i));
                }
            }

            List<String> manualFileLines = new ArrayList<>(Files.readAllLines(Paths.get(mutant.getOriginalFilePath())));
            int startIdx = -1, endIdx = -1;
            for (int i = 0; i < manualFileLines.size(); i++) {
                String trimmed = manualFileLines.get(i).trim();
                if (trimmed.contains("BLOCKGEN START") && trimmed.contains(fragmentID)) {
                    startIdx = i;
                } else if (trimmed.contains("BLOCKGEN END") && trimmed.contains(fragmentID)) {
                    endIdx = i;
                }
            }

            if (startIdx != -1 && endIdx != -1 && startIdx < endIdx && !mutatedFragment.isEmpty()) {
                // Remove from startIdx to endIdx inclusive, then insert mutatedFragment
                manualFileLines.subList(startIdx, endIdx + 1).clear();
                manualFileLines.addAll(startIdx, mutatedFragment);
                manualFileLines.removeIf(line -> line.contains("BLOCKGEN START") || line.contains("BLOCKGEN END"));
            } else {
                System.out.println("StartIdx is " + startIdx + " and endIdx is " + endIdx + " for mutant " + mutant.getSerial() + " with length " + mutatedFragment.size() + ". Failed to find block test fragment markers in the original file. No insertion of block tests will be done for this mutant.");
            }

            System.out.println("Inserting to " + mutant.getMutantFilePath());
            Files.write(Paths.get(mutant.getMutantFilePath()), manualFileLines);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    // Goal: check if mutant is killed or not
    private int runWithMutantStatus(String projectDir, String outputDir, String blockGenDir, Mutant mutant, List<String> tests) {
        String newProjectDir = Utils.getTemporaryDir(mutant.getSerial());

        System.out.println("Running mutant " + mutant.getSerial() + " at " + newProjectDir);

        if (!MutationScore.manuallyWritten) {
            if (!insertTests(mutant, tests)) {
                System.out.println("Unable to insert tests to mutant " + mutant.getSerial());
                return -1;
            }
        } else {
            if (!replaceOriginalWithMutant(mutant)) {
                System.out.println("Unable to insert tests to mutant " + mutant.getSerial());
                return -1;
            }
        }

        try {
            Utils.copyDirectory(Paths.get(projectDir), Paths.get(newProjectDir));
        } catch (Exception ex) {
            return -1;
        }

        String relativePath = Paths.get(projectDir).relativize(Paths.get(mutant.getOriginalFilePath())).toString();
        String newOriginalFilePath = Paths.get(newProjectDir, relativePath).toString();

        Utils.replaceFile(newOriginalFilePath, mutant.getMutantFilePath());
        int status = TestRunner.runBlockTest(newProjectDir, outputDir, blockGenDir, "run-mutant-" + mutant.getSerial() + ".log", newOriginalFilePath, mutant.getSerial());
        System.out.println("Status for mutant " + mutant.getSerial() + " is " + status);

        if (status != 0) {
            return 1;
        }
        return 0;
    }

    // Goal: map block test to killed mutants
    private int runWithMutant(String projectDir, String outputDir, String blockGenDir, Mutant mutant, List<String> tests) {
        String newProjectDir = Utils.getTemporaryDir(mutant.getSerial());

        System.out.println("Running mutant " + mutant.getSerial() + " at " + newProjectDir);

        if (!insertTests(mutant, tests)) {
            System.out.println("Unable to insert tests to mutant " + mutant.getSerial());
            return -1;
        }

        try {
            Utils.copyDirectory(Paths.get(projectDir), Paths.get(newProjectDir));
        } catch (Exception ex) {
            return -1;
        }

        String relativePath = Paths.get(projectDir).relativize(Paths.get(mutant.getOriginalFilePath())).toString();
        String newOriginalFilePath = Paths.get(newProjectDir, relativePath).toString();

        Utils.replaceFile(newOriginalFilePath, mutant.getMutantFilePath());
        int status = TestRunner.runBlockTest(newProjectDir, outputDir, blockGenDir, "run-mutant-" + mutant.getSerial() + ".log", newOriginalFilePath, mutant.getSerial());
        System.out.println("Status for mutant " + mutant.getSerial() + " is " + status);

        if (this.resultOnly) {
            Utils.deleteDirectory(Paths.get(newProjectDir));
            return status;
        }

        File directory = new File(outputDir + File.separator + "tests-reports" + File.separator + "mutant-" + mutant.getSerial() + File.separator + "tmp");
        if (!directory.exists() || !directory.isDirectory()) {
            // Ignore mutant (does not compile)
            Utils.deleteDirectory(Paths.get(newProjectDir));
            return -1;
        }

        // Process test result
        Arrays.stream(new File(outputDir + File.separator + "tests-reports" + File.separator + "mutant-" + mutant.getSerial() + File.separator + "tmp").listFiles())
                .filter(file -> file.getName().endsWith(".xml")).forEach(file ->{
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
        Utils.deleteDirectory(Paths.get(newProjectDir));
        return status;
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
            aggregationMap.computeIfAbsent(blockTestID, key -> ConcurrentHashMap.newKeySet()).add(mutantId);
            if (r1Tests.contains(blockTestID))
                mutantToR1Test.computeIfAbsent(mutantId, key -> ConcurrentHashMap.newKeySet()).add(blockTestID);
            else
                mutantToR0Test.computeIfAbsent(mutantId, key -> ConcurrentHashMap.newKeySet()).add(blockTestID);
        } else {
            aggregationMap.computeIfAbsent(blockTestID, key -> ConcurrentHashMap.newKeySet());
        }
    }
}
