package com.logboard.crop;

/** api-contract {@code kind}: ANCHOR = the anchor entry's header line, STACK = its continuation lines. */
public enum LineKind {
    CONTEXT,
    ANCHOR,
    STACK;

    /** Kind of line {@code no} when the anchor entry spans {@code anchorLine..anchorEndLine}. */
    public static LineKind of(int no, int anchorLine, int anchorEndLine) {
        if (no == anchorLine) {
            return ANCHOR;
        }
        return no > anchorLine && no <= anchorEndLine ? STACK : CONTEXT;
    }
}
