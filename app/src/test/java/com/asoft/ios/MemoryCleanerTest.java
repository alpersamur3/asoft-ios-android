package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemoryCleanerTest {
    @Test
    public void theTopOfDumpsysMeminfoIsReadable() {
        String d = "Total PSS by process:\n"
                + "  1,228,800K: com.foo (pid 12)\n"
                + "  204,800K: com.bar (pid 13 / activities)\n"
                + "Total PSS by category:\n"
                + "  400,000K: .so mmap\n";
        assertEquals("com.foo — 1200 MB\ncom.bar — 200 MB", MemoryCleaner.topLines(d, 6));
        assertEquals("com.foo — 1200 MB", MemoryCleaner.topLines(d, 1));
        assertEquals("", MemoryCleaner.topLines("nothing here", 6));
    }

    @Test
    public void theDumpsysSummarySaysWhatIsNotInTheTopList() {
        String d = "Total RAM: 11,700,000K (status normal)\n"
                + "Free RAM: 4,900,000K (cached 1,000,000K)\n"
                + "Used RAM: 5,500,000K (5,000,000K kernel)\n"
                + "Lost RAM: 1,200,000K\n"
                + "ZRAM: 2,000,000K physical used for 4,000,000K in swap (6,000,000K total swap)\n";
        assertEquals("Used 5371 MB · Free 4785 MB · Lost 1172 MB · ZRAM 1953 MB (swap 3906 MB)",
                MemoryCleaner.summaryLines(d));
        assertEquals("", MemoryCleaner.summaryLines("nothing here"));
    }
}
