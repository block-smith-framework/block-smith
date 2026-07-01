package org.blockgen.utils;

import org.blockgen.Utils;
import org.blockgen.mutation.Mutant;
import org.blockgen.mutation.Mutator;
import org.blockgen.mutation.TestRunner;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class CoverageScore {

    private final Set<String> r2Tests = new HashSet<>();
    private final Map<Integer, String> sourceFile = new HashMap<>();
    private final Map<Integer, Set<IMethodCoverage>> sizeToCoverageData = new HashMap<>();
    private final Map<Integer, String> coverageStats = new HashMap<>();
    private final Map<Integer, String> branchCoverageStats = new HashMap<>();


    public void calculate(String projectDir, String absPathToSrc, String outputDir, String blockGenDir, String injectedSrc, int startLine, int endLine, String r2TestPath) throws Exception {
        try {
            for (String test : Files.readAllLines(Paths.get(r2TestPath))) {
                String testName = test.split(";")[2].split("\"")[1];
                r2Tests.add(testName);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("Failed to read test file r0.");
            System.exit(1);
        }

        Path jacocoPath = Paths.get(projectDir + File.separator + "target" + File.separator + "jacoco.exec");


        if (!jacocoPath.toFile().exists()) {
            new File(outputDir).getParentFile().mkdirs();
            new File(outputDir).mkdirs();
            new File(outputDir + File.separator + "tests-reports").mkdirs();
            new File(outputDir + File.separator + "logs").mkdirs();
            Utils.replaceFile(outputDir + File.separator + "backup.java", absPathToSrc);
            Utils.replaceFile(absPathToSrc, injectedSrc);

            Path pomPath = Paths.get(projectDir, "pom.xml");
            String originalPom = new String(Files.readAllBytes(pomPath));
            Files.write(pomPath, originalPom.replace("@{argLine} ", "").getBytes());

            int removedTests = FailingTestRemover.removeFailingTests(r2Tests, projectDir, outputDir, blockGenDir, absPathToSrc);

            Files.write(pomPath, originalPom.getBytes());

            TestRunner.runBlockTestWithCoverage(projectDir, outputDir, blockGenDir, "run-coverage.log", absPathToSrc, -1);
            if (!jacocoPath.toFile().exists()) {
                System.out.println("Failed to run JaCoCo");
                return;
            }
        }

        checkCoverage(jacocoPath.toFile(), projectDir, absPathToSrc, outputDir, blockGenDir);
    }

    public void checkCoverage(File jacocoFile, String projectDir, String absPathToSrc, String outputDir, String blockGenDir)  {
        ExecFileLoader execFileLoader = new ExecFileLoader();

        try {
            execFileLoader.load(jacocoFile);
        } catch (Exception ce) {
            ce.printStackTrace();
            return;
        }


        Path base = Paths.get(projectDir);
        Path target = Paths.get(absPathToSrc);
        Path relpathToSrc = base.relativize(target);
        String className = relpathToSrc.toString().replace(Utils.getSourcePath() + File.separator, "").replace(".java", "").replace(File.separatorChar, '.');
        String classesDir = Utils.getClassesDirectory(projectDir, outputDir, blockGenDir, false);

        try (BufferedReader br = new BufferedReader(new FileReader(absPathToSrc))) {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                // process the line.
                i++;
                sourceFile.put(i, line);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return;
        }

        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(execFileLoader.getExecutionDataStore(), coverageBuilder);
        try {
            analyzer.analyzeAll(new File(classesDir));
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }

        File bytecodeFile = Paths.get(classesDir + File.separator + className.replace('.', File.separatorChar) + ".class").toFile();

        Map<Integer, String> branchCoverage = new HashMap<>();
        IClassCoverage targetClass = null;
        for (IClassCoverage icc : coverageBuilder.getClasses()) {
//            System.out.println("Class: " + icc.getName().replace('/', '.'));
            if (icc.getName().replace('/', '.').equals(className)) {
                targetClass = icc;
                break;
            }
        }
        if (targetClass != null) {
            try {
                CoverageUtils.analyze(targetClass, bytecodeFile, branchCoverage);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            for (IMethodCoverage coverage : targetClass.getMethods()) {
                if (coverage.getName().startsWith("src_AUTO_GEN")) {
                    int methodSize = coverage.getLastLine() - coverage.getFirstLine() + 1;
                    if (sourceFile.getOrDefault(coverage.getLastLine(), "").trim().equals("}")) {
                        methodSize -= 1; // if the last line is just a closing bracket, we don't count it as part of the method
                    }

                    System.out.println("Matched method: " + coverage.getName() + " with size " + methodSize);

                    if (!sizeToCoverageData.containsKey(methodSize)) {
                        sizeToCoverageData.put(methodSize, new HashSet<>());
                    }
                    sizeToCoverageData.get(methodSize).add(coverage);
                }
            }
        } else {
            boolean matched = false;
            for (IClassCoverage targetClass2 : coverageBuilder.getClasses()) {
                for (IMethodCoverage coverage : targetClass2.getMethods()) {
                    if (coverage.getName().startsWith("src_AUTO_GEN")) {
                        matched = true;
                        int methodSize = coverage.getLastLine() - coverage.getFirstLine() + 1;
                        if (sourceFile.getOrDefault(coverage.getLastLine(), "").trim().equals("}")) {
                            methodSize -= 1; // if the last line is just a closing bracket, we don't count it as part of the method
                        }

                        if (!sizeToCoverageData.containsKey(methodSize)) {
                            sizeToCoverageData.put(methodSize, new HashSet<>());
                        }
                        sizeToCoverageData.get(methodSize).add(coverage);
                    }
                }
                if (matched) {
                    try {
                        CoverageUtils.analyze(targetClass2, bytecodeFile, branchCoverage);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    break;
                }
            }
        }

        if (sizeToCoverageData.keySet().size() == 1) {
            for (Map.Entry<Integer, Set<IMethodCoverage>> entry : sizeToCoverageData.entrySet()) {
                int methodSize = entry.getKey();
                for (int i = 0; i < methodSize; i++) {
                    coverageStats.put(i, "EMPTY"); // initialize coverage stats for each line in the method
                    branchCoverageStats.put(i, "EMPTY");
                }

                System.out.println("Method size: " + methodSize);
                boolean printSrc = false;
                for (IMethodCoverage coverage : entry.getValue()) {
                    // test method coverage
                    int j = 0;
                    int lastLine = coverage.getLastLine();
                    if (sourceFile.getOrDefault(lastLine, "").trim().equals("}")) {
                        lastLine -= 1; // if the last line is just a closing bracket, we don't count it as part of the method
                    }
                    for (int i = coverage.getFirstLine(); i <= lastLine; i++) {
                        // Branch coverage
                        if (branchCoverage.containsKey(i)) {
                            // is a branch
                            String branchStatus = branchCoverage.get(i);
                            if (branchStatus.equals("ALL_MISSED") && branchCoverageStats.get(j).equals("EMPTY")) {
//                                System.out.println("Branch at line " + j + " is not covered");
                                branchCoverageStats.put(j, "NOT_COVERED");
                            } else if (branchStatus.equals("TRUE_COVERED") && !branchCoverageStats.get(j).equals("FULLY_COVERED")) {
                                if (branchCoverageStats.get(j).equals("FALSE_COVERED")) {
                                    branchCoverageStats.put(j, "FULLY_COVERED");
                                } else {
                                    branchCoverageStats.put(j, "TRUE_COVERED");
                                }
                            } else if (branchStatus.equals("FALSE_COVERED") && !branchCoverageStats.get(j).equals("FULLY_COVERED")) {
                                if (branchCoverageStats.get(j).equals("TRUE_COVERED")) {
                                    branchCoverageStats.put(j, "FULLY_COVERED");
                                } else {
                                    branchCoverageStats.put(j, "FALSE_COVERED");
                                }
                            } else if (branchStatus.equals("FULLY_COVERED")) {
                                branchCoverageStats.put(j, "FULLY_COVERED");
                            }
                        }

                        String status = getStatus(coverage.getLine(i).getStatus());
                        if (status.equals("FULLY_COVERED")) {
                            // if the line is fully covered, mark it as fully covered
                            coverageStats.put(j, "FULLY_COVERED");
                        } else if (status.equals("PARTLY_COVERED") && !coverageStats.get(j).equals("FULLY_COVERED")) {
                            // if the line is partly covered, mark it as partly covered if it is not fully covered by another test
                            if (branchCoverageStats.get(j).equals("FULLY_COVERED")) {
                                // we have full branch coverage at this line, so it is fully coverd statement as well
                                coverageStats.put(j, "FULLY_COVERED");
                            } else {
                                coverageStats.put(j, "PARTLY_COVERED");
                            }
                        } else if (status.equals("NOT_COVERED") && coverageStats.get(j).equals("EMPTY")) {
                            // if the line is not covered, mark it as not covered if it is currently empty
                            coverageStats.put(j, "NOT_COVERED");
                        }
                        if (!printSrc) {
                            System.out.println(j + ": " + sourceFile.get(i));
                        }
                        j += 1;
                    }
                    printSrc = true;
                }
            }

            int totalLine = 0;
            int fullyCoveredLine = 0;
            int partlyCoveredLine = 0;
            int notCoveredLine = 0;
            System.out.println("=== Statement ===");
            for (int j = 0; j < coverageStats.size(); j++) {
                System.out.println(j + ": " + coverageStats.get(j));
                if (!coverageStats.get(j).equals("EMPTY") && !coverageStats.get(j).equals("UNKNOWN")) {
                    totalLine += 1;
                }

                if (coverageStats.get(j).equals("FULLY_COVERED")) {
                    fullyCoveredLine += 1;
                } else if (coverageStats.get(j).equals("PARTLY_COVERED")) {
                    partlyCoveredLine += 1;
                } else if (coverageStats.get(j).equals("NOT_COVERED")) {
                    notCoveredLine += 1;
                }
            }
            System.out.println("===");

            int totalBranch = 0;
            int fullyCoveredBranch = 0;
            int partlyCoveredBranch = 0;
            int notCoveredBranch = 0;
            System.out.println("=== Branch ===");
            for (int j = 0; j < branchCoverageStats.size(); j++) {
                System.out.println(j + ": " + branchCoverageStats.get(j));
                if (!branchCoverageStats.get(j).equals("EMPTY") && !branchCoverageStats.get(j).equals("UNKNOWN")) {
                    totalBranch += 1;
                }

                if (branchCoverageStats.get(j).equals("FULLY_COVERED")) {
                    fullyCoveredBranch += 1;
                } else if (branchCoverageStats.get(j).equals("TRUE_COVERED") || branchCoverageStats.get(j).equals("FALSE_COVERED")) {
                    partlyCoveredBranch += 1;
                } else if (branchCoverageStats.get(j).equals("NOT_COVERED")) {
                    notCoveredBranch += 1;
                }
            }
            System.out.println("===");

            System.out.println("OK. Total lines: " + totalLine + " - Fully covered lines: " + fullyCoveredLine + " - Partly covered lines: " + partlyCoveredLine + " - Not covered: " + notCoveredLine);
            System.out.println("OK. Total branches: " + totalBranch + " - Fully covered branches: " + fullyCoveredBranch + " - Partly covered branches: " + partlyCoveredBranch + " - Not covered: " + notCoveredBranch);
        } else {
            System.out.println(sizeToCoverageData);
            System.out.println("Failed. TODO: handle multiple method sizes. Currently only supports one method size.");
        }
    }

    private String getStatus(int status) {
        switch (status) {
            case ICounter.FULLY_COVERED:
                return "FULLY_COVERED";
            case ICounter.NOT_COVERED:
                return "NOT_COVERED";
            case ICounter.PARTLY_COVERED:
                return "PARTLY_COVERED";
            case ICounter.EMPTY:
                return "EMPTY";
            default:
                return "UNKNOWN";
        }
    }
}
