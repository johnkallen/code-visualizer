package com.codevisualizer.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaSyntaxHighlighter {

    private static final String[] KEYWORDS = {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int", "interface",
        "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "try", "var", "void",
        "volatile", "while", "true", "false", "null", "String"
    };

    private static final Pattern PATTERN = Pattern.compile(
        "(?<COMMENTBLOCK>/\\*(?s:.*?)\\*/)"
        + "|(?<COMMENTLINE>//[^\n]*)"
        + "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")"
        + "|(?<CHAR>'([^'\\\\]|\\\\.)')"
        + "|(?<ANNOTATION>@\\w+)"
        + "|(?<KEYWORD>\\b(" + String.join("|", KEYWORDS) + ")\\b)"
        + "|(?<NUMBER>\\b\\d+(\\.\\d+)?[fFdDlL]?\\b)"
    );

    public static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;

        while (matcher.find()) {
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleFor(matcher)), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    // Merges syntax highlighting with a step-highlight background on the given lines (1-based).
    public static StyleSpans<Collection<String>> computeHighlightingWithStep(String text, int beginLine, int endLine) {
        StyleSpans<Collection<String>> syntax = computeHighlighting(text);
        if (beginLine <= 0 || text.isEmpty()) return syntax;

        int stepStart = lineToCharOffset(text, beginLine);
        int stepEnd   = Math.min(lineToCharOffset(text, endLine + 1), text.length());
        if (stepStart >= stepEnd) return syntax;

        StyleSpansBuilder<Collection<String>> stepBuilder = new StyleSpansBuilder<>();
        if (stepStart > 0)              stepBuilder.add(List.of(),                   stepStart);
        stepBuilder.add(List.of("step-highlight"),  stepEnd - stepStart);
        if (stepEnd < text.length())    stepBuilder.add(List.of(),                   text.length() - stepEnd);

        return syntax.overlay(stepBuilder.create(), (syntaxStyles, stepStyles) -> {
            if (stepStyles.isEmpty()) return syntaxStyles;
            LinkedHashSet<String> merged = new LinkedHashSet<>(syntaxStyles);
            merged.addAll(stepStyles);
            return merged;
        });
    }

    // Returns the character offset of the start of the given 1-based line number.
    private static int lineToCharOffset(String text, int line) {
        if (line <= 1) return 0;
        int current = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++current == line) return i + 1;
        }
        return text.length();
    }

    private static String styleFor(Matcher m) {
        if (m.group("COMMENTBLOCK") != null) return "comment";
        if (m.group("COMMENTLINE")  != null) return "comment";
        if (m.group("STRING")       != null) return "string";
        if (m.group("CHAR")         != null) return "string";
        if (m.group("ANNOTATION")   != null) return "annotation";
        if (m.group("KEYWORD")      != null) return "keyword";
        if (m.group("NUMBER")       != null) return "number";
        return "";
    }
}
