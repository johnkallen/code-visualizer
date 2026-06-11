package com.codevisualizer.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionContext {
    public Map<String, Object> variables = new LinkedHashMap<>();
    public Map<String, List<Object>> loopElements = new HashMap<>();
    public Map<String, Integer> loopIndex = new HashMap<>();
    public String currentNodeId;
    public boolean finished = false;
}