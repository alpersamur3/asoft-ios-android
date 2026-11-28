package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Test;

public final class AppExitTest {

    @Test
    public void writeAtomicPersistsPropertiesWithoutLeavingTempFile() throws Exception {
        File dir = Files.createTempDirectory("appexit-test").toFile();
        File target = new File(dir, "in-flight.properties");
        File tmp = new File(target.getPath() + ".tmp");
        try {
            Properties out = new Properties();
            out.setProperty("guest", "iOS 16");
            out.setProperty("pid", "12345");

            assertTrue(AppExit.writeAtomic(target, out));
            assertTrue(target.isFile());
            assertFalse(tmp.exists());

            Properties in = new Properties();
            try (InputStream is = new FileInputStream(target)) {
                in.load(is);
            }
            assertEquals("iOS 16", in.getProperty("guest"));
            assertEquals("12345", in.getProperty("pid"));
        } finally {
            tmp.delete();
            target.delete();
            dir.delete();
        }
    }
}
