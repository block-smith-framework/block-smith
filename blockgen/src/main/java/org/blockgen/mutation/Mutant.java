package org.blockgen.mutation;

public class Mutant {
    /** An integer identifier for the mutant based on a given file. */
    private final int serial;

    /** Relative file path for the original program from Maven's project root. */
    private final String originalFilePath;

    /** File path of the mutated program. */
    private final String mutantFilePath;

    /** Original content of the target statement. */
    private final String originalContent;

    /** Mutated content of the target statement. */
    private final String mutatedContent;

    public Mutant(int serial, String originalFilePath, String mutantFilePath, String originalContent,
                  String mutatedContent) {
        this.serial = serial;
        this.originalFilePath = originalFilePath;
        this.mutantFilePath = mutantFilePath;
        this.originalContent = originalContent;
        this.mutatedContent = mutatedContent;
    }

    @Override
    public String toString() {
        return "{serial: " + serial + ", original filepath: " + originalFilePath + ", mutant filepath: " + mutantFilePath
                + ", original content: " + originalContent + ", mutated content: "
                + mutatedContent + "}";}

    public int getSerial() {
        return serial;
    }

    public String getOriginalFilePath() {
        return originalFilePath;
    }

    public String getMutantFilePath() {
        return mutantFilePath;
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public String getMutatedContent() {
        return mutatedContent;
    }
}