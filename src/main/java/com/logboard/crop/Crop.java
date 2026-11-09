package com.logboard.crop;

import java.util.List;

/** Lines {@code startLine..endLine} (1-based, inclusive) of the raw file, each labeled. */
public record Crop(int startLine, int endLine, int anchorLine, int totalLines, List<Line> lines) {

    public record Line(int no, String text, LineKind kind) {}

    /** The crop file content ({@code {root}/errors/{ID}.log}). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        lines.forEach(l -> sb.append(l.text()).append('\n'));
        return sb.toString();
    }
}
