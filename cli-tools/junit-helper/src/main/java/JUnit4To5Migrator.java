


/*
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
    <version>3.25.8</version> <!-- or latest -->
</dependency>

 */


import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;

import java.nio.file.Files;
import java.nio.file.Paths;

public class JUnit4To5Migrator {
    public static void main(String[] args) throws Exception {
        String code = Files.readString(Paths.get("src/test/java/MyOldTest.java"));
        CompilationUnit cu = StaticJavaParser.parse(code);

        cu.accept(new ModifierVisitor<Void>() {
            @Override
            public MethodDeclaration visit(MethodDeclaration md, Void arg) {
                md.getAnnotations().forEach(anno -> {
                    if (anno.getNameAsString().equals("Test") && anno.isNormalAnnotationExpr()) {
                        NormalAnnotationExpr nae = anno.asNormalAnnotationExpr();
                        nae.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("expected"))
                                .findFirst()
                                .ifPresent(pair -> {
                                    // Get exception class name
                                    Expression expr = pair.getValue();
                                    String exceptionClass = expr.toString().replace(".class", "");

                                    // Remove `expected` attribute
                                    nae.getPairs().remove(pair);

                                    // Wrap original body in assertThrows
                                    if (md.getBody().isPresent()) {
                                        BlockStmt originalBody = md.getBody().get();
                                        LambdaExpr lambda = new LambdaExpr();
                                        lambda.setEnclosingParameters(true);
                                        lambda.setBody(originalBody);

                                        MethodCallExpr assertThrowsCall = new MethodCallExpr(
                                                null,
                                                "assertThrows",
                                                NodeList.nodeList(
                                                        new NameExpr(exceptionClass + ".class"),
                                                        lambda
                                                )
                                        );

                                        BlockStmt newBody = new BlockStmt();
                                        newBody.addStatement(new ExpressionStmt(assertThrowsCall));
                                        md.setBody(newBody);
                                    }
                                });
                    }
                });

                return super.visit(md, arg);
            }
        }, null);

        System.out.println(cu.toString());
    }
}
