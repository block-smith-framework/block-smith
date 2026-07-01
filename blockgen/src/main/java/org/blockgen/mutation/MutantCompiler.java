package org.blockgen.mutation;

import org.blockgen.Constant;
import org.blockgen.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MutantCompiler {
    public static Set<Integer> compileMutants(String projectDir, String originalFilePath, String injectedSrc, String mutantsDir, String blockGenDir, String outputDir) {
        ExecutorService executor = Executors.newFixedThreadPool(Constant.THREAD_POOL_SIZE);
        List<Future<Integer>> futures = new ArrayList<>();

        Set<Integer> compilableMutants = new HashSet<>();
        File[] mutantFiles = new File(mutantsDir).listFiles();

        System.out.println("Compiling " + mutantFiles.length + " mutants using " + Constant.THREAD_POOL_SIZE + " threads...");
        for (File mutant : mutantFiles) {
            futures.add(executor.submit(() -> compileMutant(mutant, projectDir, originalFilePath, injectedSrc, mutantsDir, blockGenDir, outputDir)));
        }

        for (Future<Integer> future : futures) {
            try {
                int compilableMutantID = future.get();
                if (compilableMutantID >= 0) {
                    compilableMutants.add(compilableMutantID);
                }
            } catch (Exception ignored) {}
        }

        executor.shutdown();

        System.out.println("Compilable mutants: " + compilableMutants);
        return compilableMutants;
    }

    public static int compileMutant(File mutant, String projectDir, String originalFilePath, String injectedSrc, String mutantsDir, String blockGenDir, String outputDir) {
        int mutantId = Integer.parseInt(mutant.getName().split("\\.mutant\\.")[1].split("\\.")[0]);
        String newProjectDir = Utils.getTemporaryDir(mutantId);

        System.out.println("Compiling mutant #" + mutantId + " in " + newProjectDir);

        try {
            Utils.copyDirectory(Paths.get(projectDir), Paths.get(newProjectDir));
        } catch (Exception ex) {
            return -1;
        }

        String relativePath = Paths.get(projectDir).relativize(Paths.get(originalFilePath)).toString();
        String newOriginalFilePath = Paths.get(newProjectDir, relativePath).toString();

        try {
            // Copy mutated code to the original file path in the new project directory
            Files.copy(Paths.get(mutant.getAbsolutePath()), Paths.get(newOriginalFilePath),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Utils.deleteDirectory(Paths.get(newProjectDir));
            return -1;
        }

        if (Utils.compileSingleSource(newProjectDir, outputDir, blockGenDir, newOriginalFilePath, mutantId) != 0) {
            System.out.println("Failed to compile mutant: " + mutant.getName()
                    + ", will be removed from mutant set.");
            try {
                Files.delete(Paths.get(mutant.getAbsolutePath()));
                Files.move(Paths.get(newOriginalFilePath), Paths.get(outputDir + File.separator +  "failed-mutants" + File.separator + "failed-" + mutantId + ".java"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                System.out.println("Failed to remove mutant file " + mutant.getAbsolutePath() + ".");
                ex.printStackTrace();
            }

            // We will delete the tmp directory so no need to reset
            // Utils.replaceFile(newOriginalFilePath, injectedSrc);
            Utils.deleteDirectory(Paths.get(newProjectDir));
            return -1;
        }

        // Utils.replaceFile(newOriginalFilePath, injectedSrc);
        Utils.deleteDirectory(Paths.get(newProjectDir));
        return mutantId;
    }
}
