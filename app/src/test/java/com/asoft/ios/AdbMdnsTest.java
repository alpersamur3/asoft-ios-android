package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class AdbMdnsTest {

    @Test
    public void nullAddressIsNotOwn() {
        assertFalse(AdbMdns.ownAddress(null, Collections.emptyList()));
    }

    @Test
    public void loopbackAddressIsOwn() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertTrue(AdbMdns.ownAddress(loopback, Collections.emptyList()));
    }

    @Test
    public void ipv4MatchesLocalAddress() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 10});
        InetAddress same = InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 10});
        InetAddress other = InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 11});
        List<InetAddress> locals = Collections.singletonList(local);

        assertTrue(AdbMdns.ownAddress(same, locals));
        assertFalse(AdbMdns.ownAddress(other, locals));
    }

    @Test
    public void ipv6MatchesAcrossDifferentScopeIds() throws Exception {
        byte[] linkLocalBytes = new byte[] {
            (byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        };
        Inet6Address localWithScope = Inet6Address.getByAddress(null, linkLocalBytes, 2);
        Inet6Address resolvedNoScope = Inet6Address.getByAddress(null, linkLocalBytes, 0);

        assertTrue(AdbMdns.ownAddress(resolvedNoScope, Collections.singletonList(localWithScope)));
    }
}
