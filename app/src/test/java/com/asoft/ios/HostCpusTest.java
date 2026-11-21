package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HostCpusTest {
    @Test
    public void theAllowedListIsReadFromTheStatusLine() {
        assertEquals("0-3", HostCpus.parse("Cpus_allowed_list:\t0-3"));
        assertEquals("0-7", HostCpus.parse("Cpus_allowed_list: 0-7"));
        assertEquals("0,2,4-6", HostCpus.parse("Cpus_allowed_list:\t0,2, 4-6"));
    }

    @Test
    public void somethingElseIsNoList() {
        assertEquals("", HostCpus.parse("Mems_allowed_list:\t0"));
        assertEquals("", HostCpus.parse(null));
        assertEquals("", HostCpus.parse("Cpus_allowed_list:\tall"));
    }
}
