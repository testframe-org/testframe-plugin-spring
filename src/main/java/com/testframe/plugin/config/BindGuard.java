package com.testframe.plugin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses to start bound to a non-loopback address without {@code testframe.token}
 * (docs/runner-protocol.md, "Security"): the plugin executes the calls it is asked to, so only
 * the loopback binding or the token keeps other machines from using it as a proxy.
 */
@Component
public class BindGuard {

    public BindGuard(@Value("${server.address:}") String address, @Value("${testframe.token:}") String token) {
        check(address, token);
    }

    /** IllegalStateException when {@code address} is not loopback and no token is set. */
    public static void check(String address, String token) {
        if (token != null && !token.isBlank()) {
            return;
        }
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("server.address is not set: the plugin binds to 127.0.0.1 unless testframe.token is set");
        }
        try {
            if (!InetAddress.getByName(address).isLoopbackAddress()) {
                throw new IllegalStateException("refusing to bind to non-loopback address " + address
                        + " without testframe.token (TESTFRAME_TOKEN)");
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException("unknown server.address " + address, e);
        }
    }
}
