package javax.lang.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal Android compatibility shim for GraphHopper.
 * GraphHopper 8 references javax.lang.model.SourceVersion to check Java keywords,
 * but Android does not ship this desktop JDK class.
 */
public final class SourceVersion {

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false", "null"
    ));

    private SourceVersion() {
    }

    public static boolean isKeyword(CharSequence name) {
        return name != null && JAVA_KEYWORDS.contains(name.toString());
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }

        char first = name.charAt(0);
        if (!Character.isJavaIdentifierStart(first) || isKeyword(name)) {
            return false;
        }

        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
