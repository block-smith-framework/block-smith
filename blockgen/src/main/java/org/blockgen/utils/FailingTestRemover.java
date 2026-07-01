package org.blockgen.utils;

import org.blockgen.mutation.MutationScore;
import org.blockgen.mutation.TestRunner;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FailingTestRemover {
    public static int removeFailingTests(Set<String> r2Tests, String projectDir, String outputDir, String blockGenDir, String absPathToSrc) {
        System.out.println("===== Removing failing tests =====");
        // TODO: update BDK to add the ability to generate test per class, so we can identify which test fails to compile

        int status = TestRunner.runBlockTest(projectDir, outputDir, blockGenDir, "run-all.log", absPathToSrc, -1);
        System.out.println("Status for r0 is " + status);
        if (MutationScore.manuallyWritten) {
            return status;
        }

        Set<String> passingTests = new HashSet<>();
        // Process test result
        Arrays.stream(new File(outputDir + File.separator + "tests-reports" + File.separator + "all" + File.separator + "tmp").listFiles())
                .filter(file -> file.getName().endsWith(".xml") && file.getName().startsWith("TEST-")).forEach(file ->{
                    try {
                        JSONObject root = XML.toJSONObject(new String(Files.readAllBytes(file.getAbsoluteFile().toPath())));
                        JSONObject testsuite = root.getJSONObject("testsuite");
                        try {
                            JSONArray testcases = testsuite.getJSONArray("testcase");
                            for (int i = 0; i < testcases.length(); i++) {
                                addPassingTests(r2Tests, testcases.getJSONObject(i), passingTests);
                            }
                        } catch (JSONException ex) {
                            try {
                                JSONObject testcase = testsuite.getJSONObject("testcase");
                                addPassingTests(r2Tests, testcase, passingTests);
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

        Set<String> removedTests = new HashSet<>(r2Tests); // All r2 - passing = failing
        removedTests.removeAll(passingTests);

        System.out.println("There are " + removedTests.size() + " failing tests in r2: " + removedTests);

        if (removedTests.isEmpty())
            return 0;

        // Use removedTestsWithQuote to avoid partial match. For example "AUTO_GEN_11" should not match "AUTO_GEN_1"
        Set<String> removedTestsWithQuote = new HashSet<>();
        for (String removedTest : removedTests) {
            removedTestsWithQuote.add("\"" + removedTest + "\"");
            r2Tests.removeIf(test -> test.equals(removedTest));
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

    // Return if a test is failing or passing
    private static void addPassingTests(Set<String> r2Tests, JSONObject testcase, Set<String> passingTests) {
        String blockTestID = testcase.optString("name");
        if (!r2Tests.contains(blockTestID)) {
            // Not a block test, ignore
            return;
        }
        if (!testcase.has("error") && !testcase.has("failure")) {
            passingTests.add(blockTestID);
        }
    }
}
