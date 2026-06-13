package com.codevisualizer.ui;

import com.codevisualizer.engine.ExecutionEngine;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.model.StepEvent;
import com.codevisualizer.parser.CodeParser;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import javafx.beans.binding.Bindings;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class MainView {

    private final BorderPane root = new BorderPane();

    private final CodeArea codeEditor = new CodeArea();
    private final VariablePanel variablePanel = new VariablePanel();
    private final FlowChartView flowChartView = new FlowChartView();
    private final Label statusLabel = new Label("Paste code and click Visualize.");

    private ExecutionEngine engine;
    private CodeParser.ParseResult lastResult;
    private String lastCode;
    private final Button stepBtn;
    private final ComboBox<String> methodSelector = new ComboBox<>();
    private final Label methodLabel = new Label("Method:");

    public MainView() {

        codeEditor.getStylesheets().add(
                Objects.requireNonNull(MainView.class.getResource("/com/codeflow/syntax.css"),
                        "syntax.css not found on classpath").toExternalForm()
        );
        codeEditor.setPrefWidth(500);

        // Apply syntax highlighting on every keystroke
        codeEditor.textProperty().addListener((obs, oldText, newText) -> {
            if (!newText.isEmpty()) {
                codeEditor.setStyleSpans(0, JavaSyntaxHighlighter.computeHighlighting(newText));
            }
        });

        VirtualizedScrollPane<CodeArea> codeScroll = new VirtualizedScrollPane<>(codeEditor);

        // Create SplitPane with titled containers
        SplitPane split = new SplitPane();
        split.setDividerPositions(0.4);

        // Wrap Code Editor in a VBox with a title
        VBox codeContainer = new VBox(5);
        Label codeLabel = new Label("Code");
        codeLabel.setStyle("-fx-font-weight: bold; -fx-padding: 5 10; -fx-background-color: #e0e0e0;");
        VBox.setVgrow(codeScroll, Priority.ALWAYS);
        codeContainer.getChildren().addAll(codeLabel, codeScroll);
        codeContainer.setStyle("-fx-padding: 5; -fx-background-color: #f0f0f0;");

        // Wrap Flowchart View in a VBox with a title
        VBox flowchartContainer = new VBox(5);
        Label flowLabel = new Label("Flowchart");
        flowLabel.setStyle("-fx-font-weight: bold; -fx-padding: 5 10; -fx-background-color: #e0e0e0;");
        VBox.setVgrow(flowChartView.getView(), Priority.ALWAYS);
        flowchartContainer.getChildren().addAll(flowLabel, flowChartView.getView());
        flowchartContainer.setStyle("-fx-padding: 5; -fx-background-color: #f0f0f0;");

        split.getItems().addAll(codeContainer, flowchartContainer);

        // Wrap Variables Panel in a VBox with a title
        VBox variableContainer = new VBox(5);
        Label varLabel = new Label("Variables");
        varLabel.setStyle("-fx-font-weight: bold; -fx-padding: 5 10; -fx-background-color: #e0e0e0;");
        variableContainer.getChildren().addAll(varLabel, variablePanel.getView());
        variableContainer.setStyle("-fx-padding: 5; -fx-background-color: #f0f0f0;");

        // Method selector — hidden until a multi-method class is loaded
        methodLabel.setStyle("-fx-font-weight: bold; -fx-padding: 0 4 0 10;");
        methodLabel.setVisible(false);
        methodLabel.setManaged(false);
        methodSelector.setPrefWidth(220);
        methodSelector.setVisible(false);
        methodSelector.setManaged(false);
        methodSelector.setOnAction(e -> {
            String sig = methodSelector.getValue();
            if (sig != null && lastCode != null) {
                String name = sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
                visualizeMethod(name);
            }
        });

        // Create HBox for controls
        stepBtn = new Button("Step");
        Button visualizeBtn = new Button("Visualize");
        Button fitBtn = new Button("Fit");
        Button clearBtn = new Button("Clear");
        HBox controls = new HBox(10);
        controls.getChildren().addAll(
                visualizeBtn,
                methodLabel,
                methodSelector,
                stepBtn,
                fitBtn,
                clearBtn,
                statusLabel
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exportBtn = new Button("Export");

        Label dimensionsLabel = new Label();
        dimensionsLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11;");
        dimensionsLabel.textProperty().bind(Bindings.createStringBinding(
                () -> (int) root.getWidth() + " × " + (int) root.getHeight(),
                root.widthProperty(), root.heightProperty()
        ));

        controls.getChildren().addAll(spacer, exportBtn, dimensionsLabel);
        controls.setStyle("-fx-padding: 10 15 15 15;");

        root.setTop(variableContainer);
        root.setCenter(split);
        root.setBottom(controls);

        visualizeBtn.setOnAction(e -> analyzeCode());
        stepBtn.setOnAction(e -> step());
        fitBtn.setOnAction(e -> flowChartView.fitToScreen());
        clearBtn.setOnAction(e -> clearAll());

        exportBtn.setOnAction(e -> {
            String xmlContent = flowChartView.generateDrawIOXML();
            if (xmlContent.isEmpty()) {
                statusLabel.setText("No flowchart to export.");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save as draw.io");
            fileChooser.setInitialFileName("flowchart");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("draw.io files (*.drawio)", "*.drawio"));

            File file = fileChooser.showSaveDialog(root.getScene().getWindow());
            if (file == null) {
                statusLabel.setText("Export cancelled.");
                return;
            }

            try {
                Files.writeString(file.toPath(), xmlContent);
                statusLabel.setText("Exported to " + file.getAbsolutePath());
            } catch (Exception ex) {
                statusLabel.setText("Export failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        exportBtn.setStyle("-fx-padding: 5 15 5 15;");

        variablePanel.setVariableChangeListener((name, value) -> {
            if (engine == null) {
                statusLabel.setText("Visualize code first.");
                return;
            }
            try {
                engine.setVariable(name, value);
                statusLabel.setText("Updated variable: " + name + " = " + value);
            } catch (Exception ex) {
                statusLabel.setText("Failed to update variable: " + ex.getMessage());
                throw ex;
            }
        });

        variablePanel.setMockReturnValueChangeListener((methodName, value) -> {
            if (engine == null) {
                statusLabel.setText("Visualize code first.");
                return;
            }
            engine.setMockReturnValue(methodName, value);
            statusLabel.setText("Return value set: " + methodName + "() = " + value);
        });
    }

    public Parent getRoot() {
        return root;
    }

    private void analyzeCode() {
        try {
            String formatted = formatCode(codeEditor.getText());
            codeEditor.replaceText(formatted);
            // replaceText clears all style spans; reapply highlighting explicitly because
            // the textProperty listener only fires when the text *changes*, so identical
            // content after formatting leaves the editor unstyled.
            if (!formatted.isEmpty()) {
                codeEditor.setStyleSpans(0, JavaSyntaxHighlighter.computeHighlighting(formatted));
            }
            lastCode = formatted;

            // Discover all methods in the pasted code
            CodeParser parser = new CodeParser();
            List<String> signatures = parser.parseMethodSignatures(formatted);

            if (signatures.size() > 1) {
                // Multi-method class: populate selector with "All Methods" first
                List<String> items = new ArrayList<>();
                items.add("All Methods");
                items.addAll(signatures);

                methodSelector.setOnAction(null); // suppress during repopulation
                methodSelector.getItems().setAll(items);
                methodLabel.setVisible(true);
                methodLabel.setManaged(true);
                methodSelector.setVisible(true);
                methodSelector.setManaged(true);
                methodSelector.setValue("All Methods");
                methodSelector.setOnAction(e -> {
                    String selected = methodSelector.getValue();
                    if (selected == null || lastCode == null) return;
                    if ("All Methods".equals(selected)) {
                        visualizeAllMethods();
                    } else {
                        String name = selected.contains("(")
                                ? selected.substring(0, selected.indexOf('(')) : selected;
                        visualizeMethod(name);
                    }
                });
                visualizeAllMethods();
            } else {
                // Single method or bare code: hide selector and visualize directly
                hideMethodSelector();
                visualizeMethod(null);
            }

        } catch (Exception ex) {
            engine = null;
            lastResult = null;
            variablePanel.clear();
            flowChartView.clear();
            statusLabel.setText("Analyze failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /** Renders all methods stacked vertically; variables empty, Step disabled. */
    private void visualizeAllMethods() {
        try {
            CodeParser parser = new CodeParser();
            lastResult = parser.parseAll(lastCode);

            engine = null;
            variablePanel.updateVariables(java.util.Collections.emptyMap());
            variablePanel.updateMockReturnValues(java.util.Collections.emptyMap());
            flowChartView.drawFlow(lastResult.flowNodes, lastResult.flowEdges,
                    null, lastResult.streamGroups, lastResult.methodGroups);
            stepBtn.setDisable(true);
            statusLabel.setText("All methods shown. Select a method to step through.");

        } catch (Exception ex) {
            engine = null;
            lastResult = null;
            variablePanel.clear();
            flowChartView.clear();
            statusLabel.setText("Visualize failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /** Parses and renders the named method (null = first/only method). */
    private void visualizeMethod(String targetMethodName) {
        try {
            CodeParser parser = new CodeParser();
            lastResult = parser.parse(lastCode, targetMethodName);

            engine = new ExecutionEngine(lastResult.flowNodes, lastResult.variables, lastResult.mockReturnValues);
            variablePanel.updateVariables(engine.getVariables());
            variablePanel.updateMockReturnValues(lastResult.mockReturnValues);
            flowChartView.drawFlow(lastResult.flowNodes, lastResult.flowEdges,
                    lastResult.methodName, lastResult.streamGroups, lastResult.methodGroups);
            stepBtn.setDisable(false);
            statusLabel.setText("Visualize complete. Nodes: " + lastResult.flowNodes.size());

        } catch (Exception ex) {
            engine = null;
            lastResult = null;
            variablePanel.clear();
            flowChartView.clear();
            statusLabel.setText("Visualize failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void hideMethodSelector() {
        methodLabel.setVisible(false);
        methodLabel.setManaged(false);
        methodSelector.setVisible(false);
        methodSelector.setManaged(false);
        methodSelector.getItems().clear();
    }

    private String formatCode(String code) {
        try {
            String wrapped = wrapForParsing(code);
            CompilationUnit cu = StaticJavaParser.parse(wrapped);

            // Full class with multiple methods — return the whole class as-is
            if (cu.findAll(MethodDeclaration.class).size() > 1) {
                return code;
            }

            Optional<MethodDeclaration> method = cu.findFirst(MethodDeclaration.class);
            if (method.isEmpty() || method.get().getBody().isEmpty()) return code;

            MethodDeclaration m = method.get();

            // Single named method — return the formatted method declaration
            if (!"temp".equals(m.getNameAsString())) {
                return m.toString();
            }

            // Bare code block — strip the temp wrapper and return just the body
            String body = m.getBody().get().toString();
            body = body.substring(body.indexOf('{') + 1, body.lastIndexOf('}')).stripTrailing();
            String[] lines = body.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line.length() >= 4 ? line.substring(4) : line.stripLeading()).append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return code;
        }
    }

    private void step() {
        if (engine == null) {
            statusLabel.setText("Engine not initialized. Click Visualize first.");
            return;
        }

        StepEvent event = engine.step();
        flowChartView.clearHighlights();
        variablePanel.updateVariables(engine.getVariables());
        variablePanel.updateMockReturnValues(engine.getMockReturnValues());

        if (event.type == StepEvent.StepType.COMPLETE) {
            applyCodeHighlight(0, 0);
            flowChartView.clearHighlights();
            statusLabel.setText("End of Code Reached");
            stepBtn.setDisable(true);
            flowChartView.showEndOverlay(() -> {
                engine.reset();
                stepBtn.setDisable(false);
                statusLabel.setText("Ready. Press Step to run again.");
            });
            return;
        }

        if (event.type == StepEvent.StepType.NODE) {
            flowChartView.highlightNode(event.nodeId);
            FlowNode node = flowChartView.findNodeById(lastResult.flowNodes, event.nodeId);
            if (node != null) {
                applyCodeHighlight(node.beginLine, node.endLine);
                statusLabel.setText("Node: " + node.label);
            } else {
                statusLabel.setText("Ready to execute node");
            }
            return;
        }

        if (event.type == StepEvent.StepType.EDGE) {
            flowChartView.highlightEdge(event.edgeFromId, event.edgeToId);
            FlowNode fromNode = flowChartView.findNodeById(lastResult.flowNodes, event.edgeFromId);
            if (fromNode != null) {
                applyCodeHighlight(fromNode.beginLine, fromNode.endLine);
            }
            statusLabel.setText("Transition");
        }
    }

    private static String wrapForParsing(String code) {
        String noComments = code.replaceAll("/\\*(?s).*?\\*/", " ").replaceAll("//[^\n\r]*", " ");
        if (noComments.contains("class")) return code;
        String[] tokens = noComments.trim().split("\\s+");
        String first = tokens.length > 0 ? tokens[0] : "";
        boolean isMethodDecl = first.equals("public") || first.equals("private")
                || first.equals("protected") || first.equals("static") || first.equals("void");
        return isMethodDecl ? "class Temp { " + code + " }"
                            : "class Temp { void temp() { " + code + " } }";
    }

    private void applyCodeHighlight(int beginLine, int endLine) {
        String text = codeEditor.getText();
        if (text.isEmpty()) return;
        codeEditor.setStyleSpans(0, JavaSyntaxHighlighter.computeHighlightingWithStep(text, beginLine, endLine));
    }

    private void clearAll() {
        codeEditor.clear();
        flowChartView.clear();
        variablePanel.clear();
        hideMethodSelector();
        engine = null;
        lastResult = null;
        lastCode = null;
        statusLabel.setText("Paste code and click Visualize.");
    }
}
