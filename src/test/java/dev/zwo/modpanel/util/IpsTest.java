package dev.zwo.modpanel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IpsTest {

    @Test
    void normalizesLiterals() {
        assertEquals("192.168.0.1", Ips.normalize("192.168.0.1"));
        assertEquals("10.0.0.5", Ips.normalize("/10.0.0.5"));
        assertEquals("fe80:0:0:0:0:0:0:1", Ips.normalize("fe80::1%eth0"));
    }

    @Test
    void rejectsHostnamesAndInvalidAddresses() {
        assertNull(Ips.normalize("256.1.1.1"));
        assertNull(Ips.normalize("1.2.3"));
        assertNull(Ips.normalize("exemplo.com"));
    }
}
