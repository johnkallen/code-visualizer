package com.codevisualizer.ui;

import com.codevisualizer.enums.NodeType;
import com.codevisualizer.model.FlowEdge;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.model.MethodBox;
import com.codevisualizer.model.StreamGroup;
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
    private List<StreamGroup> currentStreamGroups = new ArrayList<>();
    private List<MethodBox> currentMethodGroups = new ArrayList<>();
    private String currentMethodName;
    private String currentClassName;
    private final Pane root = new Pane();
    private final Group contentGroup = new Group();

    private final Map<String, Shape> nodeShapes = new HashMap<>();
    private final Map<String, List<Line>> edgeLines = new HashMap<>();
    private final Map<String, List<Shape>> dbSymbolShapes = new HashMap<>();

    // Routing data computed in drawFlow; reused by generateDrawIOXML for exact waypoints.
    private Map<String, Double>  lastEdgeBusX          = new HashMap<>();
    private Map<String, Double>  lastEdgeMergeY         = new HashMap<>();
    private Map<String, Boolean> lastEdgeSingleJoinBack = new HashMap<>();

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
        dbSymbolShapes.clear();
        currentStreamGroups.clear();
        currentMethodGroups.clear();
        currentMethodName = null;
        currentClassName  = null;
        lastEdgeBusX.clear();
        lastEdgeMergeY.clear();
        lastEdgeSingleJoinBack.clear();
    }

    public void drawFlow(List<FlowNode> nodes, List<FlowEdge> edges, String methodName,
                         List<StreamGroup> streamGroups, List<MethodBox> methodGroups,
                         String className) {
        clear();
        this.currentNodes = nodes;
        this.currentEdges = edges;
        this.currentMethodName = methodName;
        this.currentStreamGroups = streamGroups != null ? streamGroups : new ArrayList<>();
        this.currentMethodGroups = methodGroups != null ? methodGroups : new ArrayList<>();
        this.currentClassName = className;
        logger.info("Starting to draw flow... ");

        // ── Routing pre-computation (must precede box drawing so boxes enclose all lines) ──
        // In "All Methods" mode routing is computed independently per method box so that
        // join-back lines from one method never stray into a neighbouring method.

        final double STAGGER_STEP = 20.0;

        // These maps are keyed by "fromId->toId" for every join-back edge.
        Map<String, Integer> staggerRanks        = new HashMap<>();
        Map<String, Double>  perEdgeMergeY        = new HashMap<>();
        Map<String, Double>  perEdgeBusX          = new HashMap<>(); // final staggered bus X per edge
        Map<String, Boolean> perEdgeSingleJoinBack = new HashMap<>();
        double routingMaxX = 0;

        if (!currentMethodGroups.isEmpty()) {
            // ── All Methods: compute routing independently per method box ──
            for (MethodBox mb : currentMethodGroups) {
                List<FlowNode> mbNodes = nodes.stream()
                        .filter(n -> {
                            double cx = n.x + n.width / 2.0, cy = n.y + n.height / 2.0;
                            return cx >= mb.x && cx <= mb.x + mb.width
                                    && cy >= mb.y && cy <= mb.y + mb.height;
                        }).toList();
                Set<String> mbIds = new HashSet<>();
                mbNodes.forEach(n -> mbIds.add(n.id));
                List<FlowEdge> mbEdges = edges.stream()
                        .filter(e -> mbIds.contains(e.fromId)).toList();

                double mbBusX = mbNodes.stream()
                        .filter(n -> n.type != NodeType.JOIN)
                        .mapToDouble(n -> n.x + n.width)
                        .max().orElse(mb.x + mb.width) + 20;

                List<String> mbJoinBackIds = mbEdges.stream()
                        .filter(e -> {
                            FlowNode f = findNodeById(mbNodes, e.fromId);
                            FlowNode t = findNodeById(mbNodes, e.toId);
                            if (f == null || t == null) return false;
                            return (f.x + f.width / 2.0) > (t.x + t.width / 2.0) + 20
                                    && t.y >= f.y - 5;
                        })
                        .map(e -> e.fromId).distinct()
                        .sorted(Comparator.comparingDouble(id -> {
                            FlowNode f = findNodeById(mbNodes, id);
                            return f != null ? f.y : 0;
                        })).toList();

                Map<String, Integer> mbRanks = new HashMap<>();
                int mbN = mbJoinBackIds.size();
                for (int i = 0; i < mbN; i++) mbRanks.put(mbJoinBackIds.get(i), mbN - 1 - i);
                staggerRanks.putAll(mbRanks);

                Set<String> mbJoinBackSet = new HashSet<>(mbJoinBackIds);
                boolean mbSingle = mbJoinBackIds.size() <= 1;

                for (FlowEdge e : mbEdges) {
                    if (!mbJoinBackSet.contains(e.fromId) || e.toId == null) continue;
                    String key = e.fromId + "->" + e.toId;
                    double edgeBusX = mbBusX + mbRanks.getOrDefault(e.fromId, 0) * STAGGER_STEP;
                    perEdgeBusX.put(key, edgeBusX);
                    perEdgeSingleJoinBack.put(key, mbSingle);

                    FlowNode src = findNodeById(mbNodes, e.fromId);
                    FlowNode tgt = findNodeById(mbNodes, e.toId);
                    if (src == null || tgt == null) continue;
                    double srcBottom = src.y + src.height, tgtTop = tgt.y;
                    double localMax = mbNodes.stream()
                            .filter(n -> !n.id.equals(src.id) && !n.id.equals(tgt.id)
                                    && n.type != NodeType.JOIN
                                    && (n.x + n.width) < src.x
                                    && (n.y + n.height) > srcBottom - 5
                                    && n.y < tgtTop)
                            .mapToDouble(n -> n.y + n.height).max().orElse(srcBottom);
                    perEdgeMergeY.put(key,
                            Math.min(Math.max(localMax + 20, srcBottom + 20), tgtTop - 5));
                }

                double mbMaxBusX = mbJoinBackIds.isEmpty() ? 0
                        : mbBusX + mbRanks.values().stream()
                                .mapToInt(Integer::intValue).max().orElse(0) * STAGGER_STEP;
                routingMaxX = Math.max(routingMaxX, mbMaxBusX);
            }

        } else {
            // ── Single method: compute routing globally ──
            double busX = nodes.stream()
                    .filter(n -> n.type != NodeType.JOIN)
                    .mapToDouble(n -> n.x + n.width)
                    .max().orElse(600.0) + 20;

            List<String> joinBackIds = edges.stream()
                    .filter(e -> {
                        FlowNode f = findNodeById(nodes, e.fromId);
                        FlowNode t = findNodeById(nodes, e.toId);
                        if (f == null || t == null) return false;
                        return (f.x + f.width / 2.0) > (t.x + t.width / 2.0) + 20
                                && t.y >= f.y - 5;
                    })
                    .map(e -> e.fromId).distinct()
                    .sorted(Comparator.comparingDouble(id -> {
                        FlowNode f = findNodeById(nodes, id);
                        return f != null ? f.y : 0;
                    })).toList();

            int n = joinBackIds.size();
            for (int i = 0; i < n; i++) staggerRanks.put(joinBackIds.get(i), n - 1 - i);

            routingMaxX = staggerRanks.isEmpty() ? 0
                    : busX + staggerRanks.values().stream()
                            .mapToInt(Integer::intValue).max().orElse(0) * STAGGER_STEP;

            Set<String> joinBackSourceIds = staggerRanks.keySet();
            boolean globalSingle = staggerRanks.size() <= 1;
            for (FlowEdge e : edges) {
                if (!joinBackSourceIds.contains(e.fromId) || e.toId == null) continue;
                String key = e.fromId + "->" + e.toId;
                double edgeBusX = busX + staggerRanks.getOrDefault(e.fromId, 0) * STAGGER_STEP;
                perEdgeBusX.put(key, edgeBusX);
                perEdgeSingleJoinBack.put(key, globalSingle);

                FlowNode src = findNodeById(nodes, e.fromId);
                FlowNode tgt = findNodeById(nodes, e.toId);
                if (src == null || tgt == null) continue;
                double srcBottom = src.y + src.height, tgtTop = tgt.y;
                double localMax = nodes.stream()
                        .filter(nn -> !nn.id.equals(src.id) && !nn.id.equals(tgt.id)
                                && nn.type != NodeType.JOIN
                                && (nn.x + nn.width) < src.x
                                && (nn.y + nn.height) > srcBottom - 5
                                && nn.y < tgtTop)
                        .mapToDouble(nn -> nn.y + nn.height).max().orElse(srcBottom);
                perEdgeMergeY.put(key,
                        Math.min(Math.max(localMax + 20, srcBottom + 20), tgtTop - 5));
            }
        }

        // Persist routing data so generateDrawIOXML can produce matching waypoints.
        lastEdgeBusX          = perEdgeBusX;
        lastEdgeMergeY        = perEdgeMergeY;
        lastEdgeSingleJoinBack = perEdgeSingleJoinBack;

        // ── Draw method / group bounding boxes (now that routingMaxX is known) ──

        // Class-level enclosing box — drawn first so it sits behind all method boxes
        if (!currentMethodGroups.isEmpty() && currentClassName != null) {
            double clsMinX = Double.MAX_VALUE, clsMinY = Double.MAX_VALUE;
            double clsMaxX = 0, clsMaxY = 0;
            for (MethodBox mb : currentMethodGroups) {
                double mbRoutingMaxX = edges.stream()
                        .filter(e -> perEdgeBusX.containsKey(e.fromId + "->" + e.toId))
                        .filter(e -> {
                            FlowNode f = findNodeById(nodes, e.fromId);
                            if (f == null) return false;
                            double cx = f.x + f.width / 2.0, cy = f.y + f.height / 2.0;
                            return cx >= mb.x && cx <= mb.x + mb.width
                                    && cy >= mb.y && cy <= mb.y + mb.height;
                        })
                        .mapToDouble(e -> perEdgeBusX.get(e.fromId + "->" + e.toId))
                        .max().orElse(0);
                double right = Math.max(mb.x + mb.width, mbRoutingMaxX > 0 ? mbRoutingMaxX + 20 : 0);
                clsMinX = Math.min(clsMinX, mb.x);
                clsMinY = Math.min(clsMinY, mb.y);
                clsMaxX = Math.max(clsMaxX, right);
                clsMaxY = Math.max(clsMaxY, mb.y + mb.height);
            }
            final double CLS_PAD = 30;
            drawClassBox(clsMinX - CLS_PAD, clsMinY - CLS_PAD - 24,
                    clsMaxX - clsMinX + 2 * CLS_PAD,
                    clsMaxY - clsMinY + 2 * CLS_PAD + 24,
                    currentClassName);
        }

        if (!currentMethodGroups.isEmpty()) {
            for (MethodBox mb : currentMethodGroups) {
                // Max staggered bus X for join-back edges whose source is inside this box.
                double methodRoutingMaxX = edges.stream()
                        .filter(e -> perEdgeBusX.containsKey(e.fromId + "->" + e.toId))
                        .filter(e -> {
                            FlowNode f = findNodeById(nodes, e.fromId);
                            if (f == null) return false;
                            double cx = f.x + f.width / 2.0, cy = f.y + f.height / 2.0;
                            return cx >= mb.x && cx <= mb.x + mb.width
                                    && cy >= mb.y && cy <= mb.y + mb.height;
                        })
                        .mapToDouble(e -> perEdgeBusX.get(e.fromId + "->" + e.toId))
                        .max().orElse(0);
                drawMethodGroupBox(mb, methodRoutingMaxX);
            }
        } else if (methodName != null && !nodes.isEmpty()) {
            drawMethodBox(nodes, methodName, currentStreamGroups, routingMaxX);
        }

        for (StreamGroup sg : currentStreamGroups) {
            drawStreamGroupBox(sg);
        }

        for (FlowEdge edge : edges) {
            if (edge.toId == null) continue;

            FlowNode from = findNodeById(nodes, edge.fromId);
            FlowNode to = findNodeById(nodes, edge.toId);
            if (from == null || to == null) continue;

            String edgeKey = edge.fromId + "->" + edge.toId;
            double edgeBusX        = perEdgeBusX.getOrDefault(edgeKey, 0.0);
            double edgeMergeY      = perEdgeMergeY.getOrDefault(edgeKey, 0.0);
            boolean singleJoinBack = perEdgeSingleJoinBack.getOrDefault(edgeKey, true);
            List<Line> segments = drawEdge(from, to, edge.label, edge.isBackEdge,
                    edgeBusX, edgeMergeY, singleJoinBack);
            edgeLines.put(edge.fromId + "->" + edge.toId, segments);
            contentGroup.getChildren().addAll(segments);

            // Show label unless: it's a LOOP exit (handled visually by the routing),
            // or it's a back-edge from a non-DECISION node (PROCESS back-edges are self-evident).
            boolean showLabel = edge.label != null && !edge.label.isBlank()
                    && from.type != NodeType.LOOP
                    && (!edge.isBackEdge || from.type == NodeType.DECISION);
            if (showLabel) {
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

        dbSymbolShapes.values().forEach(this::resetDbShapes);
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
        highlightDbSymbol(fromId);
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
        dbSymbolShapes.put(node.id, List.of(connector, body, leftSide, rightSide, topCap, bottomArc));
    }

    private void highlightDbSymbol(String nodeId) {
        List<Shape> shapes = dbSymbolShapes.get(nodeId);
        if (shapes == null) return;
        for (Shape s : shapes) {
            if (s instanceof Rectangle) {
                s.setFill(Color.web("#FFF3CD"));
            } else if (s instanceof Ellipse) {
                s.setFill(Color.web("#FFF3CD"));
                s.setStroke(Color.ORANGE);
                s.setStrokeWidth(1.5);
            } else {
                s.setStroke(Color.ORANGE);
                s.setStrokeWidth(1.5);
            }
        }
    }

    private void resetDbShapes(List<Shape> shapes) {
        for (Shape s : shapes) {
            if (s instanceof Rectangle) {
                s.setFill(Color.WHITE);
                s.setStroke(Color.TRANSPARENT);
            } else if (s instanceof Ellipse) {
                s.setFill(Color.WHITE);
                s.setStroke(Color.BLACK);
                s.setStrokeWidth(1.5);
            } else if (s instanceof Arc) {
                s.setFill(Color.TRANSPARENT);
                s.setStroke(Color.BLACK);
                s.setStrokeWidth(1.5);
            } else {
                s.setStroke(Color.BLACK);
                s.setStrokeWidth(1.5);
            }
        }
    }

    private void drawMethodGroupBox(MethodBox mb, double routingMaxX) {
        final double PAD = 20;
        double right = Math.max(mb.x + mb.width, routingMaxX + PAD);
        Rectangle box = new Rectangle(mb.x, mb.y, right - mb.x, mb.height);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.DARKGRAY);
        box.setStrokeWidth(1.5);
        box.getStrokeDashArray().addAll(8.0, 5.0);

        Text label = new Text(mb.x + 8, mb.y + 14, mb.name + "()");
        label.setFill(Color.DARKGRAY);

        contentGroup.getChildren().addAll(box, label);
    }

    private void drawClassBox(double x, double y, double width, double height, String className) {
        Rectangle box = new Rectangle(x, y, width, height);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.web("#1a1a2e"));
        box.setStrokeWidth(2.5);

        Text label = new Text(className);
        label.setStyle("-fx-font-size: 15; -fx-font-weight: bold;");
        label.setFill(Color.web("#1a1a2e"));
        // Center the label on the top edge; measure before adding to scene
        double textW = label.getLayoutBounds().getWidth();
        label.setX(x + (width - textW) / 2);
        label.setY(y + 18);

        contentGroup.getChildren().addAll(box, label);
    }

    private void drawStreamGroupBox(StreamGroup sg) {
        Rectangle box = new Rectangle(sg.x, sg.y, sg.width, sg.height);
        box.setFill(Color.web("#EEF4FF"));
        box.setStroke(Color.STEELBLUE);
        box.setStrokeWidth(1.5);
        box.getStrokeDashArray().addAll(6.0, 4.0);

        Text label = new Text(sg.x + 6, sg.y + 13, "stream");
        label.setFill(Color.STEELBLUE);
        label.setStyle("-fx-font-size: 10;");

        contentGroup.getChildren().addAll(box, label);
    }

    private void drawMethodBox(List<FlowNode> nodes, String methodName, List<StreamGroup> streamGroups,
                               double routingMaxX) {
        double pad = 24;
        double minX = nodes.stream().mapToDouble(n -> n.x).min().orElse(0) - pad;
        double minY = nodes.stream().mapToDouble(n -> n.y).min().orElse(0) - pad - 16; // extra room for label
        double maxX = nodes.stream().mapToDouble(n -> n.x + n.width).max().orElse(0) + pad;
        double maxY = nodes.stream().mapToDouble(n -> n.y + n.height).max().orElse(0) + pad;

        // Expand to contain staggered routing lines that extend beyond the rightmost node.
        maxX = Math.max(maxX, routingMaxX + pad);

        // Expand to contain any stream group boxes (which extend beyond node bounds)
        for (StreamGroup sg : streamGroups) {
            minX = Math.min(minX, sg.x - 8);
            maxX = Math.max(maxX, sg.x + sg.width + 8);
            minY = Math.min(minY, sg.y - 8);
            maxY = Math.max(maxY, sg.y + sg.height + 8);
        }

        Rectangle box = new Rectangle(minX, minY, maxX - minX, maxY - minY);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.DARKGRAY);
        box.setStrokeWidth(1.5);
        box.getStrokeDashArray().addAll(8.0, 5.0);

        Text label = new Text(minX + 8, minY + 14, methodName + "()");
        label.setFill(Color.DARKGRAY);

        contentGroup.getChildren().addAll(box, label);
    }

    private List<Line> drawEdge(FlowNode from, FlowNode to, String label, boolean isBackEdge,
                                double busX, double mergeY, boolean singleJoinBack) {
        logger.info("Drawing connecting LINE from [{}] to [{}], fromType:{} - toType:{} | with Line Label: {}",
                from.label, to.label, from.type, to.type, label);
        List<Line> lines = new ArrayList<>();

        // Back-edge: goes LEFT then UP then RIGHT into the loop header.
        // DECISION back-edges stay inner (closer to nodes); PROCESS back-edges go further out.
        if (isBackEdge) {
            double detourX  = from.type == NodeType.DECISION
                    ? from.x - 30
                    : from.x - 55;
            double fromMidY = from.y + from.height / 2.0;
            double toMidY   = to.y   + to.height   / 2.0;
            lines.add(createLine(from.x,   fromMidY, detourX, fromMidY));
            lines.add(createLine(detourX,  fromMidY, detourX, toMidY));
            lines.add(createLine(detourX,  toMidY,   to.x,    toMidY));
            return lines;
        }

        // LOOP node false-exit: detour RIGHT, enter terminal from its right side at mid-height
        if (from.type == NodeType.LOOP && "False".equalsIgnoreCase(label)) {
            double startX  = from.x + from.width;
            double startY  = from.y + from.height / 2.0;
            double detourX = from.x + from.width + 70;
            double endX    = to.x + to.width;           // right edge of terminal
            double endY    = to.y + to.height / 2.0;    // mid-height of terminal
            lines.add(createLine(startX,  startY, detourX, startY));
            lines.add(createLine(detourX, startY, detourX, endY));
            lines.add(createLine(detourX, endY,   endX,    endY));
            return lines;
        }

        if (from.type == NodeType.DECISION) {

            double startY = from.y + (from.height / 2);

            if ("True".equalsIgnoreCase(label) || "Yes".equalsIgnoreCase(label)) {
                double fromCX = from.x + from.width / 2.0;
                double toCX   = to.x   + to.width   / 2.0;
                if (toCX > fromCX + 10) {
                    double startX = from.x + from.width;
                    if (Math.abs(to.y - from.y) < 5) {
                        // Same Y level: pure horizontal from diamond right edge to box left edge
                        logger.info("Draw DECISION Line RIGHT (same-level)");
                        lines.add(createLine(startX, startY, to.x, startY));
                    } else {
                        // Box below decision: go RIGHT then DOWN into box top
                        logger.info("Draw DECISION Line RIGHT then DOWN");
                        lines.add(createLine(startX, startY, toCX, startY));
                        lines.add(createLine(toCX, startY, toCX, to.y));
                    }
                } else {
                    // Target is directly below (stream filter pass): go straight DOWN
                    logger.info("Draw DECISION True Line DOWN");
                    double fromX = from.x + from.width / 2.0;
                    double fromY = from.y + from.height;
                    if (Math.abs(fromX - toCX) < 0.5) {
                        lines.add(createLine(fromX, fromY, toCX, to.y));
                    } else {
                        double midY = (fromY + to.y) / 2.0;
                        lines.add(createLine(fromX, fromY, fromX, midY));
                        lines.add(createLine(fromX, midY, toCX, midY));
                        lines.add(createLine(toCX, midY, toCX, to.y));
                    }
                }
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

        double fromX = from.x + (from.width / 2);
        double fromY = from.y + from.height;
        double toX = to.x + (to.width / 2);
        double toY = to.y;

        // Vertically aligned — single straight line
        if (Math.abs(fromX - toX) < 0.5) {
            logger.info("Draw SINGLE PROCESS Line - FROM x:{} y:{} - TO x:{} y:{}", fromX, fromY, toX, toY);
            lines.add(createLine(fromX, fromY, toX, toY));
            return lines;
        }

        // Right-to-left downward: source is in a right-side branch column, target is in the main
        // column below. Two sub-cases:
        //   • Node has a database symbol on its RIGHT side → exit from BOTTOM center to avoid
        //     the routing line visually merging with the db connector.
        //   • Otherwise → staggered right-side bus routing.
        if (fromX > toX + 20 && toY >= fromY - 5) {
            double effectiveMergeY = mergeY > fromY ? mergeY : (fromY + toY) / 2.0;
            String lbl = from.label.toLowerCase();
            boolean hasDbSymbol = lbl.contains("save") || lbl.contains("update") || lbl.contains("delete");

            if (hasDbSymbol || singleJoinBack) {
                logger.info("Draw BOTTOM-ROUTE Line - FROM x:{} y:{} merge y:{} TO x:{} y:{}",
                        fromX, fromY, effectiveMergeY, toX, toY);
                lines.add(createLine(fromX, fromY, fromX, effectiveMergeY));         // ↓ from bottom center
                lines.add(createLine(fromX, effectiveMergeY, toX, effectiveMergeY)); // ← to target center
                lines.add(createLine(toX, effectiveMergeY, toX, toY));               // ↓ into target
            } else {
                logger.info("Draw BUS-ROUTE Line - FROM x:{} y:{} via bus x:{} merge y:{} TO x:{} y:{}",
                        fromX, fromY, busX, mergeY, toX, toY);
                double fromMidY = from.y + from.height / 2.0;
                lines.add(createLine(from.x + from.width, fromMidY, busX, fromMidY)); // → bus
                lines.add(createLine(busX, fromMidY, busX, effectiveMergeY));          // ↓ to mergeY
                lines.add(createLine(busX, effectiveMergeY, toX, effectiveMergeY));    // ← converge left
                lines.add(createLine(toX, effectiveMergeY, toX, toY));                 // ↓ into target
            }
            return lines;
        }

        // Left-to-right or other: standard L-routing
        double midY = fromY + ((toY - fromY) * 0.5);
        logger.info("Draw MULTIPLE PROCESS Line - FROM x:{} y:{} - TO x:{} y:{} - MID y:{}",
                fromX, fromY, toX, toY, midY);
        lines.add(createLine(fromX, fromY, fromX, midY));
        lines.add(createLine(fromX, midY, toX, midY));
        lines.add(createLine(toX, midY, toX, toY));
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
                double fromCX = from.x + from.width / 2.0;
                double toCX   = to.x   + to.width   / 2.0;
                if (toCX > fromCX + 10) {
                    // Right branch: label beside the right exit point of the diamond
                    x = from.x + from.width + 5;
                    y = from.y + (from.height / 2) - 8;
                } else {
                    // Down branch (stream filter): label just below-right of diamond bottom
                    x = from.x + from.width / 2 + 8;
                    y = from.y + from.height + 16;
                }
            } else if ("False".equalsIgnoreCase(label) || "No".equalsIgnoreCase(label)) {
                if (to.y < from.y) {
                    // Back-edge going up: label at left side of diamond, on the horizontal segment
                    x = from.x - 28;
                    y = from.y + from.height / 2 - 6;
                } else {
                    // Normal False going down: label below-right of diamond bottom
                    x = from.x + (from.width / 2) + 8;
                    y = from.y + from.height + 16;
                }
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

        if (node.type == NodeType.LOOP) {
            double indent = node.width * 0.15;
            double cy = node.y + node.height / 2.0;
            return new Polygon(
                    node.x + indent,               node.y,
                    node.x + node.width - indent,  node.y,
                    node.x + node.width,            cy,
                    node.x + node.width - indent,  node.y + node.height,
                    node.x + indent,               node.y + node.height,
                    node.x,                        cy
            );
        }

        if (node.type == NodeType.END) {
            if (node.label.toLowerCase().startsWith("return")) {
                Rectangle rounded = new Rectangle(node.x, node.y, node.width, node.height);
                rounded.setArcWidth(20);
                rounded.setArcHeight(20);
                return rounded;
            }
            return new Ellipse(
                    node.x + node.width / 2.0,
                    node.y + node.height / 2.0,
                    node.width / 2.0,
                    node.height / 2.0
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

        // ── Class-level enclosing box (outermost, drawn first = behind everything) ──
        if (!currentMethodGroups.isEmpty() && currentClassName != null) {
            double clsMinX = Double.MAX_VALUE, clsMinY = Double.MAX_VALUE;
            double clsMaxX = 0, clsMaxY = 0;
            for (MethodBox mb : currentMethodGroups) {
                double mbRoutingMaxX = currentEdges.stream()
                        .filter(e -> lastEdgeBusX.containsKey(e.fromId + "->" + e.toId))
                        .filter(e -> {
                            FlowNode f = findNodeById(currentNodes, e.fromId);
                            if (f == null) return false;
                            double cx = f.x + f.width / 2.0, cy = f.y + f.height / 2.0;
                            return cx >= mb.x && cx <= mb.x + mb.width
                                    && cy >= mb.y && cy <= mb.y + mb.height;
                        })
                        .mapToDouble(e -> lastEdgeBusX.get(e.fromId + "->" + e.toId))
                        .max().orElse(0);
                double right = Math.max(mb.x + mb.width, mbRoutingMaxX > 0 ? mbRoutingMaxX + 20 : 0);
                clsMinX = Math.min(clsMinX, mb.x);
                clsMinY = Math.min(clsMinY, mb.y);
                clsMaxX = Math.max(clsMaxX, right);
                clsMaxY = Math.max(clsMaxY, mb.y + mb.height);
            }
            final double CLS_PAD = 30;
            double clsX = clsMinX - CLS_PAD;
            double clsY = clsMinY - CLS_PAD - 24;
            double clsW = clsMaxX - clsMinX + 2 * CLS_PAD;
            double clsH = clsMaxY - clsMinY + 2 * CLS_PAD + 24;
            xml.append("        <mxCell id=\"class-box\" value=\"").append(xmlEscape(currentClassName))
                    .append("\" style=\"rounded=0;whiteSpace=wrap;html=1;fillColor=none;")
                    .append("strokeColor=#1a1a2e;strokeWidth=2;")
                    .append("verticalAlign=top;align=center;fontSize=14;fontStyle=1;fontColor=#1a1a2e;spacingTop=4;")
                    .append("\" vertex=\"1\" parent=\"1\">\n");
            xml.append("          <mxGeometry x=\"").append(clsX)
                    .append("\" y=\"").append(clsY)
                    .append("\" width=\"").append(clsW)
                    .append("\" height=\"").append(clsH)
                    .append("\" as=\"geometry\"/>\n");
            xml.append("        </mxCell>\n");
        }

        // ── Method box(es) — rendered behind nodes ───────────────────────
        if (!currentMethodGroups.isEmpty()) {
            int mbIdx = 0;
            for (MethodBox mb : currentMethodGroups) {
                // Mirror drawMethodGroupBox: expand right edge to cover routing lines.
                final double MB_PAD = 20;
                double mbRoutingMaxX = currentEdges.stream()
                        .filter(e -> lastEdgeBusX.containsKey(e.fromId + "->" + e.toId))
                        .filter(e -> {
                            FlowNode f = findNodeById(currentNodes, e.fromId);
                            if (f == null) return false;
                            double cx = f.x + f.width / 2.0, cy = f.y + f.height / 2.0;
                            return cx >= mb.x && cx <= mb.x + mb.width
                                    && cy >= mb.y && cy <= mb.y + mb.height;
                        })
                        .mapToDouble(e -> lastEdgeBusX.get(e.fromId + "->" + e.toId))
                        .max().orElse(0);
                double right = Math.max(mb.x + mb.width, mbRoutingMaxX > 0 ? mbRoutingMaxX + MB_PAD : 0);
                xml.append("        <mxCell id=\"method-box-").append(mbIdx++)
                        .append("\" value=\"").append(xmlEscape(mb.name + "()"))
                        .append("\" style=\"rounded=0;whiteSpace=wrap;html=1;fillColor=none;")
                        .append("strokeColor=#888888;dashed=1;dashPattern=8 5;")
                        .append("verticalAlign=top;align=left;fontSize=12;fontColor=#888888;spacingLeft=6;")
                        .append("\" vertex=\"1\" parent=\"1\">\n");
                xml.append("          <mxGeometry x=\"").append(mb.x)
                        .append("\" y=\"").append(mb.y)
                        .append("\" width=\"").append(right - mb.x)
                        .append("\" height=\"").append(mb.height)
                        .append("\" as=\"geometry\"/>\n");
                xml.append("        </mxCell>\n");
            }
        } else if (currentMethodName != null && !currentNodes.isEmpty()) {
            double pad = 24;
            double minX = currentNodes.stream().mapToDouble(n -> n.x).min().orElse(0) - pad;
            double minY = currentNodes.stream().mapToDouble(n -> n.y).min().orElse(0) - pad - 16;
            double maxX = currentNodes.stream().mapToDouble(n -> n.x + n.width).max().orElse(0) + pad;
            double maxY = currentNodes.stream().mapToDouble(n -> n.y + n.height).max().orElse(0) + pad;
            // Expand to cover routing lines (mirrors drawMethodBox).
            double routingMaxX = lastEdgeBusX.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (routingMaxX > 0) maxX = Math.max(maxX, routingMaxX + pad);
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
            String nodeStyle;
            if (node.type == NodeType.DECISION) {
                nodeStyle = "rhombus;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";
            } else if (node.type == NodeType.LOOP) {
                nodeStyle = "shape=hexagon;perimeter=hexagonPerimeter2;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";
            } else if (node.type == NodeType.END && node.label.toLowerCase().startsWith("return")) {
                nodeStyle = "rounded=1;arcSize=30;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";
            } else if (node.type == NodeType.END) {
                nodeStyle = "ellipse;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";
            } else {
                nodeStyle = "rounded=0;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#000000;";
            }

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
            FlowNode from = findNodeById(currentNodes, edge.fromId);
            FlowNode to   = findNodeById(currentNodes, edge.toId);
            if (from == null || to == null) continue;

            String label   = edge.label != null ? edge.label : "";
            String edgeKey = edge.fromId + "->" + edge.toId;

            double fromCX     = from.x + from.width  / 2.0;
            double toCX       = to.x   + to.width    / 2.0;
            double fromBottom = from.y + from.height;
            double toTop      = to.y;

            // ── Back-edge (loop-back): left side of source → left side of target ──────
            if (edge.isBackEdge) {
                double detourX  = from.type == NodeType.DECISION ? from.x - 30 : from.x - 55;
                double fromMidY = from.y + from.height / 2.0;
                double toMidY   = to.y   + to.height   / 2.0;
                appendEdgeXml(xml, edge.key(), label,
                        "exitX=0;exitY=0.5;exitDx=0;exitDy=0;entryX=0;entryY=0.5;entryDx=0;entryDy=0;",
                        edge.fromId, edge.toId,
                        new double[][]{{detourX, fromMidY}, {detourX, toMidY}});
                continue;
            }

            // ── LOOP false-exit: right side → detour → right side of terminal ─────────
            if (from.type == NodeType.LOOP && "False".equalsIgnoreCase(label)) {
                double startY  = from.y + from.height / 2.0;
                double detourX = from.x + from.width + 70;
                double endY    = to.y + to.height / 2.0;
                appendEdgeXml(xml, edge.key(), label,
                        "exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=1;entryY=0.5;entryDx=0;entryDy=0;",
                        edge.fromId, edge.toId,
                        new double[][]{{detourX, startY}, {detourX, endY}});
                continue;
            }

            // ── Join-back: source to the right of target, going downward ──────────────
            if (fromCX > toCX + 20 && toTop >= fromBottom - 5) {
                double mergeY  = lastEdgeMergeY.getOrDefault(edgeKey, 0.0);
                double busX    = lastEdgeBusX.getOrDefault(edgeKey, 0.0);
                boolean single = lastEdgeSingleJoinBack.getOrDefault(edgeKey, true);
                double effectiveMergeY = mergeY > fromBottom ? mergeY : (fromBottom + toTop) / 2.0;

                String lbl = from.label.toLowerCase();
                boolean hasDb = lbl.contains("save") || lbl.contains("update") || lbl.contains("delete");

                if (hasDb || single) {
                    // Bottom-center exit → horizontal at mergeY → into top of target
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId,
                            new double[][]{{fromCX, effectiveMergeY}, {toCX, effectiveMergeY}});
                } else {
                    // Right-side bus exit → staggered bus → horizontal at mergeY → into top of target
                    double fromMidY = from.y + from.height / 2.0;
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId,
                            new double[][]{{busX, fromMidY}, {busX, effectiveMergeY}, {toCX, effectiveMergeY}});
                }
                continue;
            }

            // ── DECISION True/Yes: right side of diamond ──────────────────────────────
            if (from.type == NodeType.DECISION
                    && ("True".equalsIgnoreCase(label) || "Yes".equalsIgnoreCase(label))) {
                double startY = from.y + from.height / 2.0;
                if (Math.abs(to.y - from.y) < 5) {
                    // Same row: purely horizontal, enter from left side of target box
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0;entryY=0.5;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId, null);
                } else {
                    // Box is below: go right then down into top of target
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId,
                            new double[][]{{toCX, startY}});
                }
                continue;
            }

            // ── DECISION False/No: bottom of diamond ──────────────────────────────────
            if (from.type == NodeType.DECISION
                    && ("False".equalsIgnoreCase(label) || "No".equalsIgnoreCase(label))) {
                double startX  = fromCX;
                double startY2 = fromBottom;
                double endX    = toCX;
                if (Math.abs(startX - endX) < 0.5) {
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId, null);
                } else {
                    double midY = (startY2 + toTop) / 2.0;
                    appendEdgeXml(xml, edge.key(), label,
                            "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                            edge.fromId, edge.toId,
                            new double[][]{{startX, midY}, {endX, midY}});
                }
                continue;
            }

            // ── JOIN target: route to target's center column ──────────────────────────
            if (to.type == NodeType.JOIN) {
                double startX = fromCX;
                double startY = fromBottom;
                double endX   = toCX;
                appendEdgeXml(xml, edge.key(), label,
                        "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                        edge.fromId, edge.toId,
                        Math.abs(startX - endX) < 0.5 ? null : new double[][]{{startX, toTop}, {endX, toTop}});
                continue;
            }

            // ── Straight vertical ─────────────────────────────────────────────────────
            if (Math.abs(fromCX - toCX) < 0.5) {
                appendEdgeXml(xml, edge.key(), label,
                        "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                        edge.fromId, edge.toId, null);
                continue;
            }

            // ── L-routing (left-to-right or general offset) ───────────────────────────
            double midY = fromBottom + (toTop - fromBottom) * 0.5;
            appendEdgeXml(xml, edge.key(), label,
                    "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;",
                    edge.fromId, edge.toId,
                    new double[][]{{fromCX, midY}, {toCX, midY}});
        }

        xml.append("      </root>\n");
        xml.append("    </mxGraphModel>\n");
        xml.append("  </diagram>\n");
        xml.append("</mxfile>");
        return xml.toString();
    }

    /**
     * Appends a draw.io mxCell edge with optional explicit waypoints.
     * @param points null for no waypoints; otherwise each row is [x, y].
     */
    private static void appendEdgeXml(StringBuilder xml, String id, String label,
                                       String exitEntry, String fromId, String toId,
                                       double[][] points) {
        xml.append("        <mxCell id=\"").append(id)
                .append("\" value=\"").append(xmlEscape(label))
                .append("\" style=\"edgeStyle=orthogonalEdgeStyle;").append(exitEntry)
                .append("rounded=0;orthogonalLoop=1;html=1;\"")
                .append(" source=\"").append(fromId)
                .append("\" target=\"").append(toId)
                .append("\" edge=\"1\" parent=\"1\">\n");
        if (points != null && points.length > 0) {
            xml.append("          <mxGeometry relative=\"1\" as=\"geometry\">\n");
            xml.append("            <Array as=\"points\">\n");
            for (double[] p : points) {
                xml.append("              <mxPoint x=\"").append((int) p[0])
                        .append("\" y=\"").append((int) p[1]).append("\"/>\n");
            }
            xml.append("            </Array>\n");
            xml.append("          </mxGeometry>\n");
        } else {
            xml.append("          <mxGeometry relative=\"1\" as=\"geometry\"/>\n");
        }
        xml.append("        </mxCell>\n");
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }


}