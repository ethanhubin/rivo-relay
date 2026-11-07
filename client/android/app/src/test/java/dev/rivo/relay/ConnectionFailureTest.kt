package dev.rivo.relay

import dev.rivo.relay.data.connectionFailure
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ConnectionFailureTest {
    @Test fun failuresAreActionableAndNeverRevealSecrets() {
        val base = "https://secret-user:secret-password@node.example:18766/private?token=secret-token"
        val message = connectionFailure(IOException("secret-token", UnknownHostException("secret-password")), base)
        assertTrue(message.contains("DNS"))
        assertTrue(message.contains("node.example:18766"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("private"))
        assertTrue(connectionFailure(SSLHandshakeException("secret")).contains("TLS"))
        assertTrue(connectionFailure(IOException("EACCES secret")).contains("联网权限"))
    }
}
