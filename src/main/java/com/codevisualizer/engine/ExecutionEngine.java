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

    private Phase phase = Phase.SHOW_NODE;
    private String pendingNextNodeId;

    private enum Phase {
        SHOW_NODE,
        SHOW_EDGE
    }

    public ExecutionEngine(List<FlowNode> nodes, Map<String, Object> initialVariables) {
        for (FlowNode node : nodes) {
            nodesById.put(node.id, node);
        }

        context.variables.putAll(initialVariables);

        if (!nodes.isEmpty()) {
            context.currentNodeId = nodes.get(0).id;
        } else {
            context.finished = true;
        }
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
                if (pendingNextNodeId == null) {
                    context.finished = true;
                }
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

        if (expr.matches("^(int|double|long|float|boolean|String)\\s+\\w+\\s*=.*$")) {
            String withoutType = expr.replaceFirst("^(int|double|long|float|boolean|String)\\s+", "");
            String[] parts = withoutType.split("=", 2);
            String varName = parts[0].trim();
            String valueExpr = parts[1].trim();
            Object value = evaluateExpression(valueExpr);
            context.variables.put(varName, value);
            return;
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

    private Object evaluateExpression(String expr) {
        expr = expr.trim();

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

        return null;
    }

    private boolean evaluateCondition(String condition) {
        if (condition == null || condition.isBlank()) return false;

        String c = condition.trim();

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