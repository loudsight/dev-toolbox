//import com.github.javaparser.StaticJavaParser;
//import com.github.javaparser.ast.CompilationUnit;
//import com.github.javaparser.ast.body.MethodDeclaration;
//import com.github.javaparser.ast.expr.*;
//import com.github.javaparser.ast.stmt.BlockStmt;
//import com.github.javaparser.ast.stmt.ExpressionStmt;
//import com.github.javaparser.ast.visitor.ModifierVisitor;
//import com.github.javaparser.ast.NodeList;
//
//import java.nio.file.Files;
//import java.nio.file.Paths;
//
//public class JUnitTimeoutConverter {
//
//    public static void main(String[] args) throws Exception {
//        String path = "src/test/java/MyOldTest.java";
//        String code = Files.readString(Paths.get(path));
//        CompilationUnit cu = StaticJavaParser.parse(code);
//
//        cu.accept(new ModifierVisitor<Void>() {
//            @Override
//            public MethodDeclaration visit(MethodDeclaration md, Void arg) {
//                md.getAnnotations().forEach(anno -> {
//                    if (anno.getNameAsString().equals("Test") && anno.isNormalAnnotationExpr()) {
//                        NormalAnnotationExpr nae = anno.asNormalAnnotationExpr();
//
//                        Expression timeoutExpr = null;
//
//                        // Remove only the timeout attribute
//                        NodeList<MemberValuePair> newPairs = new NodeList<>();
//                        for (MemberValuePair pair : nae.getPairs()) {
//                            if (pair.getNameAsString().equals("timeout")) {
//                                timeoutExpr = pair.getValue();
//                            } else {
//                                newPairs.add(pair);
//                            }
//                        }
//                        nae.setPairs(newPairs);
//
//                        if (timeoutExpr != null && md.getBody().isPresent()) {
//                            BlockStmt originalBody = md.getBody().get();
//                            LambdaExpr lambda = new LambdaExpr();
//                            lambda.setEnclosingParameters(true);
//                            lambda.setBody(originalBody);
//
//                            MethodCallExpr timeoutCall = new MethodCallExpr(
//                                    null,
//                                    "assertTimeout",
//                                    NodeList.nodeList(
//                                            new MethodCallExpr(
//                                                    new NameExpr("Duration"),
//                                                    "ofMillis",
//                                                    NodeList.nodeList(timeoutExpr)
//                                            ),
//                                            lambda
//                                    )
//                            );
//
//                            BlockStmt newBody = new BlockStmt();
//                            newBody.addStatement(new ExpressionStmt(timeoutCall));
//                            md.setBody(newBody);
//                        }
//                    }
//                });
//
//                return super.visit(md, arg);
//            }
//        }, null);
//
//        System.out.println(cu.toString());
//    }
//}
