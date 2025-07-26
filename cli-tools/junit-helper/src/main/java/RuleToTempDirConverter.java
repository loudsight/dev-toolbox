import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.nio.file.Files;
import java.nio.file.Paths;

public class RuleToTempDirConverter {

    public static void main(String[] args) throws Exception {
        String path = "src/test/java/MyOldTest.java";
        String code = Files.readString(Paths.get(path));
        CompilationUnit cu = StaticJavaParser.parse(code);

        // Add the @TempDir import
        cu.addImport("org.junit.jupiter.api.io.TempDir");
        cu.addImport("java.nio.file.Path");

        cu.accept(new ModifierVisitor<Void>() {
            @Override
            public FieldDeclaration visit(FieldDeclaration fd, Void arg) {
                if (fd.isAnnotationPresent("Rule") &&
                    fd.getElementType().isClassOrInterfaceType() &&
                    fd.getElementType().asClassOrInterfaceType().getNameAsString().equals("TemporaryFolder")) {

                    // Remove @Rule
                    fd.getAnnotations().removeIf(a -> a.getNameAsString().equals("Rule"));

                    // Replace type with Path
                    fd.setVariable(0, fd.getVariable(0).setType("Path"));
                    fd.addAnnotation("TempDir");

                    // Rename variable from `folder` or anything else to `tempDir`
                    fd.getVariable(0).setName("tempDir");
                }
                return super.visit(fd, arg);
            }

            @Override
            public Expression visit(MethodCallExpr mc, Void arg) {
                if (mc.getScope().isPresent()) {
                    Expression scope = mc.getScope().get();
                    if (scope.isNameExpr() &&
                        (scope.asNameExpr().getNameAsString().equals("folder") || scope.asNameExpr().getNameAsString().equals("tempFolder"))) {

                        String methodName = mc.getNameAsString();
                        if (methodName.equals("newFile") || methodName.equals("newFolder")) {
                            // Replace with: new File(tempDir.toFile(), "filename")
                            NameExpr tempDir = new NameExpr("tempDir");
                            MethodCallExpr toFile = new MethodCallExpr(tempDir, "toFile");
                            Expression fileName = mc.getArguments().isEmpty()
                                    ? new StringLiteralExpr("temp") // fallback
                                    : mc.getArgument(0);

                            return new ObjectCreationExpr(null,
                                    StaticJavaParser.parseClassOrInterfaceType("File"),
                                    new NodeList<>(toFile, fileName));
                        }
                    }
                }
                return super.visit(mc, arg);
            }
        }, null);

        // Remove import of TemporaryFolder if present
        cu.getImports().removeIf(im -> im.getNameAsString().equals("org.junit.rules.TemporaryFolder"));

        System.out.println(cu.toString());
    }
}
