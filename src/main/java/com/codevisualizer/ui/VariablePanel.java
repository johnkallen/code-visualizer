package com.codevisualizer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class VariablePanel {

    private final VBox root = new VBox();
    private final GridPane grid = new GridPane();
    private final Label emptyLabel = new Label("No variables found.");
    private final Button applyBtn = new Button("Apply");

    private final Map<String, Object> currentVariables = new LinkedHashMap<>();
    private final Map<String, TextField> fieldMap = new LinkedHashMap<>();

    private BiConsumer<String, Object> variableChangeListener;

    public VariablePanel() {
        root.setPrefHeight(120);
        root.setStyle("-fx-background-color: #eeeeee; -fx-padding: 10; -fx-spacing: 8;");

        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(0));

        applyBtn.setDisable(true);
        applyBtn.setOnAction(e -> applyAll());

        HBox btnRow = new HBox(applyBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox.setVgrow(grid, Priority.ALWAYS);
        root.getChildren().addAll(emptyLabel, btnRow);
    }

    public VBox getView() {
        return root;
    }

    public void setVariableChangeListener(BiConsumer<String, Object> listener) {
        this.variableChangeListener = listener;
    }

    public void updateVariables(Map<String, Object> vars) {
        currentVariables.clear();
        fieldMap.clear();
        root.getChildren().clear();

        HBox btnRow = new HBox(applyBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        applyBtn.setDisable(true);

        if (vars == null || vars.isEmpty()) {
            root.getChildren().addAll(emptyLabel, btnRow);
            return;
        }

        currentVariables.putAll(vars);
        rebuildGrid();
        VBox.setVgrow(grid, Priority.ALWAYS);
        root.getChildren().addAll(grid, btnRow);
    }

    public void clear() {
        currentVariables.clear();
        fieldMap.clear();
        root.getChildren().clear();
        applyBtn.setDisable(true);
    }

    private void rebuildGrid() {
        grid.getChildren().clear();

        int row = 0;
        for (Map.Entry<String, Object> entry : currentVariables.entrySet()) {
            String varName = entry.getKey();
            Object value = entry.getValue();
            String displayValue = value == null ? "null" : String.valueOf(value);

            Label nameLabel = new Label(varName + " =");
            TextField valueField = new TextField(displayValue);
            GridPane.setHgrow(valueField, Priority.ALWAYS);

            valueField.textProperty().addListener((obs, oldText, newText) -> {
                boolean anyDirty = fieldMap.entrySet().stream().anyMatch(e -> {
                    Object stored = currentVariables.get(e.getKey());
                    String storedStr = stored == null ? "null" : String.valueOf(stored);
                    return !e.getValue().getText().equals(storedStr);
                });
                applyBtn.setDisable(!anyDirty);
            });

            valueField.setOnAction(e -> applyAll());

            fieldMap.put(varName, valueField);
            grid.add(nameLabel, 0, row);
            grid.add(valueField, 1, row);
            row++;
        }
    }

    private void applyAll() {
        boolean anyError = false;

        for (Map.Entry<String, TextField> entry : fieldMap.entrySet()) {
            String varName = entry.getKey();
            TextField field = entry.getValue();
            Object oldValue = currentVariables.get(varName);
            String storedStr = oldValue == null ? "null" : String.valueOf(oldValue);

            if (field.getText().equals(storedStr)) continue; // unchanged

            try {
                Object parsed = parseValue(field.getText(), oldValue);
                if (variableChangeListener != null) {
                    variableChangeListener.accept(varName, parsed);
                }
                currentVariables.put(varName, parsed);
                field.setText(parsed == null ? "null" : String.valueOf(parsed));
                field.setStyle("");
                field.setTooltip(null);
            } catch (IllegalArgumentException ex) {
                field.setStyle("-fx-border-color: red;");
                field.setTooltip(new Tooltip(ex.getMessage()));
                anyError = true;
            }
        }

        if (!anyError) {
            applyBtn.setDisable(true);
        }
    }

    private Object parseValue(String text, Object originalValue) {
        String trimmed = text == null ? "" : text.trim();

        if ("null".equalsIgnoreCase(trimmed)) return null;

        if (originalValue == null)          return inferBestType(trimmed);
        if (originalValue instanceof Integer) {
            try { return Integer.parseInt(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected an integer."); }
        }
        if (originalValue instanceof Long) {
            try { return Long.parseLong(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a long."); }
        }
        if (originalValue instanceof Double) {
            try { return Double.parseDouble(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a double."); }
        }
        if (originalValue instanceof Float) {
            try { return Float.parseFloat(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a float."); }
        }
        if (originalValue instanceof Boolean) {
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed))
                return Boolean.parseBoolean(trimmed);
            throw new IllegalArgumentException("Expected true or false.");
        }
        if (originalValue instanceof Short) {
            try { return Short.parseShort(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a short."); }
        }
        if (originalValue instanceof Byte) {
            try { return Byte.parseByte(trimmed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Expected a byte."); }
        }
        if (originalValue instanceof Character) {
            if (trimmed.length() == 1) return trimmed.charAt(0);
            throw new IllegalArgumentException("Expected a single character.");
        }
        return trimmed;
    }

    private Object inferBestType(String value) {
        if (value.isEmpty()) return "";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
            return Boolean.parseBoolean(value);
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(value); }    catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) {}
        if (value.length() == 1) return value.charAt(0);
        return value;
    }
}
