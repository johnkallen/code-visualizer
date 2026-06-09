package com.codevisualizer.model;

import java.util.UUID;

public class FlowEdge {
    public String fromId;
    public String toId;
    public String label;
    private final String id;

    public FlowEdge(String fromId, String toId, String label) {
        this.fromId = fromId;
        this.toId = toId;
        this.label = label;
        this.id = UUID.randomUUID().toString(); // Unique ID
    }

    public String key() {
        return id;
    }
}