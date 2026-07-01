package org.blockgen.generation;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GenerationUtils {

    public static String findMethodByLine(String file, int targetLine) throws IOException {
        InputStream is = Files.newInputStream(Paths.get(file));
        return findMethodByLine(is, targetLine);
    }

    public static String findMethodByLine(InputStream classStream, int targetLine) throws IOException {
        ClassReader reader = new ClassReader(classStream);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        for (MethodNode method : classNode.methods) {
            if (coversLine(method, targetLine)) {
                return method.name + method.desc;
            }
        }
        return null;
    }

    private static boolean coversLine(MethodNode method, int targetLine) {
        if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) return false;
        if (method.instructions == null) return false;

        int minLine = Integer.MAX_VALUE;
        int maxLine = Integer.MIN_VALUE;

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode) {
                LineNumberNode ln = (LineNumberNode) insn;
                minLine = Math.min(minLine, ln.line);
                maxLine = Math.max(maxLine, ln.line);
            }
        }

        if (minLine == Integer.MAX_VALUE) return false;
        return targetLine >= minLine && targetLine <= maxLine;
    }
}
