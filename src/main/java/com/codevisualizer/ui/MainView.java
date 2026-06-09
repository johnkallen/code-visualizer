package com.codevisualizer.ui;

import com.codevisualizer.engine.ExecutionEngine;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.model.StepEvent;
import com.codevisualizer.parser.CodeParser;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final Button stepBtn;

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
        split.setDividerPositions(0.3);

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

        // Create HBox for controls
        stepBtn = new Button("Step");
        Button clearBtn = new Button("Clear");
        HBox controls = new HBox(10);
        controls.getChildren().addAll(
                new Button("Visualize"),
                stepBtn,
                new Button("Fit"),
                clearBtn,
                statusLabel
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exportBtn = new Button("Export");
        controls.getChildren().addAll(spacer, exportBtn);
        controls.setStyle("-fx-padding: 10 15 15 15;");

        root.setTop(variableContainer);
        root.setCenter(split);
        root.setBottom(controls);

        ((Button) controls.getChildren().get(0)).setOnAction(e -> analyzeCode());
        ((Button) controls.getChildren().get(1)).setOnAction(e -> step());
        ((Button) controls.getChildren().get(2)).setOnAction(e -> flowChartView.fitToScreen());
        clearBtn.setOnAction(e -> clearAll());

        exportBtn.setOnAction(e -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            File selectedDirectory = directoryChooser.showDialog(root.getScene().getWindow());
            if (selectedDirectory == null) {
                statusLabel.setText("Export cancelled.");
                return;
            }

            TextInputDialog filenameDialog = new TextInputDialog("flowchart");
            filenameDialog.setTitle("Export to draw.io");
            filenameDialog.setHeaderText("Enter filename (without .drawio extension):");
            filenameDialog.setContentText("Filename:");

            Optional<String> result = filenameDialog.showAndWait();
            result.ifPresent(filename -> {
                String fullPath = selectedDirectory.getAbsolutePath() + File.separator + filename + ".drawio";
                String xmlContent = flowChartView.generateDrawIOXML();
                if (xmlContent.isEmpty()) {
                    statusLabel.setText("No flowchart to export.");
                    return;
                }
                try {
                    Files.writeString(Paths.get(fullPath), xmlContent);
                    statusLabel.setText("Exported to " + fullPath);
                } catch (Exception ex) {
                    statusLabel.setText("Export failed: " + ex.getMessage());
                    ex.printStackTrace();
                }
            });
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

            CodeParser parser = new CodeParser();
            lastResult = parser.parse(formatted);

            engine = new ExecutionEngine(lastResult.flowNodes, lastResult.variables, lastResult.mockReturnValues);
            variablePanel.updateVariables(engine.getVariables());
            variablePanel.updateMockReturnValues(lastResult.mockReturnValues);
            flowChartView.drawFlow(lastResult.flowNodes, lastResult.flowEdges, lastResult.methodName);

            statusLabel.setText("Visualize complete. Nodes: " + lastResult.flowNodes.size());

        } catch (Exception ex) {
            engine = null;
            lastResult = null;
            variablePanel.clear();
            flowChartView.clear();
            statusLabel.setText("Analyze failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String formatCode(String code) {
        try {
            String wrapped = wrapForParsing(code);
            CompilationUnit cu = StaticJavaParser.parse(wrapped);
            Optional<MethodDeclaration> method = cu.findFirst(MethodDeclaration.class);
            if (method.isEmpty() || method.get().getBody().isEmpty()) return code;

            MethodDeclaration m = method.get();

            // If the user provided a method declaration, return the full formatted method
            if (!"temp".equals(m.getNameAsString())) {
                return m.toString();
            }

            // Bare code — extract and return just the body
            String body = m.getBody().get().toString();
            // Strip outer { }
            body = body.substring(body.indexOf('{') + 1, body.lastIndexOf('}')).stripTrailing();
            // Dedent one level (JavaParser indents with 4 spaces)
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
        if (code.contains("class")) return code;
        String first = code.trim().split("\\s+")[0];
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
        engine = null;
        lastResult = null;
        statusLabel.setText("Paste code and click Visualize.");
    }
}
