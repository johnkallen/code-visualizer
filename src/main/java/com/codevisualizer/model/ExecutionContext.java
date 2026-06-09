package com.codevisualizer.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionContext {
    public Map<String, Object> variables = new LinkedHashMap<>();
    public String currentNodeId;
    public boolean finished = false;
}