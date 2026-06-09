package com.codevisualizer.model;

import com.codevisualizer.enums.NodeType;

public class FlowNode {
    public String id;
    public String label;
    public NodeType type;
    public double x;
    public double y;
    public double width;
    public double height;

    // source location (1-based, maps directly to CodeArea paragraph index via line-1)
    public int beginLine = 0;
    public int endLine   = 0;

    // execution metadata
    public String variableName;
    public String expression;
    public String condition;

    public String nextId;
    public String trueNextId;
    public String falseNextId;

    public FlowNode(String id, String label, NodeType type, double x, double y, double width, double height) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}