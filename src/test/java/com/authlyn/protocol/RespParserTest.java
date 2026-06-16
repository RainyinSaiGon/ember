package com.authlyn.protocol;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class RespParserTest {

    @Test
    @Disabled("implement in Track 0 Stage 2")
    void parsesInlinePing() {
        // TODO: assert inline PING\r\n is parsed to a PING command
    }

    @Test
    @Disabled("implement in Track 0 Stage 2")
    void parsesArrayPing() {
        // TODO: assert *1\r\n$4\r\nPING\r\n is parsed to a PING command
    }
}