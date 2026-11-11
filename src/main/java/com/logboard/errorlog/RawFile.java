package com.logboard.errorlog;

/** One instance's raw copy of an error log: {@code path} relative to the storage root, its line count. */
record RawFile(String path, int totalLines) {}
