import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Syntax-only check. This deliberately does not claim Android API compilation. */
public final class CheckJavaSyntax {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "src" : args[0]);
        List<java.io.File> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .sorted().forEach(p -> sources.add(p.toFile()));
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("No Java sources: " + root);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK compiler module is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    java.util.Arrays.asList("-proc:none", "-source", "8"), null,
                    files.getJavaFileObjectsFromFiles(sources));
            task.parse();
            boolean failed = false;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    failed = true;
                    System.err.println(diagnostic);
                }
            }
            if (failed) System.exit(1);
        }
        System.out.println("Parsed " + sources.size()
                + " Java source files (syntax only; Android API type checking NOT performed)");
    }
}
