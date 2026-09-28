package com.hmdm.util;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/** {@link FileUtil#resolveInside}: an upload's file name may only name a direct child of the target directory. */
public class FileUtilResolveInsideTest {

    private final File base;

    public FileUtilResolveInsideTest() throws Exception {
        base = Files.createTempDirectory("resolve-inside").toFile();
        base.deleteOnExit();
    }

    @Test
    public void plainNamesResolveInside() throws Exception {
        File f = FileUtil.resolveInside(base, "config.json");
        Assert.assertNotNull(f);
        Assert.assertEquals(base.getCanonicalFile(), f.getParentFile());
        Assert.assertNotNull(FileUtil.resolveInside(base, "app v2.apk"));
        Assert.assertNotNull(FileUtil.resolveInside(base, "ñandú.txt"));
    }

    @Test
    public void traversalAndSeparatorsAreRejected() {
        for (String bad : new String[] {"../x", "../../etc/passwd", "a/b", "a\\b", "..", ".", "", "   ", "x\u0000.txt", "x\n", "/abs"}) {
            Assert.assertNull(bad, FileUtil.resolveInside(base, bad));
        }
        Assert.assertNull(FileUtil.resolveInside(base, null));
        Assert.assertNull(FileUtil.resolveInside(null, "x"));
    }
}
