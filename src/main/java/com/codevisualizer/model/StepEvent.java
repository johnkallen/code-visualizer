package com.codevisualizer.model;

public class StepEvent {
    public enum StepType {
        NODE,
        EDGE,
        COMPLETE
    }

    public StepType type;
    public String nodeId;
    public String edgeFromId;
    public String edgeToId;

    public static StepEvent node(String nodeId) {
        StepEvent e = new StepEvent();
        e.type = StepType.NODE;
        e.nodeId = nodeId;
        return e;
    }

    public static StepEvent edge(String fromId, String toId) {
        StepEvent e = new StepEvent();
        e.type = StepType.EDGE;
        e.edgeFromId = fromId;
        e.edgeToId = toId;
        return e;
    }

    public static StepEvent complete() {
        StepEvent e = new StepEvent();
        e.type = StepType.COMPLETE;
        return e;
    }
}