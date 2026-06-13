package com.codevisualizer.parser;

import com.codevisualizer.model.FlowEdge;
import com.codevisualizer.model.FlowNode;
import com.codevisualizer.model.MethodBox;
import com.codevisualizer.model.StreamGroup;
import com.codevisualizer.enums.NodeType;
import com.codevisualizer.model.Pair;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(CodeParser.class);

    public static class ParseResult {
        public Map<String, Object> variables = new LinkedHashMap<>();
        public Map<String, Object> mockReturnValues = new LinkedHashMap<>();
        public List<FlowNode> flowNodes = new ArrayList<>();
        public List<FlowEdge> flowEdges = new ArrayList<>();
        public List<StreamGroup> streamGroups = new ArrayList<>();
        public List<MethodBox> methodGroups = new ArrayList<>();
        public String methodName = null;
    }

    private static class BranchResult {
        final FlowNode first;
        final List<FlowNode> exits;

        BranchResult(FlowNode first, List<FlowNode> exits) {
            this.first = first;
            this.exits = new ArrayList<>(exits);
        }
    }

    /** Wraps bare code or a bare method in a throwaway class so JavaParser can parse it. */
    private String wrapIfNeeded(String code) {
        // Strip comments to find the true first keyword (leading // or /* */ must not fool us)
        String noComments = code.replaceAll("/\\*(?s).*?\\*/", " ").replaceAll("//[^\n\r]*", " ");
        if (noComments.contains("class")) return code;
        String[] tokens = noComments.trim().split("\\s+");
        String first = tokens.length > 0 ? tokens[0] : "";
        boolean isMethodDecl = first.equals("public") || first.equals("private")
                || first.equals("protected") || first.equals("static") || first.equals("void");
        return isMethodDecl ? "class Temp { " + code + " }"
                            : "class Temp { void temp() { " + code + " } }";
    }

    /**
     * Returns the display signatures of every method in the pasted code,
     * e.g. ["getUser(int id)", "createUser(String name, int age)"].
     * Used to populate the method selector before full parsing.
     */
    public List<String> parseMethodSignatures(String code) {
        List<String> signatures = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(wrapIfNeeded(code));
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if ("temp".equals(m.getNameAsString())) continue;
                List<String> params = new ArrayList<>();
                m.getParameters().forEach(p ->
                        params.add(p.getType().asString() + " " + p.getNameAsString()));
                signatures.add(m.getNameAsString() + "(" + String.join(", ", params) + ")");
            }
        } catch (Exception ignored) {}
        return signatures;
    }


    /** Convenience overload — parses the first (or only) method. */
    public ParseResult parse(String code) {
        return parse(code, null);
    }

    /**
     * Parses {@code targetMethodName} from the code and builds the flowchart model.
     * If {@code targetMethodName} is null, the first method found is used.
     */
    public ParseResult parse(String code, String targetMethodName) {
        logger.info("\nStarting to parse code: \n{}", code);
        ParseResult result = new ParseResult();

        code = wrapIfNeeded(code);

        try {
            CompilationUnit cu = StaticJavaParser.parse(code);

            Optional<MethodDeclaration> methodOpt = (targetMethodName == null)
                    ? cu.findFirst(MethodDeclaration.class)
                    : cu.findAll(MethodDeclaration.class).stream()
                            .filter(m -> m.getNameAsString().equals(targetMethodName))
                            .findFirst();

            if (methodOpt.isEmpty() || methodOpt.get().getBody().isEmpty()) return result;

            MethodDeclaration method = methodOpt.get();
            if (!"temp".equals(method.getNameAsString())) {
                result.methodName = method.getNameAsString();
            }

            // Parameters first so they appear at the top of the Variables panel
            method.getParameters().forEach(p ->
                    result.variables.put(p.getNameAsString(), defaultValue(p.getType().asString()))
            );

            // Scope variable discovery to this method only (not the whole class)
            method.findAll(VariableDeclarator.class).forEach(v -> {
                result.variables.put(v.getNameAsString(), defaultValue(v.getType().asString()));
                v.getInitializer().ifPresent(init -> {
                    if (init instanceof MethodCallExpr mce) {
                        // If the call has all-literal args (e.g. Arrays.asList(85, 42, 90, 61, 78)),
                        // store them as a readable string instead of null
                        boolean hasLiteralArgs = !mce.getArguments().isEmpty()
                                && mce.getArguments().stream().allMatch(Expression::isLiteralExpr);
                        Object value = hasLiteralArgs
                                ? mce.getArguments().stream()
                                        .map(Expression::toString)
                                        .collect(Collectors.joining(", "))
                                : defaultValue(v.getType().asString());
                        result.mockReturnValues.put(mce.getNameAsString(), value);
                    }
                });
            });

            List<Statement> statements = method.getBody().get().getStatements();
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
                } else if (isStreamStatement(stmt)) {
                    StreamExpansion se = expandStream(result, stmt, layout.centerX, layout);
                    if (previous != null) addEdge(result, previous, se.first);
                    for (FlowNode exit : pendingExits) connectExit(result, exit, se.first);
                    pendingExits.clear();
                    previous = se.terminal;
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

            // Append a synthetic END node unless the last statement was already a return
            boolean needsEnd = (previous != null && previous.type != NodeType.END)
                    || !pendingExits.isEmpty();

            if (needsEnd) {
                double endW = 120;
                double endH = 36;
                double endX = layout.centerX + (layout.nodeWidth - endW) / 2.0;
                FlowNode endNode = new FlowNode(
                        UUID.randomUUID().toString(),
                        "End",
                        NodeType.END,
                        endX, layout.currentY, endW, endH
                );
                result.flowNodes.add(endNode);
                if (previous != null && previous.type != NodeType.END) {
                    addEdge(result, previous, endNode);
                }
                for (FlowNode exit : pendingExits) connectExit(result, exit, endNode);
                pendingExits.clear();
            }

            logger.info("Parsing completed successfully");
        } catch (Exception e) {
            logger.error("Error parsing code", e);
        }

        return result;
    }

    /**
     * Parses every method in the class and lays them out in a grid: at most 3 methods
     * per column, then a new column starts ~half-inch to the right of the widest method
     * box in the previous column. Used for the "All Methods" view.
     */
    public ParseResult parseAll(String code) {
        ParseResult combined = new ParseResult();
        List<String> sigs = parseMethodSignatures(code);
        if (sigs.isEmpty()) return parse(code);

        final int    METHODS_PER_COLUMN = 3;
        final double METHOD_GAP         = 80;  // vertical gap between methods in same column
        final double COLUMN_GAP         = 60;  // horizontal gap between columns (~half inch)
        final double PAD                = 24;

        double xOffset   = 0;
        int    numColumns = (sigs.size() + METHODS_PER_COLUMN - 1) / METHODS_PER_COLUMN;

        for (int col = 0; col < numColumns; col++) {
            int start = col * METHODS_PER_COLUMN;
            int end   = Math.min(start + METHODS_PER_COLUMN, sigs.size());

            double yOffset        = 0;
            double columnMinLeft  = Double.MAX_VALUE;
            double columnMaxRight = 0;

            for (int i = start; i < end; i++) {
                String sig  = sigs.get(i);
                String name = sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
                ParseResult mr = parse(code, name);
                if (mr.flowNodes.isEmpty()) continue;

                double minY = mr.flowNodes.stream().mapToDouble(n -> n.y).min().orElse(0);
                double maxY = mr.flowNodes.stream().mapToDouble(n -> n.y + n.height).max().orElse(0);
                double minX = mr.flowNodes.stream().mapToDouble(n -> n.x).min().orElse(0);
                double maxX = mr.flowNodes.stream().mapToDouble(n -> n.x + n.width).max().orElse(0);

                final double xShift = xOffset;
                final double yShift = yOffset;
                mr.flowNodes.forEach(n -> { n.x += xShift; n.y += yShift; });

                // Shift stream groups by both x and y offsets
                List<StreamGroup> shifted = mr.streamGroups.stream()
                        .map(sg -> new StreamGroup(sg.x + xShift, sg.y + yShift, sg.width, sg.height)).toList();
                combined.streamGroups.addAll(shifted);

                // Compute method box bounds after shifting, expanded to contain stream groups
                double boxMinX = (minX + xShift) - PAD;
                double boxMinY = (minY + yShift) - PAD - 16;
                double boxMaxX = (maxX + xShift) + PAD;
                double boxMaxY = (maxY + yShift) + PAD;
                for (StreamGroup sg : shifted) {
                    boxMinX = Math.min(boxMinX, sg.x - 8);
                    boxMaxX = Math.max(boxMaxX, sg.x + sg.width + 8);
                    boxMinY = Math.min(boxMinY, sg.y - 8);
                    boxMaxY = Math.max(boxMaxY, sg.y + sg.height + 8);
                }
                combined.methodGroups.add(
                        new MethodBox(name, boxMinX, boxMinY, boxMaxX - boxMinX, boxMaxY - boxMinY));

                // Track this column's horizontal extent to position the next column
                columnMinLeft  = Math.min(columnMinLeft,  boxMinX);
                columnMaxRight = Math.max(columnMaxRight, boxMaxX);

                combined.flowNodes.addAll(mr.flowNodes);
                combined.flowEdges.addAll(mr.flowEdges);

                yOffset += (maxY - minY) + METHOD_GAP;
            }

            // Advance xOffset so the next column starts after this column's widest box + gap
            if (columnMaxRight > 0) {
                xOffset += (columnMaxRight - columnMinLeft) + COLUMN_GAP;
            }
        }

        return combined;
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

        double trueX = decisionX + layout.branchOffset; // right; false stays at decisionX (straight down)

        // True branch is placed at the SAME Y as the decision diamond (side-by-side).
        layout.currentY = decisionY;
        BranchResult trueBranch = processBranch(result, thenStmts, trueX, layout);
        double trueEndY = layout.currentY;

        // False branch continues downward from one gap below the decision.
        layout.currentY = decisionY + layout.verticalGap;
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

        // END (return) nodes are terminal — never wire them to what follows
        List<FlowNode> exits = new ArrayList<>();
        trueBranch.exits.stream().filter(n -> n.type != NodeType.END).forEach(exits::add);
        if (falseBranch.exits.isEmpty()) {
            exits.add(decision); // no-else: decision is the false exit point
        } else {
            falseBranch.exits.stream().filter(n -> n.type != NodeType.END).forEach(exits::add);
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
            } else if (isStreamStatement(stmt)) {
                StreamExpansion se = expandStream(result, stmt, x, layout);
                if (branchFirst == null) branchFirst = se.first;
                for (FlowNode exit : currentExits) connectExit(result, exit, se.first);
                currentExits.clear();
                currentExits.add(se.terminal);
            } else {
                FlowNode node = createProcessNode(
                        stmt, x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                result.flowNodes.add(node);

                if (branchFirst == null) branchFirst = node;
                for (FlowNode exit : currentExits) connectExit(result, exit, node);
                currentExits.clear();

                layout.currentY += layout.verticalGap;
                // END (return) nodes are terminal — don't wire them to what follows
                if (node.type != NodeType.END) {
                    currentExits.add(node);
                }
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

    // ── Stream expansion ─────────────────────────────────────────────────────

    private static class StreamExpansion {
        final FlowNode first;
        final FlowNode terminal;
        final FlowNode loopNode;
        StreamExpansion(FlowNode first, FlowNode terminal, FlowNode loopNode) {
            this.first    = first;
            this.terminal = terminal;
            this.loopNode = loopNode;
        }
    }

    private boolean isStreamStatement(Statement stmt) {
        return stmt.findFirst(MethodCallExpr.class,
                mce -> "stream".equals(mce.getNameAsString())).isPresent();
    }

    /** Walks the MethodCallExpr chain from outermost→innermost, then reverses to source order. */
    private List<MethodCallExpr> collectChain(Statement stmt) {
        List<MethodCallExpr> chain = new ArrayList<>();

        // Extract the outermost expression
        Expression root = null;
        Optional<VariableDeclarator> vdOpt = stmt.findFirst(VariableDeclarator.class);
        if (vdOpt.isPresent()) {
            root = vdOpt.get().getInitializer().orElse(null);
        } else if (stmt instanceof ExpressionStmt es) {
            root = es.getExpression();
        }
        if (root == null) return chain;

        // Unwrap cast(s)
        while (root instanceof CastExpr ce) root = ce.getExpression();

        // Walk from terminal inward, collecting
        Expression cur = root;
        while (cur instanceof MethodCallExpr mce) {
            chain.add(mce);
            cur = mce.getScope().orElse(null);
        }
        Collections.reverse(chain); // now source-order: stream(), filter(), map(), ..., terminal
        return chain;
    }

    /** Returns body statements for a lambda arg, or empty if not a lambda. */
    private List<Statement> lambdaBodyStmts(MethodCallExpr mce) {
        if (mce.getArguments().isEmpty()) return new ArrayList<>();
        Expression arg = mce.getArguments().get(0);
        if (!(arg instanceof LambdaExpr lambda)) return new ArrayList<>();
        Statement body = lambda.getBody();
        if (body instanceof BlockStmt block) return new ArrayList<>(block.getStatements());
        List<Statement> single = new ArrayList<>();
        single.add(body);
        return single;
    }

    private String lambdaParam(MethodCallExpr mce) {
        if (mce.getArguments().isEmpty()) return "x";
        Expression arg = mce.getArguments().get(0);
        if (arg instanceof LambdaExpr le && !le.getParameters().isEmpty())
            return le.getParameters().get(0).getNameAsString();
        return "x";
    }

    private String lambdaBodyExpr(MethodCallExpr mce) {
        if (mce.getArguments().isEmpty()) return mce.getNameAsString() + "()";
        Expression arg = mce.getArguments().get(0);
        if (arg instanceof LambdaExpr le) {
            Statement body = le.getBody();
            if (body instanceof ExpressionStmt es) return trimSemi(es.getExpression().toString());
            if (body instanceof BlockStmt bs)
                return bs.getStatements().stream()
                        .map(s -> trimSemi(s.toString())).collect(Collectors.joining("; "));
        }
        return trimSemi(arg.toString());
    }

    private static String trimSemi(String s) {
        s = stripComments(s);
        return s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
    }

    /** Removes // line comments and block comments from a label string. */
    private static String stripComments(String s) {
        s = s.replaceAll("/\\*(?s).*?\\*/", " "); // block comments
        s = s.replaceAll("//[^\n\r]*", "");        // line comments
        return s.replaceAll("\\s+", " ").trim();
    }

    private FlowNode makeNode(String label, NodeType type, double x, double y, double w, double h) {
        return new FlowNode(UUID.randomUUID().toString(), label, type, x, y, w, h);
    }

    /** Wires from→to, respecting DECISION trueNextId vs PROCESS nextId. */
    private void wireBodyEdge(ParseResult result, FlowNode from, FlowNode to) {
        if (from.type == NodeType.DECISION) {
            from.trueNextId = to.id;
            result.flowEdges.add(new FlowEdge(from.id, to.id, "True"));
        } else if (from.type == NodeType.LOOP) {
            from.trueNextId = to.id;
            result.flowEdges.add(new FlowEdge(from.id, to.id, ""));
        } else {
            addEdge(result, from, to);
        }
    }

    StreamExpansion expandStream(ParseResult result, Statement stmt, double x, Layout layout) {
        List<MethodCallExpr> chain = collectChain(stmt);
        if (chain.size() < 2) {
            FlowNode fb = createProcessNode(stmt, x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
            result.flowNodes.add(fb);
            layout.currentY += layout.verticalGap;
            return new StreamExpansion(fb, fb, fb);
        }

        // Source name: scope of the stream() call (chain[0])
        String sourceName = chain.get(0).getScope()
                .filter(s -> s instanceof NameExpr)
                .map(s -> ((NameExpr) s).getNameAsString())
                .orElse("collection");

        // Lambda param: from the first lambda in any intermediate op
        String param = "x";
        for (MethodCallExpr mce : chain) {
            if (!mce.getArguments().isEmpty() && mce.getArguments().get(0) instanceof LambdaExpr) {
                param = lambdaParam(mce);
                break;
            }
        }

        // Add the stream iteration variable so it appears in the Variables panel
        result.variables.putIfAbsent(param, 0);

        // Variable name being assigned (if any)
        String varName = stmt.findFirst(VariableDeclarator.class)
                .map(NodeWithSimpleName::getNameAsString).orElse(null);

        // 1. Init node
        FlowNode initNode = makeNode(sourceName + ".stream()", NodeType.PROCESS,
                x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
        result.flowNodes.add(initNode);
        layout.currentY += layout.verticalGap;

        // 2. Loop header
        FlowNode loopNode = makeNode("for each " + param + " in " + sourceName, NodeType.LOOP,
                x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
        result.flowNodes.add(loopNode);
        layout.currentY += layout.verticalGap;
        addEdge(result, initNode, loopNode);

        // 3. Intermediate operations (skip stream() at index 0 and terminal at last index)
        FlowNode prevBody = loopNode;
        boolean firstBody = true;
        List<FlowNode> filterNodes = new ArrayList<>();

        for (int i = 1; i < chain.size() - 1; i++) {
            MethodCallExpr mce = chain.get(i);
            String op = mce.getNameAsString();

            if (op.equals("stream")) continue;

            if (op.equals("filter")) {
                String predicate = lambdaBodyExpr(mce);
                FlowNode filterNode = makeNode(predicate, NodeType.DECISION,
                        x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                filterNode.condition = predicate;
                result.flowNodes.add(filterNode);
                wireBodyEdge(result, prevBody, filterNode);
                if (firstBody) { loopNode.trueNextId = filterNode.id; firstBody = false; }
                filterNodes.add(filterNode);
                prevBody = filterNode;
                layout.currentY += layout.verticalGap;

            } else if (op.startsWith("map") || op.equals("peek") || op.equals("flatMap")) {
                List<Statement> bodyStmts = lambdaBodyStmts(mce);
                if (bodyStmts.isEmpty()) {
                    // no-arg or non-lambda op — single node
                    FlowNode opNode = makeNode(op + ": " + lambdaBodyExpr(mce), NodeType.PROCESS,
                            x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                    opNode.expression = opNode.label;
                    result.flowNodes.add(opNode);
                    wireBodyEdge(result, prevBody, opNode);
                    if (firstBody) { loopNode.trueNextId = opNode.id; firstBody = false; }
                    prevBody = opNode;
                    layout.currentY += layout.verticalGap;
                } else if (bodyStmts.size() == 1 && !(bodyStmts.get(0) instanceof BlockStmt)) {
                    // Single-expression lambda
                    String label = op + ": " + lambdaBodyExpr(mce);
                    FlowNode opNode = makeNode(label, NodeType.PROCESS,
                            x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                    opNode.expression = label;
                    result.flowNodes.add(opNode);
                    wireBodyEdge(result, prevBody, opNode);
                    if (firstBody) { loopNode.trueNextId = opNode.id; firstBody = false; }
                    prevBody = opNode;
                    layout.currentY += layout.verticalGap;
                } else {
                    // Block lambda — expand each statement
                    for (Statement bStmt : bodyStmts) {
                        String label = trimSemi(bStmt.toString());
                        FlowNode bNode = makeNode(label, NodeType.PROCESS,
                                x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
                        bNode.expression = label;
                        result.flowNodes.add(bNode);
                        wireBodyEdge(result, prevBody, bNode);
                        if (firstBody) { loopNode.trueNextId = bNode.id; firstBody = false; }
                        prevBody = bNode;
                        layout.currentY += layout.verticalGap;
                    }
                }
            }
        }

        // Back-edge: last body node → loop header
        FlowNode lastBody = prevBody == loopNode ? null : prevBody;
        if (lastBody != null) {
            FlowEdge backEdge = new FlowEdge(lastBody.id, loopNode.id, "");
            backEdge.isBackEdge = true;
            lastBody.nextId = loopNode.id;
            result.flowEdges.add(backEdge);
        }

        // Filter false → back to loop (skip element)
        for (FlowNode f : filterNodes) {
            FlowEdge filterBack = new FlowEdge(f.id, loopNode.id, "False");
            filterBack.isBackEdge = true;
            f.falseNextId = loopNode.id;
            result.flowEdges.add(filterBack);
        }

        // 4. Terminal node
        String terminalName = chain.get(chain.size() - 1).getNameAsString();
        // Pre-populate a default return value of 0 so the variable isn't set to null
        result.mockReturnValues.putIfAbsent(terminalName, 0);
        String terminalLabel = varName != null ? varName + " = " + terminalName + "()" : terminalName + "()";
        FlowNode terminalNode = makeNode(terminalLabel, NodeType.PROCESS,
                x, layout.currentY, layout.nodeWidth, layout.nodeHeight);
        terminalNode.expression = terminalLabel;
        result.flowNodes.add(terminalNode);
        loopNode.falseNextId = terminalNode.id;
        FlowEdge exitEdge = new FlowEdge(loopNode.id, terminalNode.id, "False");
        result.flowEdges.add(exitEdge);
        layout.currentY += layout.verticalGap;

        // Dashed bounding box: loopNode (top) → terminalNode (bottom).
        // Left margin covers PROCESS back-edges (detourX = x - 55).
        // Right margin covers LOOP false-exit detour (detourX = x + width + 70).
        double leftMargin  = 55 + 10;   // back-edge detour + padding
        double rightMargin = 70 + 10;   // LOOP exit detour + padding
        double vertPad     = 14;
        result.streamGroups.add(new StreamGroup(
                loopNode.x - leftMargin,
                loopNode.y - vertPad,
                loopNode.width + leftMargin + rightMargin,
                (terminalNode.y + terminalNode.height) - loopNode.y + 2 * vertPad
        ));

        return new StreamExpansion(initNode, terminalNode, loopNode);
    }

    // ── End stream expansion ─────────────────────────────────────────────────

    private FlowNode createProcessNode(Statement stmt, double x, double y, double width, double height) {
        NodeType type = stmt instanceof ReturnStmt ? NodeType.END : NodeType.PROCESS;
        String label = stripComments(stmt.toString());
        FlowNode node = new FlowNode(
                UUID.randomUUID().toString(),
                label,
                type,
                x, y, width, height
        );
        if (type == NodeType.PROCESS) node.expression = label;
        node.beginLine = stmt.getBegin().map(p -> p.line).orElse(0);
        node.endLine   = stmt.getEnd().map(p -> p.line).orElse(0);
        return node;
    }

    private static Object defaultValue(String typeName) {
        return switch (typeName) {
            case "int", "long", "short", "byte" -> 0;
            case "double", "float" -> 0.0;
            case "boolean" -> false;
            default -> null;
        };
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
