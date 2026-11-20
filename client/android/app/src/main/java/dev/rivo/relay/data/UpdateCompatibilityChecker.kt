package dev.rivo.relay.data

import android.content.Context
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.PairingRepository
import dev.rivo.relay.connection.NodeConnection
import kotlinx.coroutines.*

object UpdateCompatibilityChecker {
    suspend fun check(context: Context, release: AppRelease): UpdateCompatibility {
        val pairing = PairingRepository(CredentialStore(context))
        return checkCurrent(pairing.restore(), release.apiVersions)
    }

    internal suspend fun checkCurrent(connection: NodeConnection?, candidate: Set<Int>?,
        read: suspend (NodeConnection) -> org.json.JSONObject = { it.api().call("/v1/node", reconnectProbe = true) },
    ): UpdateCompatibility {
        val current = connection?.let { active ->
            inspect(active.nodeId, candidate) { read(active) }
        }
        return UpdateCompatibility(current, candidate != null)
    }

    internal suspend fun inspect(id: String, candidate: Set<Int>?, read: suspend () -> org.json.JSONObject): NodeCompatibility = try {
        assessNode(id, candidate, withTimeout(12_000) { read() })
    } catch (e: TimeoutCancellationException) {
        // A timeout owned by an outer caller must remain cancellation, not an offline result.
        currentCoroutineContext().ensureActive()
        NodeCompatibility(CompatibilityState.UNKNOWN, "节点检查超时，暂缓安装")
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        NodeCompatibility(CompatibilityState.UNKNOWN, "无法读取节点声明，请检查连接与授权")
    }
}
