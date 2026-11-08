package dev.rivo.relay

import dev.rivo.relay.data.connectionFailure
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ConnectionFailureTest {
    @Test fun failureAddressRespectsForcedRoute() {
        val base = "http://100.124.150.1:18767"
        val routes = dev.rivo.relay.connection.NodeRoutes(direct = base, relay = "https://vps.example/relay/private")
        for (mode in dev.rivo.relay.connection.ConnectionMode.entries) {
            val api = dev.rivo.relay.data.RelayApi(base, routes = routes.copy(mode = mode))
            val message = api.describeFailure(IOException("secret"))
            assertEquals(mode == dev.rivo.relay.connection.ConnectionMode.DIRECT, message.contains("100.124.150.1"))
            assertEquals(mode == dev.rivo.relay.connection.ConnectionMode.RELAY, message.contains("vps.example"))
            assertFalse(message.contains("private"))
            assertFalse(message.contains("已提交操作"))
        }
    }

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
