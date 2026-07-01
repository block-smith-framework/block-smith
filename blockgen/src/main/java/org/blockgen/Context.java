package org.blockgen;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;

import java.util.*;

/**
 * This class is used to store the context of the visitor.
 */
public class Context implements Cloneable {
    public String className;
    public boolean canCreateThis = false;
    public String logPath;
    public String r0TestPath;
    public String r1TestPath;
    public int startLineNumber;
    public int endLineNumber;
    public String srcPath;
    public String classesDirectory;
    public Set<String> logVariablesBefore = new HashSet<>();
    public Set<String> declaredVariables = new HashSet<>();
    public Set<String> assignedVariables = new HashSet<>();
    public Set<String> unassignedVariables = new HashSet<>();
    public Set<String> logVariablesAfter = new HashSet<>();
    public Set<String> mockMethods = new HashSet<>();
    public Set<String> givenMethods = new HashSet<>();
    public Map<String, Set<String>> blockTests = new HashMap<>();
    public ArrayDeque<Set<String>> locals = new ArrayDeque();
    public boolean lineNumberKnown = false;
    public boolean isTargetStmt = false;
    public boolean isCondition = false;
    public boolean throwExceptionForMalformedInlineTest = false;
    public Set<String> uninitializedVariables = new HashSet<>();
    public boolean endWithReturn = true;

    public Set<String> declaredVariablesTopLevel = new HashSet<>();

    public Map<String, String> variableToAssignment = new HashMap<>();

    // Genie version
    public Set<Node> rename = new HashSet<>();
    public NodeList<ReferenceType> thrownExceptions = new NodeList<>();
    public NodeList<TypeParameter> genericTypes = new NodeList<>();
    public NodeList<TypeParameter> classGenericTypes = new NodeList<>();
    public Type returnType = null;

    public Map<String, String> logVariablesWithTypeBefore = new HashMap<>();
    public Map<String, String> logVariablesWithTypeAfter = new HashMap<>();

    public Map<String, String> declaredVariablesWithType = new HashMap<>();
    public Map<String, String> assignedVariablesWithType = new HashMap<>();
    public Map<String, String> unassignedVariablesWithType = new HashMap<>();
    public Map<String, String> uninitializedVariablesWithType = new HashMap<>();
    public Map<String, String> allVariablesWithType = new HashMap<>();
    public Map<String, String> invokedVariablesWithType = new HashMap<>();
    public Set<String> methods = new HashSet<>();

    public Set<String> staticVariables = new HashSet<>();

    public String message = "";

    public Context clone() {
        try {
            Context copy = (Context) super.clone();

            copy.canCreateThis = this.canCreateThis;

            // Sets<String>
            copy.logVariablesBefore        = new HashSet<>(this.logVariablesBefore);
            copy.declaredVariables         = new HashSet<>(this.declaredVariables);
            copy.assignedVariables         = new HashSet<>(this.assignedVariables);
            copy.unassignedVariables       = new HashSet<>(this.unassignedVariables);
            copy.logVariablesAfter         = new HashSet<>(this.logVariablesAfter);
            copy.mockMethods               = new HashSet<>(this.mockMethods);
            copy.givenMethods              = new HashSet<>(this.givenMethods);
            copy.uninitializedVariables    = new HashSet<>(this.uninitializedVariables);
            copy.declaredVariablesTopLevel = new HashSet<>(this.declaredVariablesTopLevel);
            copy.variableToAssignment      = new HashMap<>(this.variableToAssignment);
            copy.methods                   = new HashSet<>(this.methods);
            copy.staticVariables           = new HashSet<>(this.staticVariables);

            // Map<String, Set<String>> — deep copy each value Set
            copy.blockTests = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : this.blockTests.entrySet()) {
                copy.blockTests.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }

            // ArrayDeque<Set<String>> — deep copy each frame Set
            copy.locals = new ArrayDeque<>();
            for (Set<String> frame : this.locals) {
                copy.locals.addLast(new HashSet<>(frame));
            }

            // Maps<String, String>
            copy.logVariablesWithTypeBefore     = new HashMap<>(this.logVariablesWithTypeBefore);
            copy.logVariablesWithTypeAfter      = new HashMap<>(this.logVariablesWithTypeAfter);
            copy.declaredVariablesWithType      = new HashMap<>(this.declaredVariablesWithType);
            copy.assignedVariablesWithType      = new HashMap<>(this.assignedVariablesWithType);
            copy.unassignedVariablesWithType    = new HashMap<>(this.unassignedVariablesWithType);
            copy.uninitializedVariablesWithType = new HashMap<>(this.uninitializedVariablesWithType);
            copy.allVariablesWithType           = new HashMap<>(this.allVariablesWithType);
            copy.invokedVariablesWithType       = new HashMap<>(this.invokedVariablesWithType);

            // JavaParser AST nodes — shared by reference (no deep copy)
            copy.rename           = this.rename;
            copy.thrownExceptions = this.thrownExceptions;
            copy.genericTypes     = this.genericTypes;
            copy.classGenericTypes= this.classGenericTypes;
            copy.returnType       = this.returnType;

            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Context is Cloneable but clone failed", e);
        }
    }
}
