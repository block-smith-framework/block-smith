package org.blockgen.utils;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.internal.analysis.*;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.*;
import org.objectweb.asm.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class CoverageUtils {
    public static void analyze(IClassCoverage cc, File classFile, Map<Integer, String> branchCoverage) throws IOException {
        Set<Integer> realLines = scanRealBranchLines(classFile, cc.getName());

//        System.out.println("\n============================");
//        System.out.println("Class: " + cc.getName());
//        System.out.println("============================");

        for (IMethodCoverage mc : cc.getMethods()) {
//            boolean methodPrinted = false;

            for (int line = mc.getFirstLine(); line <= mc.getLastLine(); line++) {
                ILine lineData = mc.getLine(line);
                ICounter branches = lineData.getBranchCounter();

                if (branches.getTotalCount() == 0) {
//                    System.out.println("  Line " + line + ": no branches (skipping)");
                    continue;
                }
                if (!realLines.contains(line)) {
//                    System.out.println("  Line " + line + ": synthetic branch (skipping)");
                    continue;
                }
                if (branches.getMissedCount() == 0) {
//                    System.out.println("  Line " + line + ": ALL branches ✅ covered");
                    branchCoverage.put(line, "FULLY_COVERED");
                    continue;
                }

//                if (!methodPrinted) {
//                    System.out.println("\n  Method: " + mc.getName() + mc.getDesc());
//                    System.out.println("  " + "---");
//                    methodPrinted = true;
//                }

                int missed  = branches.getMissedCount();
                int covered = branches.getCoveredCount();
                int total   = branches.getTotalCount();

//                System.out.printf("  Line %d: %d/%d branches covered%n",
//                        line, covered, total);

                if (missed == total) {
//                    System.out.println("    → ALL branches ❌ MISSED (line never executed)");
//                    System.out.println("  Line " + line + ": missed: " + missed + "/" + total + " branches");
                    branchCoverage.put(line, "ALL_MISSED");
                } else {
                    int nextLine = findNextExecutableLine(mc, line);
                    if (nextLine == -1) {
                        System.out.println("    → Could not determine which branch is missed");
                        continue;
                    }

                    int nextStatus = mc.getLine(nextLine).getStatus();
                    if (nextStatus == ICounter.FULLY_COVERED
                            || nextStatus == ICounter.PARTLY_COVERED) {
                        branchCoverage.put(line, "TRUE_COVERED");

//                        System.out.println("    → TRUE  branch ✅ covered   (line " + nextLine + " was reached)");
//                        System.out.println("    → FALSE branch ❌ MISSED    (else/skip path never taken)");
                    } else {
                        branchCoverage.put(line, "FALSE_COVERED");

//                        System.out.println("    → TRUE  branch ❌ MISSED    (line " + nextLine + " never reached)");
//                        System.out.println("    → FALSE branch ✅ covered   (else/skip path was taken)");
                    }
                }
            }
        }
    }

    static Set<Integer> scanRealBranchLines(File classFile, String className)
            throws IOException {

        byte[] bytes = Files.readAllBytes(classFile.toPath());
        ClassReader cr = new ClassReader(bytes);
        Set<Integer> realLines = new HashSet<>();

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name,
                                             String desc, String signature, String[] exceptions) {

                return new MethodVisitor(Opcodes.ASM9) {

                    final Map<Label, Integer> labelLines = new HashMap<>();
                    final List<int[]>         jumps      = new ArrayList<>();
                    final List<Label>         jumpLabels = new ArrayList<>();
                    int currentLine = -1;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        currentLine = line;
                        labelLines.put(start, line);  // overwrite, don't use putIfAbsent
                    }

                    @Override
                    public void visitLabel(Label label) {
                        if (currentLine != -1) {
                            labelLines.putIfAbsent(label, currentLine);
                        }
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        if (opcode != Opcodes.GOTO && currentLine != -1) {
                            jumps.add(new int[]{currentLine});
                            jumpLabels.add(label);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        for (int i = 0; i < jumps.size(); i++) {
                            int     branchLine = jumps.get(i)[0];
                            Label   target     = jumpLabels.get(i);
                            Integer targetLine = labelLines.get(target);

                            if (targetLine == null || targetLine != branchLine) {
                                realLines.add(branchLine);
                            }
                        }
                    }
                };
            }
        }, 0);

        return realLines;
    }

    static int findNextExecutableLine(IMethodCoverage mc, int fromLine) {
        for (int line = fromLine + 1; line <= mc.getLastLine(); line++) {
            if (mc.getLine(line).getStatus() != ICounter.EMPTY) {
                return line;
            }
        }
        return -1;
    }
}
