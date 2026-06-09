package com.codevisualizer.export;

import com.codevisualizer.model.FlowNode;

import java.util.List;

public class DrawIoExporter {

    public String export(List<FlowNode> nodes) {

        StringBuilder xml = new StringBuilder();
        xml.append("<mxGraphModel><root>");

        int id = 1;
        for (FlowNode node : nodes) {
            xml.append("<mxCell id='")
                    .append(id++)
                    .append("' value='")
                    .append(node.label)
                    .append("'/>");
        }

        xml.append("</root></mxGraphModel>");

        return xml.toString();
    }

}
