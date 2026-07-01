package org.blockgen;

import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.List;

public class BlockTest {
    public String testName = null;
    public int lineNo;
    public String targetStmtLineNo;
    public List<String> givens;
    public List<String> assertions;
    public List<String> mocking;
    public String expect;
    public String end;
    public String srcPath; // source file path that inline test is in
    public String clazzName; // class that inline test is in

    public BlockTest() {
        this.givens = new ArrayList<String>();
        this.assertions = new ArrayList<String>();
        this.mocking = new ArrayList<String>();
        this.expect = "";
        this.end = "";
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (this.testName == null || this.testName.isEmpty()) {
            sb.append(Constant.DECLARE_NAME + "()");
        } else {
            sb.append(Constant.DECLARE_NAME + "(\"" + this.testName + "\")");
        }
        for (String given : givens) {
            sb.append(".");
            sb.append(given);
        }
        for (String mock : mocking) {
            sb.append(".");
            sb.append(mock);
        }
        for (String assertion : assertions) {
            sb.append(".");
            sb.append(assertion);
        }
        if (!expect.isEmpty()) {
            sb.append(".");
            sb.append(expect);
        }
        if (!end.isEmpty()) {
            sb.append(".");
            sb.append(end);
        }
        sb.append(";");
        return sb.toString();
    }

    public int hashCode() {
        return this.srcPath.hashCode() + this.lineNo + this.toString().hashCode();
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof BlockTest)) {
            return false;
        }
        BlockTest other = (BlockTest) o;
        return this.srcPath.equals(other.srcPath) && this.lineNo == other.lineNo
                && this.toString().equals(other.toString());
    }
}
