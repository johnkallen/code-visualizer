package com.codevisualizer.parser;

import com.codevisualizer.model.FlowEdge;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.enums.NodeType;
import com.codevisualizer.model.Pair;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(CodeParser.class);

    public static class ParseResult {
        public Map<String, Object> variables = new LinkedHashMap<>();
        public List<FlowNode> flowNodes = new ArrayList<>();
        public List<FlowEdge> flowEdges = new ArrayList<>();

    }

    public ParseResult parse(String code) {
        logger.info("\nStarting to parse code: \n{}", code);
        ParseResult result = new ParseResult();

        if (!code.contains("class")) {
            logger.info("Wrapping code in a temporary class");
            code = "class Temp { void temp() { " + code + " } }";
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            logger.info("Parsed code into CompilationUnit");

            cu.findAll(VariableDeclarator.class).forEach(v ->
                    result.variables.put(v.getNameAsString(), null)
            );
            logger.debug("Collected {} variables", result.variables.size());

            Optional<MethodDeclaration> methodOpt = cu.findFirst(MethodDeclaration.class);
            if (methodOpt.isEmpty() || methodOpt.get().getBody().isEmpty()) {
                logger.warn("No method body found in code");
                return result;
            }

            List<Statement> statements = methodOpt.get().getBody().get().getStatements();
            logger.debug("Found {} statements in method body", statements.size());

            Layout layout = new Layout();
            FlowNode previous = null;

            List<FlowNode> branchEnds = new ArrayList<>();

            for (Statement stmt : statements) {
                logger.debug("Processing statement: {}", stmt);
                // Scan Statements and Identify different code types
                if (stmt instanceof IfStmt ifStmt) {
                    List<FlowNode> predecessors = new ArrayList<>(branchEnds);
                    if (previous != null) predecessors.add(previous);
                    branchEnds.clear();
                    Pair<FlowNode, FlowNode> branches = addIf(result, ifStmt, predecessors, layout);
                    if (branches.getFirst() != null) branchEnds.add(branches.getFirst());
                    if (branches.getSecond() != null) branchEnds.add(branches.getSecond());
                    previous = null; // No single predecessor after if-else
                } else {
                    // Default if parser cannot find a specific type
                    FlowNode node = createProcessNode(
                            stmt,
                            layout.centerX,
                            layout.currentY,
                            layout.nodeWidth,
                            layout.nodeHeight
                    );
                    result.flowNodes.add(node);

                    // Connect to previous if exists (single predecessor)
                    if (previous != null) {
                        previous.nextId = node.id;
                        result.flowEdges.add(new FlowEdge(previous.id, node.id, ""));
                        logger.debug("Added edge from {} to {} with label '{}'", previous.label, node.label, "");
                    }

                    // Connect to branch ends (multiple predecessors)
                    for (FlowNode end : branchEnds) {
                        if (end.type == NodeType.DECISION && end.falseNextId == null) {
                            // No-else decision: wire the false path directly to this node
                            end.falseNextId = node.id;
                            result.flowEdges.add(new FlowEdge(end.id, node.id, "False"));
                            logger.debug("Added false edge from decision {} to {} (no-else)", end.label, node.label);
                        } else {
                            end.nextId = node.id;
                            result.flowEdges.add(new FlowEdge(end.id, node.id, ""));
                            logger.debug("Added edge from branch-end {} to {} with label '{}'", end.label, node.label, "");
                        }
                    }

                    previous = node;
                    layout.currentY += layout.verticalGap;
                    branchEnds.clear();
                }
            }
            logger.info("Parsing completed successfully");
        } catch (Exception e) {
            logger.error("Error parsing code", e);
        }

        return result;
    }

    private Pair<FlowNode, FlowNode> addIf(ParseResult result, IfStmt ifStmt, List<FlowNode> predecessors, Layout layout) {

        logger.debug("Processing if statement with condition: {}", ifStmt.getCondition().toString());
        double decisionX = layout.centerX;
        double decisionY = layout.currentY;

        // Create decision node
        FlowNode decision = new FlowNode(
                UUID.randomUUID().toString(),
                ifStmt.getCondition().toString(),
                NodeType.DECISION,
                decisionX,
                decisionY,
                layout.nodeWidth,
                layout.nodeHeight
        );
        decision.condition  = ifStmt.getCondition().toString();
        decision.beginLine  = ifStmt.getBegin().map(p -> p.line).orElse(0);
        decision.endLine    = decision.beginLine;
        result.flowNodes.add(decision);

        // Connect all predecessors to this decision node
        for (FlowNode pred : predecessors) {
            if (pred != null) {
                pred.nextId = decision.id;
                result.flowEdges.add(new FlowEdge(pred.id, decision.id, ""));
                logger.debug("Added edge from {} to decision {} with label '{}'", pred.label, decision.label, "");
            }
        }

        // Unwrap statements
        List<Statement> thenStatements = unwrapStatements(ifStmt.getThenStmt());
        List<Statement> elseStatements = ifStmt.getElseStmt()
                .map(this::unwrapStatements)
                .orElseGet(ArrayList::new);

        double trueX  = layout.centerX - layout.branchOffset;
        double falseX = layout.centerX; // false branch goes straight down
        double branchStartY = layout.currentY + layout.verticalGap;

        // Create true and false branches
        List<FlowNode> trueNodes = createVerticalBranch(result, thenStatements, trueX, branchStartY, layout);
        List<FlowNode> falseNodes = createVerticalBranch(result, elseStatements, falseX, branchStartY, layout);

        FlowNode trueFirst = trueNodes.isEmpty() ? null : trueNodes.get(0);
        FlowNode falseFirst = falseNodes.isEmpty() ? null : falseNodes.get(0);

        // Connect decision to true/false branches with "True"/"False" labels
        if (trueFirst != null) {
            decision.trueNextId = trueFirst.id;
            result.flowEdges.add(new FlowEdge(decision.id, trueFirst.id, "True"));
            logger.debug("Added edge from {} to {} with label 'True'", decision.label, trueFirst.label);
        }

        if (falseFirst != null) {
            decision.falseNextId = falseFirst.id;
            result.flowEdges.add(new FlowEdge(decision.id, falseFirst.id, "False"));
            logger.debug("Added edge from {} to {} with label 'False'", decision.label, falseFirst.label);
        }

        // Update layout Y position based on the deepest branch
        int trueDepth = trueNodes.size();
        int falseDepth = falseNodes.size();
        int maxDepth = Math.max(trueDepth, falseDepth);
        layout.currentY = branchStartY + (maxDepth * layout.verticalGap);

        logger.debug("Created if-else branches with {} and {} nodes", trueNodes.size(), falseNodes.size());

        // When there is no else, the decision node itself is the false exit point
        FlowNode falseEnd = falseNodes.isEmpty() ? decision : falseNodes.get(falseNodes.size() - 1);
        return new Pair<>(trueNodes.isEmpty() ? null : trueNodes.get(trueNodes.size() - 1), falseEnd);

    }

    private List<FlowNode> createVerticalBranch(ParseResult result,
                                                List<Statement> statements,
                                                double x,
                                                double startY,
                                                Layout layout) {
        logger.debug("Creating vertical branch with {} statements", statements.size());
        List<FlowNode> nodes = new ArrayList<>();
        FlowNode previous = null;
        double y = startY;

        for (Statement stmt : statements) {
            FlowNode node = createProcessNode(stmt, x, y, layout.nodeWidth, layout.nodeHeight);
            result.flowNodes.add(node);
            nodes.add(node);

            if (previous != null) {
                previous.nextId = node.id;
                result.flowEdges.add(new FlowEdge(previous.id, node.id, ""));
            }

            previous = node;
            y += layout.verticalGap;
        }

        return nodes;
    }

    private List<Statement> unwrapStatements(Statement stmt) {
        if (stmt instanceof BlockStmt block) {
            return new ArrayList<>(block.getStatements());
        }

        List<Statement> list = new ArrayList<>();
        list.add(stmt);
        return list;
    }

    private FlowNode createProcessNode(Statement stmt,
                                       double x,
                                       double y,
                                       double width,
                                       double height) {
        NodeType type = stmt instanceof ReturnStmt ? NodeType.END : NodeType.PROCESS;

        FlowNode node = new FlowNode(
                UUID.randomUUID().toString(),
                stmt.toString().trim(),
                type,
                x,
                y,
                width,
                height
        );

        if (type == NodeType.PROCESS) {
            node.expression = stmt.toString().trim();
        }

        node.beginLine = stmt.getBegin().map(p -> p.line).orElse(0);
        node.endLine   = stmt.getEnd().map(p -> p.line).orElse(0);

        return node;
    }

    private static class Layout {
        double centerX = 320;
        double currentY = 40;
        double nodeWidth = 200;
        double nodeHeight = 60;
        double verticalGap = 100; // Spacing between nodes (top of Node to top of next node)
        double branchOffset = 260;
    }
}
