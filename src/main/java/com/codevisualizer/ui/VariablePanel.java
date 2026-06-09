package com.codevisualizer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class VariablePanel {

    private final HBox root = new HBox();

    // Left — current variables
    private final GridPane grid = new GridPane();
    private final Label emptyLabel = new Label("No variables found.");
    private final Button applyBtn = new Button("Apply");
    private final VBox leftPane = new VBox(4);

    // Right — mock return values
    private final GridPane mockGrid = new GridPane();
    private final Label emptyMockLabel = new Label("No method calls.");
    private final Button mockApplyBtn = new Button("Apply");
    private final VBox rightPane = new VBox(4);

    private final Map<String, Object> currentVariables = new LinkedHashMap<>();
    private final Map<String, TextField> fieldMap = new LinkedHashMap<>();
    private final Map<String, Object> currentMockValues = new LinkedHashMap<>();
    private final Map<String, TextField> mockFieldMap = new LinkedHashMap<>();

    private BiConsumer<String, Object> variableChangeListener;
    private BiConsumer<String, Object> mockReturnValueChangeListener;

    public VariablePanel() {
        root.setPrefHeight(120);
        root.setStyle("-fx-background-color: #eeeeee; -fx-padding: 8; -fx-spacing: 0;");

        // ── Left pane setup ──────────────────────────────────────────
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(0));

        applyBtn.setDisable(true);
        applyBtn.setOnAction(e -> applyAll());

        HBox leftBtnRow = new HBox(applyBtn);
        leftBtnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox.setVgrow(grid, Priority.ALWAYS);
        HBox.setHgrow(leftPane, Priority.ALWAYS);
        leftPane.getChildren().addAll(emptyLabel, leftBtnRow);

        // ── Right pane setup ─────────────────────────────────────────
        mockGrid.setHgap(10);
        mockGrid.setVgap(6);
        mockGrid.setPadding(new Insets(0));

        mockApplyBtn.setDisable(true);
        mockApplyBtn.setOnAction(e -> applyMockAll());

        Label returnValuesHeader = new Label("Return Values");
        returnValuesHeader.setStyle("-fx-font-weight: bold;");

        HBox mockBtnRow = new HBox(mockApplyBtn);
        mockBtnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox.setVgrow(mockGrid, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);
        rightPane.getChildren().addAll(returnValuesHeader, emptyMockLabel, mockBtnRow);

        Separator divider = new Separator(Orientation.VERTICAL);
        divider.setPadding(new Insets(0, 8, 0, 8));

        root.getChildren().addAll(leftPane, divider, rightPane);
    }

    public HBox getView() {
        return root;
    }

    public void setVariableChangeListener(BiConsumer<String, Object> listener) {
        this.variableChangeListener = listener;
    }

    public void setMockReturnValueChangeListener(BiConsumer<String, Object> listener) {
        this.mockReturnValueChangeListener = listener;
    }

    // ── Variables (left) ────────────────────────────────────────────

    public void updateVariables(Map<String, Object> vars) {
        currentVariables.clear();
        fieldMap.clear();

        HBox btnRow = new HBox(applyBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        applyBtn.setDisable(true);

        leftPane.getChildren().clear();
        if (vars == null || vars.isEmpty()) {
            leftPane.getChildren().addAll(emptyLabel, btnRow);
            return;
        }

        currentVariables.putAll(vars);
        rebuildGrid();
        VBox.setVgrow(grid, Priority.ALWAYS);
        leftPane.getChildren().addAll(grid, btnRow);
    }

    // ── Mock return values (right) ───────────────────────────────────

    public void updateMockReturnValues(Map<String, Object> mockValues) {
        currentMockValues.clear();
        mockFieldMap.clear();

        HBox btnRow = new HBox(mockApplyBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        mockApplyBtn.setDisable(true);

        Label header = new Label("Return Values");
        header.setStyle("-fx-font-weight: bold;");

        rightPane.getChildren().clear();
        rightPane.getChildren().add(header);

        if (mockValues == null || mockValues.isEmpty()) {
            rightPane.getChildren().addAll(emptyMockLabel, btnRow);
            return;
        }

        currentMockValues.putAll(mockValues);
        rebuildMockGrid();
        VBox.setVgrow(mockGrid, Priority.ALWAYS);
        rightPane.getChildren().addAll(mockGrid, btnRow);
    }

    public void clear() {
        currentVariables.clear();
        fieldMap.clear();
        currentMockValues.clear();
        mockFieldMap.clear();
        applyBtn.setDisable(true);
        mockApplyBtn.setDisable(true);

        leftPane.getChildren().clear();
        leftPane.getChildren().add(emptyLabel);

        rightPane.getChildren().clear();
        Label header = new Label("Return Values");
        header.setStyle("-fx-font-weight: bold;");
        rightPane.getChildren().addAll(header, emptyMockLabel);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private void rebuildGrid() {
        grid.getChildren().clear();
        int row = 0;
        for (Map.Entry<String, Object> entry : currentVariables.entrySet()) {
            String varName = entry.getKey();
            String displayValue = entry.getValue() == null ? "null" : String.valueOf(entry.getValue());

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

    private void rebuildMockGrid() {
        mockGrid.getChildren().clear();
        int row = 0;
        for (Map.Entry<String, Object> entry : currentMockValues.entrySet()) {
            String methodName = entry.getKey();
            String displayValue = entry.getValue() == null ? "null" : String.valueOf(entry.getValue());

            TextField valueField = new TextField(displayValue);
            GridPane.setHgrow(valueField, Priority.ALWAYS);
            Label nameLabel = new Label(methodName + "()");

            valueField.textProperty().addListener((obs, oldText, newText) -> {
                boolean anyDirty = mockFieldMap.entrySet().stream().anyMatch(e -> {
                    Object stored = currentMockValues.get(e.getKey());
                    String storedStr = stored == null ? "null" : String.valueOf(stored);
                    return !e.getValue().getText().equals(storedStr);
                });
                mockApplyBtn.setDisable(!anyDirty);
            });

            valueField.setOnAction(e -> applyMockAll());
            mockFieldMap.put(methodName, valueField);
            mockGrid.add(valueField, 0, row);
            mockGrid.add(nameLabel, 1, row);
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
            if (field.getText().equals(storedStr)) continue;

            try {
                Object parsed = parseValue(field.getText(), oldValue);
                if (variableChangeListener != null) variableChangeListener.accept(varName, parsed);
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
        if (!anyError) applyBtn.setDisable(true);
    }

    private void applyMockAll() {
        boolean anyError = false;
        for (Map.Entry<String, TextField> entry : mockFieldMap.entrySet()) {
            String methodName = entry.getKey();
            TextField field = entry.getValue();
            Object oldValue = currentMockValues.get(methodName);
            String storedStr = oldValue == null ? "null" : String.valueOf(oldValue);
            if (field.getText().equals(storedStr)) continue;

            try {
                Object parsed = parseValue(field.getText(), oldValue);
                if (mockReturnValueChangeListener != null)
                    mockReturnValueChangeListener.accept(methodName, parsed);
                currentMockValues.put(methodName, parsed);
                field.setText(parsed == null ? "null" : String.valueOf(parsed));
                field.setStyle("");
                field.setTooltip(null);
            } catch (IllegalArgumentException ex) {
                field.setStyle("-fx-border-color: red;");
                field.setTooltip(new Tooltip(ex.getMessage()));
                anyError = true;
            }
        }
        if (!anyError) mockApplyBtn.setDisable(true);
    }

    private Object parseValue(String text, Object originalValue) {
        String trimmed = text == null ? "" : text.trim();
        if ("null".equalsIgnoreCase(trimmed)) return null;
        if (originalValue == null) return inferBestType(trimmed);
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
