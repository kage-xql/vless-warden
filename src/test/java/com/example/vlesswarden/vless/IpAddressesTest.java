package com.example.vlesswarden.vless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IpAddressesTest {
    @Test
    void normalizesIPv4() {
        assertEquals("1.2.3.4", IpAddresses.normalize("1.2.3.4"));
        assertEquals("1.2.3.4", IpAddresses.normalize(" 1.2.3.4 "));
        assertEquals("1.2.3.4", IpAddresses.normalize("[1.2.3.4]"));
    }

    @Test
    void normalizesIPv6To64() {
        assertEquals("2001:db8:0:0::/64", IpAddresses.normalize("2001:db8::1"));
        assertEquals("2001:db8:0:0::/64", IpAddresses.normalize("[2001:db8::dead:beef]"));
    }

    @Test
    void normalizesV4MappedIPv6() {
        assertEquals("1.2.3.4", IpAddresses.normalize("::ffff:1.2.3.4"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.normalize("not-an-ip"));
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.normalize(null));
    }
}
