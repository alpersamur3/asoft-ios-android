package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RunLogsTest {
    private File dir;

    @Before
    public void dir() throws IOException {
        dir = Files.createTempDirectory("runlogs").toFile();
    }

    @After
    public void clean() {
        File[] f = dir.listFiles();
        if (f != null) {
            for (File x : f) {
                x.delete();
            }
        }
        dir.delete();
    }

    private File file(String s) throws IOException {
        File f = new File(dir, "serial.txt");
        Files.write(f.toPath(), s.getBytes(StandardCharsets.ISO_8859_1));
        return f;
    }

    @Test
    public void aWholeFileThatFitsIsAllThere() throws IOException {
        assertEquals("one\ntwo\n", RunLogs.tail(file("one\ntwo\n"), 4000, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void theTailDropsThePartialFirstLine() throws IOException {
        // from byte 2, inside "one": the rest of that line goes with it
        assertEquals("two\nthree\n", RunLogs.tail(file("one\ntwo\nthree\n"), 12, StandardCharsets.ISO_8859_1));
        // from byte 5, inside "two": "three" only
        assertEquals("three\n", RunLogs.tail(file("one\ntwo\nthree\n"), 9, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void theSizesAreWhatTheListShows() {
        assertEquals("1 KB", RunLogs.size(0));
        assertEquals("1 KB", RunLogs.size(999));
        assertEquals("2 KB", RunLogs.size(2000));
        assertEquals("1.5 MB", RunLogs.size(1_500_000));
    }
}
