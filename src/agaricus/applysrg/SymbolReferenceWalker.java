package agaricus.applysrg;

import com.intellij.psi.*;

import java.util.HashMap;

/**
 * Recursively descends and processes symbol references
 */
public class SymbolReferenceWalker {
    // Where to write results to
    private SymbolRangeEmitter emitter;

    // Where we're at
    private String className;
    private String methodName = "(outside-method)";
    private String methodSignature = "";

    // If currently within // xxx start // xxx end comments
    private boolean withinAddedCode = false;

    /**
     * Variables in the code block, mapped to the order they were declared.
     * This includes PsiLocalVariable from PsiDeclarationStatement, and also
     * PsiParameter from PsiForeachStatement/PsiCatchSection. Both are PsiVariable.
     */
    private HashMap<PsiVariable, Integer> localVariableIndices = new HashMap<PsiVariable, Integer>();
    private int nextLocalVariableIndex = 0;

    // Separate index for variable declarations in "added" code
    private int nextAddedLocalVariableIndex = 100;

    /**
     * Parameters to method, mapped to order in method declaration.
     * Set by caller
     * @see #addMethodParameterIndices
     */
    private HashMap<PsiParameter, Integer> methodParameterIndices = new HashMap<PsiParameter, Integer>();

    public SymbolReferenceWalker(SymbolRangeEmitter emitter, String className) {
        this.emitter = emitter;
        this.className = className;
    }

    public SymbolReferenceWalker(SymbolRangeEmitter emitter, String className, String methodName, String methodSignature) {
        this(emitter, className);
        this.methodName = methodName;
        this.methodSignature = methodSignature;
    }

    /**
     * Recursively walk starting from given element
     * @param startElement
     * @return true if successful, or false if failed due to unresolved symbols
     */
    public boolean walk(PsiElement startElement) {
        return walk(startElement, 0);
    }

    /**
     * Add map used for labeling method parameters by index
     * @param methodParameterIndices
     */
    public void addMethodParameterIndices(HashMap<PsiParameter, Integer> methodParameterIndices) {
        this.methodParameterIndices.putAll(methodParameterIndices);
    }

    /**
     * Record the positional index of a local variable declaration
     *
     * @param psiVariable The newly-declared variable
     * @return The new index, unique per method
     */
    private int assignLocalVariableIndex(PsiVariable psiVariable) {
        int index = withinAddedCode ? nextAddedLocalVariableIndex : nextLocalVariableIndex;

        localVariableIndices.put(psiVariable, index);

        // Variables in "added" code are tracked with a separate index, so they don't shift variables
        // indexes below the added code
        if (withinAddedCode) {
            nextAddedLocalVariableIndex++;
        } else {
            nextLocalVariableIndex++;
        }

        return index;
    }

    private boolean walk(PsiElement psiElement, int depth) {
        //emitter.log("walking "+className+" "+psiMethod.getName()+" -- "+psiElement);

        if (psiElement == null) {
            return true; // gracefully ignore
        }

        // Comment possibly telling us this is added code, to track local variables differently
        if (psiElement instanceof PsiComment) {
            PsiComment psiComment = (PsiComment)psiElement;
            if (psiComment.getTokenType() == JavaTokenType.END_OF_LINE_COMMENT) { // "//" style comments
                String commentText = psiComment.getText();
                //emitter.log("COMMENT:"+commentText);
                String[] words = commentText.split(" ");

                if (words.length >= 3) {
                    // First word is "//", second is "CraftBukkit", "Spigot", "Forge".., third is "start"/"end"
                    String command = words[2];
                    if (command.equalsIgnoreCase("start")) {
                        withinAddedCode = true;
                    } else if (command.equalsIgnoreCase("end")) {
                        withinAddedCode = false;
                    }
                }
            }
        }

        // New local variable declaration
        if (psiElement instanceof PsiDeclarationStatement) {
            PsiDeclarationStatement psiDeclarationStatement = (PsiDeclarationStatement)psiElement;

            for (PsiElement declaredElement : psiDeclarationStatement.getDeclaredElements()) {
                if (declaredElement instanceof PsiClass) {
                    emitter.log("TODO: inner class "+declaredElement); // TODO: process this?
                } else if (declaredElement instanceof PsiLocalVariable) {
                    PsiLocalVariable psiLocalVariable = (PsiLocalVariable)declaredElement;

                    emitter.emitTypeRange(psiLocalVariable.getTypeElement());

                    // Record order of variable declarations for references in body
                    int index = assignLocalVariableIndex(psiLocalVariable);

                    emitter.emitLocalVariableRange(className, methodName, methodSignature, psiLocalVariable, index);
                } else {
                    emitter.log("WARNING: Unknown declaration "+psiDeclarationStatement);
                }
            }
        }

        // New local variable declaration within try..catch
        if (psiElement instanceof PsiCatchSection) {
            PsiCatchSection psiCatchSection = (PsiCatchSection)psiElement;
            PsiParameter psiParameter = psiCatchSection.getParameter();
            int index = assignLocalVariableIndex(psiParameter);
            emitter.emitLocalVariableRange(className, methodName, methodSignature, psiParameter, index);
        }

        // .. and foreach
        if (psiElement instanceof PsiForeachStatement) {
            PsiForeachStatement psiForeachStatement = (PsiForeachStatement)psiElement;
            PsiParameter psiParameter = psiForeachStatement.getIterationParameter();
            int index = assignLocalVariableIndex(psiParameter);
            emitter.emitLocalVariableRange(className, methodName, methodSignature, psiParameter, index);
        }

        // Variable reference
        if (psiElement instanceof PsiJavaCodeReferenceElement) {
            PsiJavaCodeReferenceElement psiJavaCodeReferenceElement = (PsiJavaCodeReferenceElement)psiElement;

            // Identifier token naming this reference without qualification
            PsiElement nameElement = psiJavaCodeReferenceElement.getReferenceNameElement();

            // What this reference expression actually refers to
            PsiElement referentElement = psiJavaCodeReferenceElement.resolve();

            if (referentElement == null) {
                // Element references something that doesn't exist! This shows in red in the IDE, as unresolved symbols.
                // Fail hard
                emitter.log("FAILURE: unresolved symbol: null referent "+referentElement+" in "+className+" "+methodName+","+methodSignature);
                return false;
            } else if (referentElement instanceof PsiPackage) {
                // Not logging package since includes net, net.minecraft, net.minecraft.server.. all components
                // TODO: log reference for rename
                //emitter.log("PKGREF"+referentElement+" name="+nameElement);
            } else if (referentElement instanceof PsiClass) {
                emitter.emitReferencedClass(nameElement, (PsiClass)referentElement);
                //TODO emitter.emitTypeQualifierRangeIfQualified((psiJavaCodeReferenceElement));
            } else if (referentElement instanceof PsiField) {
                emitter.emitReferencedField(nameElement, (PsiField)referentElement);
                //TODO emitter.emitTypeQualifierRangeIfQualified((psiJavaCodeReferenceElement));
            } else if (referentElement instanceof PsiMethod) {
                emitter.emitReferencedMethod(nameElement, (PsiMethod)referentElement);
                //TODO emitter.emitTypeQualifierRangeIfQualified((psiJavaCodeReferenceElement));
            } else if (referentElement instanceof PsiLocalVariable) {
                PsiLocalVariable psiLocalVariable = (PsiLocalVariable)referentElement;

                // Index of local variable as declared in method
                int index;
                if (!localVariableIndices.containsKey(psiLocalVariable))  {
                    index = -1;
                    emitter.log("couldn't find local variable index for "+psiLocalVariable+" in "+localVariableIndices);
                } else {
                    index = localVariableIndices.get(psiLocalVariable);
                }

                emitter.emitReferencedLocalVariable(nameElement, className, methodName, methodSignature, psiLocalVariable, index);
            } else if (referentElement instanceof PsiParameter) {
                PsiParameter psiParameter = (PsiParameter)referentElement;

                PsiElement declarationScope = psiParameter.getDeclarationScope();

                if (declarationScope instanceof PsiMethod) {
                    // Method parameter

                    int index;
                    if (!methodParameterIndices.containsKey(psiParameter)) {
                        index = -1;
                        // TODO: properly handle parameters in inner classes.. currently we always look at the outer method,
                        // but there could be parameters in a method in an inner class. This currently causes four errors in CB,
                        // CraftTask and CraftScheduler, since it makes heavy use of anonymous inner classes.
                        emitter.log("WARNING: couldn't find method parameter index for "+psiParameter+" in "+methodParameterIndices);
                    } else {
                        index = methodParameterIndices.get(psiParameter);
                    }

                    emitter.emitReferencedMethodParameter(nameElement, className, methodName, methodSignature, psiParameter, index);
                } else if (declarationScope instanceof PsiForeachStatement || declarationScope instanceof PsiCatchSection) {
                    // New variable declared with for(type var:...) and try{}catch(type var){}
                    // For some reason, PSI calls these "parameters", but they're more like local variable declarations
                    // Treat them as such

                    int index;
                    if (!localVariableIndices.containsKey(psiParameter)) {
                        index = -1;
                        emitter.log("WARNING: couldn't find non-method parameter index for "+psiParameter+" in "+localVariableIndices);
                    } else {
                        index = localVariableIndices.get(psiParameter);
                    }
                    emitter.emitTypeRange(psiParameter.getTypeElement());
                    emitter.emitReferencedLocalVariable(nameElement, className, methodName, methodSignature, psiParameter, index);
                } else {
                    emitter.log("WARNING: parameter "+psiParameter+" in unknown declaration scope "+declarationScope);
                }
            } else {
                emitter.log("WARNING: ignoring unknown referent "+referentElement+" in "+className+" "+methodName+","+methodSignature);
            }

            /*
            emitter.log("   ref "+psiReferenceExpression+
                    " nameElement="+nameElement+
                    " name="+psiReferenceExpression.getReferenceName()+
                    " resolve="+psiReferenceExpression.resolve()+
                    " text="+psiReferenceExpression.getText()+
                    " qualifiedName="+psiReferenceExpression.getQualifiedName()+
                    " qualifierExpr="+psiReferenceExpression.getQualifierExpression()
                );
                */
        }

        PsiElement[] children = psiElement.getChildren();
        if (children != null) {
            for (PsiElement child: children) {
                if (!walk(child, depth + 1)) {
                    return false; // fail
                }
            }
        }
        return true;
    }
}
