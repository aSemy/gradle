/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.toolchain.internal;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

/**
 * AST Transformation for `@GroovyName`.
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class GroovyNameAstTransformation extends AbstractASTTransformation {

    @Override
    public void visit(ASTNode[] nodes, SourceUnit sourceUnit) {
        System.out.println("GroovyNameAstTransformation.visit()");
        // The annotation node (0) and the annotated method node (1)
        if (nodes.length < 2 || !(nodes[0] instanceof AnnotationNode) || !(nodes[1] instanceof AnnotatedNode)) {
            System.out.println("GroovyNameAstTransformation.visit() - Unexpected structure");
            return; // Unexpected structure
        }

        AnnotationNode annotationNode = (AnnotationNode) nodes[0];
        AnnotatedNode annotatedNode = (AnnotatedNode) nodes[1];

        // Ensure the annotation is applied to a method
        if (!(annotatedNode instanceof MethodNode)) {
            addError("The @GroovyName annotation can only be applied to methods.", annotatedNode);
            return;
        }

        MethodNode originalMethod = (MethodNode) annotatedNode;
        ClassNode classNode = originalMethod.getDeclaringClass();

        // Extract the value of the `@GroovyName` annotation (the new name)
        String newName = getMemberStringValue(annotationNode, "value");
        if (newName == null) {
            addError("The @GroovyName annotation must specify a non-null name.", annotationNode);
            return;
        }

        // Duplicate the original method with the new name
        addDuplicatedMethod(classNode, originalMethod, newName);
    }

    /**
     * Duplicates the given method with the specified new name.
     *
     * @param classNode The class where the method is declared.
     * @param originalMethod The original method to duplicate.
     * @param newMethodName The new name for the duplicated method.
     */
    private void addDuplicatedMethod(ClassNode classNode, MethodNode originalMethod, String newMethodName) {
        if (classNode.getDeclaredMethod(newMethodName, originalMethod.getParameters()) != null) {
            // Avoid duplicates if the method already exists
            return;
        }

        // Build the method body (delegate call to the original method)
        BlockStatement methodBody = new BlockStatement();
        methodBody.addStatement(new ReturnStatement(
            new MethodCallExpression(
                new VariableExpression("this"),   // Call on `this`
                originalMethod.getName(),         // Delegates to the original method
                MethodCallExpression.NO_ARGUMENTS // Pass no arguments
            )
        ));

        // Create the new method node
        MethodNode duplicatedMethod = new MethodNode(
            newMethodName,
            originalMethod.getModifiers(),
            originalMethod.getReturnType(),
            originalMethod.getParameters(),
            originalMethod.getExceptions(),
            methodBody
        );

        // Add the duplicated method to the class
        classNode.addMethod(duplicatedMethod);
    }
}
