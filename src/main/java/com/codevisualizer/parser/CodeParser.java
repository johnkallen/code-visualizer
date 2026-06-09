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

    private static class BranchResult {
        final FlowNode first;
        final List<FlowNode> exits;

        BranchResult(FlowNode first, List<FlowNode> exits) {
            this.first = first;
            this.exits = new ArrayList<>(exits);
        }
    }

    public ParseResult parse(String code) {
        logger.info("\nStarting to parse code: \n{}", code);
        ParseResult result = new ParseResult();

        if (!code.contains("class")) {
            code = "class Temp { void temp() { " + code + " } }";
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(code);

            cu.findAll(VariableDeclarator.class).forEach(v ->
                    result.variables.put(v.getNameAsString(), null)
            );

            Optional<MethodDeclaration> methodOpt = cu.findFirst(MethodDeclaration.class);
            if (methodOpt.isEmpty() || methodOpt.get().getBody().isEmpty()) return result;

            List<Statement> statements = methodOpt.get().getBody().get().getStatements();
            logger.debug("Found {} top-level statements", statements.size());

            Layout layout = new Layout();
            FlowNode previous = null;
            List<FlowNode> pendingExits = new ArrayList<>();

            for (Statement stmt : statements) {
                if (stmt instanceof IfStmt ifStmt) {
                    List<FlowNode> predecessors = new ArrayList<>(pendingExits);
                    if (previous != null) predecessors.add(previous);
                    pendingExits.clear();
                    previous = null;

                    Pair<FlowNode, List<FlowNode>> ifResult =
                            processIfStmt(result, ifStmt, predecessors, layout.centerX, layout);
                    pendingExits.addAll(ifResult.getSecond());
                } else {
                    FlowNode node = createProcessNode(
                            stmt, layout.centerX, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                    result.flowNodes.add(node);

                    if (previous != null) addEdge(result, previous, node);
                    for (FlowNode exit : pendingExits) connectExit(result, exit, node);
                    pendingExits.clear();

                    previous = node;
                    layout.currentY += layout.verticalGap;
                }
            }

            logger.info("Parsing completed successfully");
        } catch (Exception e) {
            logger.error("Error parsing code", e);
        }

        return result;
    }

    /**
     * Builds a DECISION node at (decisionX, layout.currentY), then recursively
     * processes the then/else bodies via processBranch.
     * True branch goes RIGHT  (decisionX + branchOffset) so nested ifs never go off-screen.
     * False branch goes DOWN  (same decisionX column).
     * Returns: (decisionNode, exitNodes)
     *   exitNodes — leaf nodes that callers must wire to whatever follows the if.
     */
    private Pair<FlowNode, List<FlowNode>> processIfStmt(ParseResult result, IfStmt ifStmt,
                                                          List<FlowNode> predecessors,
                                                          double decisionX, Layout layout) {
        double decisionY = layout.currentY;

        FlowNode decision = new FlowNode(
                UUID.randomUUID().toString(),
                ifStmt.getCondition().toString(),
                NodeType.DECISION,
                decisionX, decisionY,
                layout.nodeWidth, layout.nodeHeight
        );
        decision.condition = ifStmt.getCondition().toString();
        decision.beginLine = ifStmt.getBegin().map(p -> p.line).orElse(0);
        decision.endLine   = decision.beginLine;
        result.flowNodes.add(decision);

        for (FlowNode pred : predecessors) {
            connectExit(result, pred, decision);
        }

        List<Statement> thenStmts = unwrapStatements(ifStmt.getThenStmt());
        List<Statement> elseStmts = ifStmt.getElseStmt()
                .map(this::unwrapStatements).orElseGet(ArrayList::new);

        double branchStartY = decisionY + layout.verticalGap;
        double trueX = decisionX + layout.branchOffset; // right; false stays at decisionX (straight down)

        layout.currentY = branchStartY;
        BranchResult trueBranch = processBranch(result, thenStmts, trueX, layout);
        double trueEndY = layout.currentY;

        layout.currentY = branchStartY;
        BranchResult falseBranch = processBranch(result, elseStmts, decisionX, layout);
        double falseEndY = layout.currentY;

        layout.currentY = Math.max(trueEndY, falseEndY);

        if (trueBranch.first != null) {
            decision.trueNextId = trueBranch.first.id;
            result.flowEdges.add(new FlowEdge(decision.id, trueBranch.first.id, "True"));
        }
        if (falseBranch.first != null) {
            decision.falseNextId = falseBranch.first.id;
            result.flowEdges.add(new FlowEdge(decision.id, falseBranch.first.id, "False"));
        }

        List<FlowNode> exits = new ArrayList<>(trueBranch.exits);
        if (falseBranch.exits.isEmpty()) {
            exits.add(decision); // no-else: decision is the false exit point
        } else {
            exits.addAll(falseBranch.exits);
        }

        return new Pair<>(decision, exits);
    }

    /**
     * Processes a linear sequence of statements for a branch column at x.
     * Handles nested IfStmts recursively.
     * Returns BranchResult(firstNode, exitNodes).
     */
    private BranchResult processBranch(ParseResult result, List<Statement> statements,
                                        double x, Layout layout) {
        if (statements.isEmpty()) return new BranchResult(null, new ArrayList<>());

        FlowNode branchFirst = null;
        List<FlowNode> currentExits = new ArrayList<>();

        for (Statement stmt : statements) {
            if (stmt instanceof IfStmt ifStmt) {
                Pair<FlowNode, List<FlowNode>> ifResult =
                        processIfStmt(result, ifStmt, currentExits, x, layout);
                FlowNode nestedDecision = ifResult.getFirst();
                if (branchFirst == null) branchFirst = nestedDecision;
                currentExits = new ArrayList<>(ifResult.getSecond());
            } else {
                FlowNode node = createProcessNode(
                        stmt, x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                result.flowNodes.add(node);

                if (branchFirst == null) branchFirst = node;
                for (FlowNode exit : currentExits) connectExit(result, exit, node);
                currentExits.clear();

                layout.currentY += layout.verticalGap;
                currentExits.add(node);
            }
        }

        return new BranchResult(branchFirst, currentExits);
    }

    private void addEdge(ParseResult result, FlowNode from, FlowNode to) {
        from.nextId = to.id;
        result.flowEdges.add(new FlowEdge(from.id, to.id, ""));
        logger.debug("Edge: {} → {}", from.label, to.label);
    }

    private void connectExit(ParseResult result, FlowNode exit, FlowNode target) {
        if (exit.type == NodeType.DECISION && exit.falseNextId == null) {
            exit.falseNextId = target.id;
            result.flowEdges.add(new FlowEdge(exit.id, target.id, "False"));
            logger.debug("False edge: {} → {}", exit.label, target.label);
        } else {
            exit.nextId = target.id;
            result.flowEdges.add(new FlowEdge(exit.id, target.id, ""));
            logger.debug("Edge: {} → {}", exit.label, target.label);
        }
    }

    private List<Statement> unwrapStatements(Statement stmt) {
        if (stmt instanceof BlockStmt block) {
            return new ArrayList<>(block.getStatements());
        }
        List<Statement> list = new ArrayList<>();
        list.add(stmt);
        return list;
    }

    private FlowNode createProcessNode(Statement stmt, double x, double y, double width, double height) {
        NodeType type = stmt instanceof ReturnStmt ? NodeType.END : NodeType.PROCESS;
        FlowNode node = new FlowNode(
                UUID.randomUUID().toString(),
                stmt.toString().trim(),
                type,
                x, y, width, height
        );
        if (type == NodeType.PROCESS) node.expression = stmt.toString().trim();
        node.beginLine = stmt.getBegin().map(p -> p.line).orElse(0);
        node.endLine   = stmt.getEnd().map(p -> p.line).orElse(0);
        return node;
    }

    private static class Layout {
        double centerX      = 200;  // left column; true branches extend rightward
        double currentY     = 40;
        double nodeWidth    = 200;
        double nodeHeight   = 60;
        double verticalGap  = 100;
        double branchOffset = 260;  // each nesting level adds this to the right
    }
}
