package com.codevisualizer.ui;

import com.codevisualizer.enums.NodeType;
import com.codevisualizer.model.FlowEdge;
import com.codevisualizer.model.FlowNode;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.util.Duration;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Text;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FlowChartView {

    private List<FlowNode> currentNodes;
    private List<FlowEdge> currentEdges;
    private String currentMethodName;
    private final Pane root = new Pane();
    private final Group contentGroup = new Group();

    private final Map<String, Shape> nodeShapes = new HashMap<>();
    private final Map<String, List<Line>> edgeLines = new HashMap<>();

    private double scale = 1.0;
    private double lastPanX;
    private double lastPanY;

    private static final double MIN_SCALE = 0.3;
    private static final double MAX_SCALE = 3.0;
    private static final double ZOOM_FACTOR = 1.1;
    private static final double FIT_PADDING = 40.0;

    private static final Logger logger = LoggerFactory.getLogger(FlowChartView.class);

    /*
        IMPORTANT - All shapes X & Y position start in upper left corner
     */

    public FlowChartView() {
        root.setPrefSize(1000, 1000);
        root.setStyle("-fx-background-color: white;");
        root.getChildren().add(contentGroup);

        setupZoom();
        setupPan();
    }

    public Pane getView() {
        return root;
    }

    public void clear() {
        contentGroup.getChildren().clear();
        nodeShapes.clear();
        edgeLines.clear();
        currentMethodName = null;
    }

    public void drawFlow(List<FlowNode> nodes, List<FlowEdge> edges, String methodName) {
        clear();
        this.currentNodes = nodes;
        this.currentEdges = edges;
        this.currentMethodName = methodName;
        logger.info("Starting to draw flow... ");

        if (methodName != null && !nodes.isEmpty()) {
            drawMethodBox(nodes, methodName);
        }

        for (FlowEdge edge : edges) {
            if (edge.toId == null) continue;

            FlowNode from = findNodeById(nodes, edge.fromId);
            FlowNode to = findNodeById(nodes, edge.toId);
            if (from == null || to == null) continue;

            List<Line> segments = drawEdge(from, to, edge.label);
            edgeLines.put(edge.fromId + "->" + edge.toId, segments);
            contentGroup.getChildren().addAll(segments);

            if (edge.label != null && !edge.label.isBlank()) {
                contentGroup.getChildren().add(createEdgeLabel(from, to, edge.label));
            }
        }

        for (FlowNode node : nodes) {
            if (node.type == NodeType.JOIN) continue;

            StackPane nodeContainer = new StackPane();
            nodeContainer.setLayoutX(node.x);
            nodeContainer.setLayoutY(node.y);
            nodeContainer.setPrefSize(node.width, node.height);

            // Shape
            Shape shape = createShape(node);
            shape.setFill(Color.WHITE);
            shape.setStroke(Color.BLACK);
            shape.setStrokeWidth(2);

            // Text (Label handles wrapping + alignment better than Text)
            Label label = new Label(node.label);
            label.setWrapText(true);
            label.setMaxWidth(node.width - 20);
            label.setAlignment(Pos.CENTER);
            label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

            // Center everything
            nodeContainer.setAlignment(Pos.CENTER);

            // Add children
            nodeContainer.getChildren().addAll(shape, label);

            // Add to scene
            contentGroup.getChildren().add(nodeContainer);

            // IMPORTANT: store shape for highlighting
            nodeShapes.put(node.id, shape);

            String lowerLabel = node.label.toLowerCase();
            if (lowerLabel.contains("save") || lowerLabel.contains("update") || lowerLabel.contains("delete")) {
                drawDatabaseSymbol(node);
            }
        }

        Platform.runLater(this::fitToScreen);
    }

    public void clearHighlights() {
        nodeShapes.values().forEach(shape -> {
            shape.setFill(Color.WHITE);
            shape.setStroke(Color.BLACK);
            shape.setStrokeWidth(2);
        });

        edgeLines.values().forEach(lines ->
                lines.forEach(line -> {
                    line.setStroke(Color.BLACK);
                    line.setStrokeWidth(2);
                })
        );
    }

    public void showEndOverlay(Runnable onComplete) {
        Rectangle dim = new Rectangle();
        dim.widthProperty().bind(root.widthProperty());
        dim.heightProperty().bind(root.heightProperty());
        dim.setFill(Color.color(0, 0, 0, 0.4));

        Label msg = new Label("End of Code Reached");
        msg.setStyle("-fx-font-size: 16; -fx-font-weight: bold; -fx-text-fill: white;");

        root.getChildren().addAll(dim, msg);

        Platform.runLater(() -> {
            msg.setLayoutX((root.getWidth() - msg.getWidth()) / 2);
            msg.setLayoutY(36);
        });

        PauseTransition pause = new PauseTransition(Duration.seconds(1));
        pause.setOnFinished(e -> {
            root.getChildren().removeAll(dim, msg);
            onComplete.run();
        });
        pause.play();
    }

    public void highlightNode(String nodeId) {
        Shape shape = nodeShapes.get(nodeId);
        if (shape != null) {
            shape.setFill(Color.YELLOW);
        }
    }

    public void highlightEdge(String fromId, String toId) {
        List<Line> lines = edgeLines.get(fromId + "->" + toId);
        if (lines != null) {
            lines.forEach(line -> {
                line.setStroke(Color.RED);
                line.setStrokeWidth(4);
            });
        }
    }

    public void resetView() {
        scale = 1.0;
        contentGroup.setScaleX(scale);
        contentGroup.setScaleY(scale);
        contentGroup.setTranslateX(0);
        contentGroup.setTranslateY(0);
    }

    public void fitToScreen() {
        if (contentGroup.getChildren().isEmpty()) {
            resetView();
            return;
        }

        double viewportWidth = root.getWidth() > 0 ? root.getWidth() : root.getPrefWidth();
        double viewportHeight = root.getHeight() > 0 ? root.getHeight() : root.getPrefHeight();

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }

        // Reset transforms first
        contentGroup.setScaleX(1.0);
        contentGroup.setScaleY(1.0);
        contentGroup.setTranslateX(0);
        contentGroup.setTranslateY(0);

        Bounds layoutBounds = contentGroup.getLayoutBounds();
        if (layoutBounds.getWidth() <= 0 || layoutBounds.getHeight() <= 0) {
            return;
        }

        double availableWidth = Math.max(1, viewportWidth - (FIT_PADDING * 2));
        double availableHeight = Math.max(1, viewportHeight - (FIT_PADDING * 2));

        double scaleX = availableWidth / layoutBounds.getWidth();
        double scaleY = availableHeight / layoutBounds.getHeight();

        scale = clamp(Math.min(scaleX, scaleY));

        // Apply scale first
        contentGroup.setScaleX(scale);
        contentGroup.setScaleY(scale);

        // Now center based on the ACTUAL rendered bounds after scaling
        Bounds scaledBounds = contentGroup.getBoundsInParent();

        double dx = (viewportWidth - scaledBounds.getWidth()) / 2 - scaledBounds.getMinX();
        double dy = (viewportHeight - scaledBounds.getHeight()) / 2 - scaledBounds.getMinY();

        contentGroup.setTranslateX(contentGroup.getTranslateX() + dx);
        contentGroup.setTranslateY(contentGroup.getTranslateY() + dy);
    }

    private void setupZoom() {
        root.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0) {
                return;
            }

            double oldScale = scale;
            double zoomMultiplier = event.getDeltaY() > 0 ? ZOOM_FACTOR : 1 / ZOOM_FACTOR;
            scale = clamp(scale * zoomMultiplier);

            if (Math.abs(scale - oldScale) < 0.0001) {
                return;
            }

            double mouseX = event.getX();
            double mouseY = event.getY();

            Bounds bounds = contentGroup.getBoundsInParent();
            double dx = mouseX - bounds.getMinX();
            double dy = mouseY - bounds.getMinY();
            double f = scale / oldScale - 1;

            contentGroup.setScaleX(scale);
            contentGroup.setScaleY(scale);

            contentGroup.setTranslateX(contentGroup.getTranslateX() - f * dx);
            contentGroup.setTranslateY(contentGroup.getTranslateY() - f * dy);

            event.consume();
        });
    }

    private void setupPan() {
        root.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }

            lastPanX = event.getSceneX();
            lastPanY = event.getSceneY();
        });

        root.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }

            double deltaX = event.getSceneX() - lastPanX;
            double deltaY = event.getSceneY() - lastPanY;

            contentGroup.setTranslateX(contentGroup.getTranslateX() + deltaX);
            contentGroup.setTranslateY(contentGroup.getTranslateY() + deltaY);

            lastPanX = event.getSceneX();
            lastPanY = event.getSceneY();
        });
    }

    private void drawDatabaseSymbol(FlowNode node) {
        double midY   = node.y + node.height / 2.0;
        double lineX0 = node.x + node.width;
        double lineX1 = lineX0 + 16;
        double dbW    = 70;
        double dbH    = 54;
        double ry     = 9;   // ellipse cap half-height
        double dbTopY = midY - dbH / 2.0;
        double dbCX   = lineX1 + dbW / 2.0;

        Line connector = new Line(lineX0, midY, lineX1, midY);
        connector.setStroke(Color.BLACK);
        connector.setStrokeWidth(1.5);

        // Body fill (white, no stroke — sides drawn as lines)
        Rectangle body = new Rectangle(lineX1, dbTopY + ry, dbW, dbH - 2 * ry);
        body.setFill(Color.WHITE);
        body.setStroke(Color.TRANSPARENT);

        Line leftSide  = new Line(lineX1,      dbTopY + ry, lineX1,      dbTopY + dbH - ry);
        Line rightSide = new Line(lineX1 + dbW, dbTopY + ry, lineX1 + dbW, dbTopY + dbH - ry);
        leftSide.setStroke(Color.BLACK);   leftSide.setStrokeWidth(1.5);
        rightSide.setStroke(Color.BLACK);  rightSide.setStrokeWidth(1.5);

        Ellipse topCap = new Ellipse(dbCX, dbTopY + ry, dbW / 2.0, ry);
        topCap.setFill(Color.WHITE);
        topCap.setStroke(Color.BLACK);
        topCap.setStrokeWidth(1.5);

        Arc bottomArc = new Arc(dbCX, dbTopY + dbH - ry, dbW / 2.0, ry, 0, -180);
        bottomArc.setType(ArcType.OPEN);
        bottomArc.setFill(Color.TRANSPARENT);
        bottomArc.setStroke(Color.BLACK);
        bottomArc.setStrokeWidth(1.5);

        Text dbLabel = new Text("Database");
        dbLabel.setStyle("-fx-font-size: 11;");
        dbLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        dbLabel.setWrappingWidth(dbW);
        dbLabel.setX(lineX1);
        dbLabel.setY(dbTopY + ry + (dbH - 2 * ry) / 2.0 + 5);

        contentGroup.getChildren().addAll(connector, body, leftSide, rightSide, topCap, bottomArc, dbLabel);
    }

    private void drawMethodBox(List<FlowNode> nodes, String methodName) {
        double pad = 24;
        double minX = nodes.stream().mapToDouble(n -> n.x).min().orElse(0) - pad;
        double minY = nodes.stream().mapToDouble(n -> n.y).min().orElse(0) - pad - 16; // extra room for label
        double maxX = nodes.stream().mapToDouble(n -> n.x + n.width).max().orElse(0) + pad;
        double maxY = nodes.stream().mapToDouble(n -> n.y + n.height).max().orElse(0) + pad;

        Rectangle box = new Rectangle(minX, minY, maxX - minX, maxY - minY);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.DARKGRAY);
        box.setStrokeWidth(1.5);
        box.getStrokeDashArray().addAll(8.0, 5.0);

        Text label = new Text(minX + 8, minY + 14, methodName + "()");
        label.setFill(Color.DARKGRAY);

        contentGroup.getChildren().addAll(box, label);
    }

    private List<Line> drawEdge(FlowNode from, FlowNode to, String label) {
        logger.info("Drawing connecting LINE from [{}] to [{}], fromType:{} - toType:{} | with Line Label: {}",
                from.label, to.label, from.type, to.type, label);
        List<Line> lines = new ArrayList<>();

        if (from.type == NodeType.DECISION) {

            double startY = from.y + (from.height / 2);

            if ("True".equalsIgnoreCase(label) || "Yes".equalsIgnoreCase(label)) {
                logger.info("Draw DECISION Line RIGHT");
                double startX = from.x + from.width;
                double endX = to.x + (to.width / 2);
                double endY = to.y;

                lines.add(createLine(startX, startY, endX, startY));
                lines.add(createLine(endX, startY, endX, endY));
                return lines;
            }

            if ("False".equalsIgnoreCase(label) || "No".equalsIgnoreCase(label)) {
                logger.info("Draw DECISION Line DOWN");
                double startX = from.x + (from.width / 2);
                double startY2 = from.y + from.height;
                double endX = to.x + (to.width / 2);
                double endY = to.y;

                if (Math.abs(startX - endX) < 0.5) {
                    lines.add(createLine(startX, startY2, endX, endY));
                } else {
                    double midY = (startY2 + endY) / 2;
                    lines.add(createLine(startX, startY2, startX, midY));
                    lines.add(createLine(startX, midY, endX, midY));
                    lines.add(createLine(endX, midY, endX, endY));
                }
                return lines;
            }
        }

        if (to.type == NodeType.JOIN) {
            logger.info("Draw JOIN Line");
            double startX = from.x + (from.width / 2);
            double startY = from.y + from.height;
            double endX = to.x + (to.width / 2);
            double endY = to.y;

            lines.add(createLine(startX, startY, startX, endY));
            lines.add(createLine(startX, endY, endX, endY));
            return lines;
        }

        double fromX = from.x + (from.width / 2); // Set X to Middle of Shape
        double fromY = from.y + from.height; // Set Y to Bottom of Shape
        double toX = to.x + (to.width / 2); // Set x to Middle of Shape
        double toY = to.y; // Set Y to Top of Shape

        // If FROM Shape and TO Shape are already aligned vertically - Create ONE line ONLY
        if (Math.abs(fromX - toX) < 0.5) {
            logger.info("Draw SINGLE PROCESS Line - FROM x:{} y:{} - TO x:{} y:{}", fromX, fromY, toX, toY);
            lines.add(createLine(fromX, fromY, toX, toY));
            return lines;
        }

        // FROM Shape is NOT vertically aligned - create multiple connected lines with right angles
        double midY = fromY + ((toY - fromY) * 0.5); // Set Mid-way between two shapes
        logger.info("Draw MULTIPLE PROCESS Line - FROM x:{} y:{} - TO x:{} y:{} - MID y:{}",
                fromX, fromY, toX, toY, midY);
        lines.add(createLine(fromX, fromY, fromX, midY)); // Vertical Line - 1st
        lines.add(createLine(fromX, midY, toX, midY)); // Horizontal Line - 2nd
        lines.add(createLine(toX, midY, toX, toY)); // Vertical Line - 3rd
        return lines;
    }

    private Line createLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Color.BLACK);
        line.setStrokeWidth(2);
        return line;
    }

    private Text createEdgeLabel(FlowNode from, FlowNode to, String label) {
        Text text = new Text(label);

        double x;
        double y;

        if (from.type == NodeType.DECISION) {
            if ("True".equalsIgnoreCase(label) || "Yes".equalsIgnoreCase(label)) {
                x = from.x + from.width + 5;
                y = from.y + (from.height / 2) - 8;
            } else if ("False".equalsIgnoreCase(label) || "No".equalsIgnoreCase(label)) {
                x = from.x + (from.width / 2) + 8;
                y = from.y + from.height + 16;
            } else {
                x = (from.x + to.x) / 2;
                y = (from.y + to.y) / 2;
            }
        } else {
            x = ((from.x + from.width / 2) + (to.x + to.width / 2)) / 2 + 5;
            y = from.y + from.height + 15;
        }

        text.setX(x);
        text.setY(y);
        return text;
    }

    private Shape createShape(FlowNode node) {
        if (node.type == NodeType.DECISION) {
            return new Polygon(
                    node.x + (node.width / 2), node.y,
                    node.x + node.width, node.y + (node.height / 2),
                    node.x + (node.width / 2), node.y + node.height,
                    node.x, node.y + (node.height / 2)
            );
        }

        return new Rectangle(node.x, node.y, node.width, node.height);
    }

    public FlowNode findNodeById(List<FlowNode> nodes, String id) {
        return nodes.stream()
                .filter(n -> n.id.equals(id))
                .findFirst()
                .orElse(null);
    }

    private double clamp(double value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    public String generateDrawIOXML() {
        if (currentNodes == null || currentEdges == null) return "";

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<mxfile host=\"app.diagrams.net\">\n");
        xml.append("  <diagram name=\"Page-1\">\n");
        xml.append("    <mxGraphModel>\n");
        xml.append("      <root>\n");
        xml.append("        <mxCell id=\"0\"/>\n");
        xml.append("        <mxCell id=\"1\" parent=\"0\"/>\n");

        // ── Method box (dashed) — added first so it renders behind nodes ──
        if (currentMethodName != null && !currentNodes.isEmpty()) {
            double pad = 24;
            double minX = currentNodes.stream().mapToDouble(n -> n.x).min().orElse(0) - pad;
            double minY = currentNodes.stream().mapToDouble(n -> n.y).min().orElse(0) - pad - 16;
            double maxX = currentNodes.stream().mapToDouble(n -> n.x + n.width).max().orElse(0) + pad;
            double maxY = currentNodes.stream().mapToDouble(n -> n.y + n.height).max().orElse(0) + pad;
            xml.append("        <mxCell id=\"method-box\" value=\"")
                    .append(xmlEscape(currentMethodName + "()"))
                    .append("\" style=\"rounded=0;whiteSpace=wrap;html=1;fillColor=none;")
                    .append("strokeColor=#888888;dashed=1;dashPattern=8 5;")
                    .append("verticalAlign=top;align=left;fontSize=12;fontColor=#888888;spacingLeft=6;")
                    .append("\" vertex=\"1\" parent=\"1\">\n");
            xml.append("          <mxGeometry x=\"").append(minX)
                    .append("\" y=\"").append(minY)
                    .append("\" width=\"").append(maxX - minX)
                    .append("\" height=\"").append(maxY - minY)
                    .append("\" as=\"geometry\"/>\n");
            xml.append("        </mxCell>\n");
        }

        // ── Nodes + database symbols ──────────────────────────────────────
        int dbIdx = 0;
        for (FlowNode node : currentNodes) {
            String nodeStyle = node.type == NodeType.DECISION
                    ? "rhombus;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;"
                    : "rounded=0;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";

            xml.append("        <mxCell id=\"").append(node.id)
                    .append("\" value=\"").append(xmlEscape(node.label))
                    .append("\" style=\"").append(nodeStyle)
                    .append("\" vertex=\"1\" parent=\"1\">\n");
            xml.append("          <mxGeometry x=\"").append(node.x)
                    .append("\" y=\"").append(node.y)
                    .append("\" width=\"").append(node.width)
                    .append("\" height=\"").append(node.height)
                    .append("\" as=\"geometry\"/>\n");
            xml.append("        </mxCell>\n");

            String lower = node.label.toLowerCase();
            if (lower.contains("save") || lower.contains("update") || lower.contains("delete")) {
                double dbW = 70, dbH = 54;
                double dbX = node.x + node.width + 16;
                double dbY = node.y + node.height / 2.0 - dbH / 2.0;
                String dbId = "db-" + dbIdx;

                xml.append("        <mxCell id=\"").append(dbId)
                        .append("\" value=\"Database\" style=\"shape=cylinder;whiteSpace=wrap;html=1;")
                        .append("fillColor=#ffffff;strokeColor=#000000;fontSize=11;verticalAlign=middle;")
                        .append("\" vertex=\"1\" parent=\"1\">\n");
                xml.append("          <mxGeometry x=\"").append(dbX)
                        .append("\" y=\"").append(dbY)
                        .append("\" width=\"").append(dbW)
                        .append("\" height=\"").append(dbH)
                        .append("\" as=\"geometry\"/>\n");
                xml.append("        </mxCell>\n");

                xml.append("        <mxCell id=\"db-conn-").append(dbIdx++)
                        .append("\" value=\"\" style=\"endArrow=none;html=1;")
                        .append("exitX=1;exitY=0.5;exitDx=0;exitDy=0;")
                        .append("entryX=0;entryY=0.5;entryDx=0;entryDy=0;")
                        .append("\" edge=\"1\" source=\"").append(node.id)
                        .append("\" target=\"").append(dbId)
                        .append("\" parent=\"1\">\n");
                xml.append("          <mxGeometry relative=\"1\" as=\"geometry\"/>\n");
                xml.append("        </mxCell>\n");
            }
        }

        // ── Edges ─────────────────────────────────────────────────────────
        for (FlowEdge edge : currentEdges) {
            FlowNode from = currentNodes.stream().filter(n -> n.id.equals(edge.fromId)).findFirst().orElse(null);
            FlowNode to   = currentNodes.stream().filter(n -> n.id.equals(edge.toId)).findFirst().orElse(null);
            if (from == null || to == null) continue;

            String label = edge.label != null ? edge.label : "";
            String exitPoint  = (from.type == NodeType.DECISION && "True".equalsIgnoreCase(label))
                    ? "exitX=1;exitY=0.5;exitDx=0;exitDy=0;"
                    : "exitX=0.5;exitY=1;exitDx=0;exitDy=0;";
            String entryPoint = "entryX=0.5;entryY=0;entryDx=0;entryDy=0;";

            xml.append("        <mxCell id=\"").append(edge.key())
                    .append("\" value=\"").append(xmlEscape(label))
                    .append("\" style=\"edgeStyle=orthogonalEdgeStyle;")
                    .append(exitPoint).append(entryPoint)
                    .append("rounded=0;orthogonalLoop=1;html=1;\"")
                    .append(" source=\"").append(edge.fromId)
                    .append("\" target=\"").append(edge.toId)
                    .append("\" edge=\"1\" parent=\"1\">\n");
            xml.append("          <mxGeometry relative=\"1\" as=\"geometry\"/>\n");
            xml.append("        </mxCell>\n");
        }

        xml.append("      </root>\n");
        xml.append("    </mxGraphModel>\n");
        xml.append("  </diagram>\n");
        xml.append("</mxfile>");
        return xml.toString();
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }


}