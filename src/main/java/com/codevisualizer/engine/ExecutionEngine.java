package com.codevisualizer.engine;

import com.codevisualizer.model.ExecutionContext;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.model.StepEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionEngine {

    private final Map<String, FlowNode> nodesById = new HashMap<>();
    private final ExecutionContext context = new ExecutionContext();
    private final Map<String, Object> mockReturnValues = new HashMap<>();

    private Phase phase = Phase.SHOW_NODE;
    private String pendingNextNodeId;
    private String firstNodeId;

    private enum Phase {
        SHOW_NODE,
        SHOW_EDGE
    }

    public ExecutionEngine(List<FlowNode> nodes, Map<String, Object> initialVariables,
                           Map<String, Object> mockReturnValues) {
        for (FlowNode node : nodes) {
            nodesById.put(node.id, node);
        }

        context.variables.putAll(initialVariables);
        this.mockReturnValues.putAll(mockReturnValues);

        if (!nodes.isEmpty()) {
            firstNodeId = nodes.get(0).id;
            context.currentNodeId = firstNodeId;
        } else {
            context.finished = true;
        }
    }

    public void reset() {
        context.currentNodeId = firstNodeId;
        context.finished = (firstNodeId == null);
        context.loopElements.clear();
        context.loopIndex.clear();
        phase = Phase.SHOW_NODE;
        pendingNextNodeId = null;
    }

    public StepEvent step() {
        if (context.finished || context.currentNodeId == null) {
            return StepEvent.complete();
        }

        FlowNode current = nodesById.get(context.currentNodeId);
        if (current == null) {
            context.finished = true;
            return StepEvent.complete();
        }

        if (phase == Phase.SHOW_NODE) {
            phase = Phase.SHOW_EDGE;
            return StepEvent.node(current.id);
        }

        if (phase == Phase.SHOW_EDGE) {
            executeNode(current);

            if (pendingNextNodeId == null) {
                context.finished = true;
                return StepEvent.complete();
            }

            String fromId = current.id;
            String toId = pendingNextNodeId;

            context.currentNodeId = pendingNextNodeId;
            pendingNextNodeId = null;
            phase = Phase.SHOW_NODE;

            return StepEvent.edge(fromId, toId);
        }

        return StepEvent.complete();
    }

    public Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(context.variables);
    }

    public void setMockReturnValue(String methodName, Object value) {
        mockReturnValues.put(methodName, value);
    }

    public Map<String, Object> getMockReturnValues() {
        return Collections.unmodifiableMap(mockReturnValues);
    }

    /** True if this DECISION's false-exit leads back to a LOOP (stream filter pattern). */
    private boolean isStreamFilter(FlowNode decision) {
        if (decision.falseNextId == null) return false;
        FlowNode falseTarget = nodesById.get(decision.falseNextId);
        return falseTarget != null && falseTarget.type == com.codevisualizer.enums.NodeType.LOOP;
    }

    /** When a stream filter passes, increment the terminal's mock return value (e.g. count). */
    private void incrementStreamCount(FlowNode filterDecision) {
        FlowNode loopNode = nodesById.get(filterDecision.falseNextId);
        if (loopNode == null) return;
        String key = terminalCountKey(loopNode);
        if (key != null) mockReturnValues.merge(key, 1, (a, b) -> toInt(a) + 1);
    }

    /** Resets the terminal count to 0 when the loop starts a new pass. */
    private void resetStreamTerminalCount(FlowNode loopNode) {
        String key = terminalCountKey(loopNode);
        if (key != null) mockReturnValues.put(key, 0);
    }

    /** Extracts the method name from the terminal node's expression, e.g. "count" from "passing = count()". */
    private String terminalCountKey(FlowNode loopNode) {
        if (loopNode.falseNextId == null) return null;
        FlowNode terminal = nodesById.get(loopNode.falseNextId);
        if (terminal == null || terminal.expression == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\w+)\\(\\)\\s*$")
                .matcher(terminal.expression);
        return m.find() ? m.group(1) : null;
    }

    public void setVariable(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name cannot be blank.");
        }

        if (!context.variables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown variable: " + name);
        }

        context.variables.put(name, value);
    }

    private void executeNode(FlowNode node) {
        switch (node.type) {
            case DECISION -> {
                boolean result = evaluateCondition(node.condition);
                pendingNextNodeId = result ? node.trueNextId : node.falseNextId;
                if (result && isStreamFilter(node)) incrementStreamCount(node);
                if (pendingNextNodeId == null) context.finished = true;
            }
            case LOOP -> {
                // Initialise element list on first visit
                if (!context.loopElements.containsKey(node.id)) {
                    context.loopElements.put(node.id, parseLoopElements(node));
                    context.loopIndex.put(node.id, 0);
                    resetStreamTerminalCount(node);
                }
                List<Object> elements = context.loopElements.get(node.id);
                int idx = context.loopIndex.get(node.id);
                if (idx < elements.size()) {
                    // Set loop variable to current element and advance index
                    setLoopParam(node, elements.get(idx));
                    context.loopIndex.put(node.id, idx + 1);
                    pendingNextNodeId = node.trueNextId;
                } else {
                    // All elements consumed — exit loop
                    context.loopElements.remove(node.id);
                    context.loopIndex.remove(node.id);
                    pendingNextNodeId = node.falseNextId;
                }
                if (pendingNextNodeId == null) context.finished = true;
            }
            case END -> {
                pendingNextNodeId = null;
                context.finished = true;
            }
            default -> {
                executeProcess(node);
                pendingNextNodeId = node.nextId;
                if (pendingNextNodeId == null) {
                    context.finished = true;
                }
            }
        }
    }

    private void executeProcess(FlowNode node) {
        String expr = node.expression;
        if (expr == null || expr.isBlank()) {
            return;
        }

        expr = expr.trim();

        if (expr.endsWith(";")) {
            expr = expr.substring(0, expr.length() - 1).trim();
        }

        java.util.regex.Matcher typedDecl = java.util.regex.Pattern
                .compile("^(int|double|long|float|boolean|String)\\s+(\\w+)\\s*=(.*)$")
                .matcher(expr);
        if (typedDecl.matches()) {
            String typeName  = typedDecl.group(1);
            String varName   = typedDecl.group(2);
            String valueExpr = typedDecl.group(3).trim();
            Object value = evaluateExpression(valueExpr);
            if (value == null) value = defaultValue(typeName);
            context.variables.put(varName, value);
            return;
        }

        // Non-primitive typed declaration: e.g. List<Integer> scores = Arrays.asList(...)
        // Extract variable name as the last word before '='
        if (expr.contains("=")) {
            int eqIdx = expr.indexOf('=');
            boolean isCompound = eqIdx > 0 && "!=<>".indexOf(expr.charAt(eqIdx - 1)) >= 0;
            boolean isDoubleEq = eqIdx + 1 < expr.length() && expr.charAt(eqIdx + 1) == '=';
            if (!isCompound && !isDoubleEq) {
                String lhs = expr.substring(0, eqIdx).trim();
                java.util.regex.Matcher lastWord = java.util.regex.Pattern
                        .compile("(\\w+)$").matcher(lhs);
                if (lastWord.find()) {
                    String varName = lastWord.group(1);
                    if (context.variables.containsKey(varName) && lhs.contains(" ")) {
                        String valueExpr = expr.substring(eqIdx + 1).trim();
                        Object value = evaluateExpression(valueExpr);
                        if (value != null) context.variables.put(varName, value);
                        return;
                    }
                }
            }
        }

        if (expr.matches("^\\w+\\s*=.*$")) {
            String[] parts = expr.split("=", 2);
            String varName = parts[0].trim();
            String valueExpr = parts[1].trim();
            Object value = evaluateExpression(valueExpr);
            context.variables.put(varName, value);
            return;
        }

        if (expr.matches("^\\w+\\+\\+$")) {
            String varName = expr.substring(0, expr.length() - 2).trim();
            Object current = context.variables.get(varName);
            int value = toInt(current);
            context.variables.put(varName, value + 1);
            return;
        }

        if (expr.matches("^\\w+--$")) {
            String varName = expr.substring(0, expr.length() - 2).trim();
            Object current = context.variables.get(varName);
            int value = toInt(current);
            context.variables.put(varName, value - 1);
        }
    }

    /** Parses the source collection from the LOOP label into an ordered list of elements. */
    private List<Object> parseLoopElements(FlowNode loopNode) {
        List<Object> elements = new java.util.ArrayList<>();
        if (loopNode.label == null) return elements;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("for each (\\w+) in (\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(loopNode.label);
        if (!m.find()) return elements;
        Object sourceVal = context.variables.get(m.group(2));
        if (sourceVal == null) return elements;
        for (String token : sourceVal.toString().split(",")) {
            String t = token.trim();
            try { elements.add(Integer.parseInt(t)); }
            catch (NumberFormatException e) { elements.add(t); }
        }
        return elements;
    }

    /** Sets the loop param variable (e.g. "s") to the given element value. */
    private void setLoopParam(FlowNode loopNode, Object value) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("for each (\\w+) in", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(loopNode.label != null ? loopNode.label : "");
        if (m.find()) context.variables.put(m.group(1), value);
    }

    private Object evaluateExpression(String expr) {
        expr = expr.trim();

        // String literal: "hello" → hello
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return expr.substring(1, expr.length() - 1);
        }

        if ("null".equals(expr)) {
            return null;
        }

        if (expr.matches("^-?\\d+$")) {
            return Integer.parseInt(expr);
        }

        if ("true".equals(expr)) {
            return true;
        }

        if ("false".equals(expr)) {
            return false;
        }

        if (expr.matches("^\\w+$")) {
            return context.variables.get(expr);
        }

        if (expr.matches("^\\w+\\s*\\(.*\\)$")) {
            String methodName = expr.substring(0, expr.indexOf('(')).trim();
            return mockReturnValues.get(methodName);
        }

        // Scoped method call: e.g. Arrays.asList(...), scores.size()
        // Only match when the ENTIRE expression is a method call (ends with ')'),
        // so that "scores.size() - passing" falls through to the arithmetic split.
        if (expr.matches("^[\\w.]+\\.\\w+\\(.*\\)$")) {
            int firstParen = expr.indexOf('(');
            int lastDot = expr.lastIndexOf('.', firstParen);
            if (lastDot >= 0) {
                String scope      = expr.substring(0, lastDot).trim();
                String methodName = expr.substring(lastDot + 1, firstParen).trim();
                // size()/length() on a variable stored as a comma-separated string
                if (("size".equals(methodName) || "length".equals(methodName))
                        && context.variables.containsKey(scope)) {
                    Object val = context.variables.get(scope);
                    if (val == null) return 0;
                    String s = val.toString().trim();
                    return s.isEmpty() ? 0 : s.split(",").length;
                }
                if (mockReturnValues.containsKey(methodName)) {
                    return mockReturnValues.get(methodName);
                }
            }
        }

        // Ternary: condition ? trueExpr : falseExpr  (must precede arithmetic splits)
        int qIdx = expr.indexOf('?');
        if (qIdx > 0) {
            String cond = expr.substring(0, qIdx).trim();
            String rest = expr.substring(qIdx + 1);
            int cIdx = rest.indexOf(':');
            if (cIdx >= 0) {
                String trueExpr  = rest.substring(0, cIdx).trim();
                String falseExpr = rest.substring(cIdx + 1).trim();
                return evaluateCondition(cond)
                        ? evaluateExpression(trueExpr)
                        : evaluateExpression(falseExpr);
            }
        }

        if (expr.contains("+")) {
            String[] parts = expr.split("\\+");
            int sum = 0;
            for (String part : parts) {
                sum += toInt(evaluateExpression(part.trim()));
            }
            return sum;
        }

        if (expr.contains("-")) {
            String[] parts = expr.split("-");
            int result = toInt(evaluateExpression(parts[0].trim()));
            for (int i = 1; i < parts.length; i++) {
                result -= toInt(evaluateExpression(parts[i].trim()));
            }
            return result;
        }

        if (expr.contains("*")) {
            String[] parts = expr.split("\\*");
            int result = 1;
            for (String part : parts) {
                result *= toInt(evaluateExpression(part.trim()));
            }
            return result;
        }

        if (expr.contains("/")) {
            String[] parts = expr.split("/");
            int result = toInt(evaluateExpression(parts[0].trim()));
            for (int i = 1; i < parts.length; i++) {
                int divisor = toInt(evaluateExpression(parts[i].trim()));
                if (divisor != 0) result /= divisor;
            }
            return result;
        }

        if (expr.contains("%")) {
            String[] parts = expr.split("%");
            int result = toInt(evaluateExpression(parts[0].trim()));
            for (int i = 1; i < parts.length; i++) {
                int divisor = toInt(evaluateExpression(parts[i].trim()));
                if (divisor != 0) result %= divisor;
            }
            return result;
        }

        return null;
    }

    private boolean evaluateCondition(String condition) {
        if (condition == null || condition.isBlank()) return false;

        String c = condition.trim();

        // Strip outer parentheses, e.g. "(passing > failing)"
        while (c.startsWith("(") && c.endsWith(")")) {
            c = c.substring(1, c.length() - 1).trim();
        }

        // || has lowest precedence — split and short-circuit OR
        if (c.contains("||")) {
            for (String part : c.split("\\|\\|")) {
                if (evaluateCondition(part.trim())) return true;
            }
            return false;
        }

        // && next — split and short-circuit AND
        if (c.contains("&&")) {
            for (String part : c.split("&&")) {
                if (!evaluateCondition(part.trim())) return false;
            }
            return true;
        }

        // Single comparisons — check two-char operators before one-char to avoid mis-splits
        if (c.contains(">=")) {
            String[] parts = c.split(">=", 2);
            return toInt(evaluateExpression(parts[0].trim())) >= toInt(evaluateExpression(parts[1].trim()));
        }
        if (c.contains("<=")) {
            String[] parts = c.split("<=", 2);
            return toInt(evaluateExpression(parts[0].trim())) <= toInt(evaluateExpression(parts[1].trim()));
        }
        if (c.contains("==")) {
            String[] parts = c.split("==", 2);
            Object left = evaluateExpression(parts[0].trim());
            Object right = evaluateExpression(parts[1].trim());
            return left == null ? right == null : left.equals(right);
        }
        if (c.contains("!=")) {
            String[] parts = c.split("!=", 2);
            Object left = evaluateExpression(parts[0].trim());
            Object right = evaluateExpression(parts[1].trim());
            return !(left == null ? right == null : left.equals(right));
        }
        if (c.contains(">")) {
            String[] parts = c.split(">", 2);
            return toInt(evaluateExpression(parts[0].trim())) > toInt(evaluateExpression(parts[1].trim()));
        }
        if (c.contains("<")) {
            String[] parts = c.split("<", 2);
            return toInt(evaluateExpression(parts[0].trim())) < toInt(evaluateExpression(parts[1].trim()));
        }

        Object result = evaluateExpression(c);
        return result instanceof Boolean b && b;
    }

    private static Object defaultValue(String typeName) {
        return switch (typeName) {
            case "int", "long", "short", "byte" -> 0;
            case "double", "float" -> 0.0;
            case "boolean" -> false;
            default -> null;
        };
    }

    private int toInt(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("^-?\\d+$")) {
            return Integer.parseInt(s);
        }
        return 0;
    }
}