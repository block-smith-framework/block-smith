package org.blockgen;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.blockgen.helpers.StatementInserter;
import org.blockgen.helpers.VariableFinder;
import org.blockgen.strategies.Exli;
//import org.blockgen.visitors.LogLocalVariable;

public class Parser {

    /**
     * Add parsed block tests (from log file) to the Java source file
     *
     * @param testPath
     * @throws IOException
     */
    public static void addBlockTest(String testPath) throws IOException {
        FileReader reader = new FileReader(testPath);
        Map<String, Map<String, Set<String>>> blockTestMap = new HashMap<>();
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] tokens = line.split(";");
                if (tokens.length < 3) {
                    System.out.println("cannot be splitted" + line);
                    continue;
                }
                String srcPath = tokens[0];
                int startLineNumber;
                int endLineNumber;
                try {
                    startLineNumber = Integer.parseInt(tokens[1].split("-")[0]);
                    endLineNumber = Integer.parseInt(tokens[1].split("-")[1]);
                } catch (NumberFormatException e) {
                    System.out.println("cannot be parsed: " + line);
                    continue;
                }
                StringBuilder blockTestSB = new StringBuilder();
                for (int i = 2; i < tokens.length; i++) {
                    blockTestSB.append(tokens[i]);
                    blockTestSB.append(";");
                }
                String blockTestStr = blockTestSB.toString();
                if (!blockTestMap.containsKey(srcPath)) {
                    blockTestMap.put(srcPath, new HashMap<>());
                }
                String key = startLineNumber + "-" + endLineNumber;
                Map<String, Set<String>> lineMap = blockTestMap.get(srcPath);
                if (!lineMap.containsKey(key)) {
                    lineMap.put(key, new HashSet<>());
                }
                lineMap.get(key).add(blockTestStr);
            }
        }
        Context ctx = new Context();

        /*
        Add line end indicator as comment
         */
        boolean addComment = true;
        for (String srcPath : blockTestMap.keySet()) {
            Map<String, Set<String>> blockTests = blockTestMap.get(srcPath);
            for (Map.Entry<String, Set<String>> lineMap : blockTests.entrySet()) {
                String[] lineTokens = lineMap.getKey().split("-");
                int endLineNumber = Integer.parseInt(lineTokens[1]);
                Set<String> tests = lineMap.getValue();
                if (tests.isEmpty() || !tests.iterator().next().contains("." + Constant.END + "(")) { // match .end(FIRST_STATEMENT, 9999999)
                    System.out.println("No block test with .end() found. No need to modify source file to add block test end indicator as comment.");
                    addComment = false;
                    continue;
                }

                if (addComment) {
                    System.out.println("Block test with .end() found. Adding block test end indicator as comment at line " + endLineNumber + " in source file: " + srcPath);
                    List<String> lines = Files.readAllLines(Paths.get(srcPath));
                    lines.set(endLineNumber - 1, lines.get(endLineNumber - 1) + " //* BLOCKTEST_FRAGMENT_END_HERE");
                    Files.write(Paths.get(srcPath), lines);
                }
            }
        }

        for (String srcPath : blockTestMap.keySet()) {
            ctx.blockTests = blockTestMap.get(srcPath);

            constructBlockTestHelper(srcPath, ctx);
        }
    }

    public static void constructBlockTestHelper(String srcPath, Context ctx) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(Paths.get(srcPath));
        LexicalPreservingPrinter.setup(cu);

        StatementInserter inserter = new StatementInserter(cu);

        for (Map.Entry<String, Set<String>> lineMap : ctx.blockTests.entrySet()) {
            String[] lineTokens = lineMap.getKey().split("-");
            int startLineNumber = Integer.parseInt(lineTokens[0]);
            int endLineNumber = Integer.parseInt(lineTokens[1]);
            Set<String> blockTests = lineMap.getValue();

            for (String blockTestStr : blockTests) {
                try {
                    Statement blockTestStmt = StaticJavaParser.parseStatement(blockTestStr);
                    inserter.insertStatementAtLine(startLineNumber, blockTestStmt, true);
                } catch (Exception ex) {
                    System.out.println("Failed to parse block test statement: " + blockTestStr);
                    ex.printStackTrace();
                }

                if (blockTestStr.contains("." + Constant.END + "(")) { // match .end(FIRST_STATEMENT, 9999999)
                    System.out.println("Found .end() in block test statement, skipping adding .end() statement at the end of block test: " + blockTestStr);
                    continue;
                }

                try {
                    Statement blockTestEndStmt = StaticJavaParser.parseStatement(blockTestStr.substring(0, blockTestStr.indexOf(")") + 1) + "." + Constant.END + "();");
                    inserter.insertStatementAtLine(endLineNumber, blockTestEndStmt, false);
                } catch (Exception ex) {
                    System.out.println("Failed to parse block test end statement: " + blockTestStr);
                    ex.printStackTrace();
                }
            }
        }

        if (!ctx.blockTests.isEmpty()) {
            // check if there is import of BTest
            boolean importBTest = false;
            NodeList<ImportDeclaration> importDeclarations = cu.getImports();
            for (ImportDeclaration importDeclaration : importDeclarations) {
                if (importDeclaration.getNameAsString().equals("org.blocktest.BTest")) {
                    importBTest = true;
                    break;
                }
            }
            if (!importBTest) {
                cu.addImport("org.blocktest.BTest");
                cu.addImport("org.blocktest.BTest.blocktest", true, false);
                cu.addImport("org.blocktest.types.EndAt", true, true);
                cu.addImport("org.blocktest.utils.Constant", true, true);
            }
        }

        String output;
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("lpp-worker-" + t.getId());
            return t;
        });
        try {
            Future<String> f = ex.submit(() -> LexicalPreservingPrinter.print(cu.clone()));
            try {
                output = f.get(15, TimeUnit.SECONDS);
                System.out.println("LPP succeeded");
            } catch (TimeoutException e) {
                f.cancel(true);
                System.err.println("LPP hung, falling back to pretty printer.");
                output = cu.toString();
            }
        } catch (Exception ignored) {
            System.err.println("LPP exception, falling back to pretty printer.");
            output = cu.toString();
        } finally {
            ex.shutdownNow();
        }

        FileWriter writer;
        writer = new FileWriter(srcPath);
        writer.write(output);
        writer.close();
    }
}
