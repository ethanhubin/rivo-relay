package dev.rivo.relay.data

import android.content.Context
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.PairingRepository
import kotlinx.coroutines.*

object UpdateCompatibilityChecker {
    suspend fun check(context: Context, release: AppRelease): UpdateCompatibility {
        val pairing = PairingRepository(CredentialStore(context))
        val saved = pairing.boundNodes()
        val results = coroutineScope {
            saved.map { savedNode -> async {
                val connection = pairing.connection(savedNode.base)
                if (connection == null) NodeCompatibility(savedNode.base, savedNode.name, CompatibilityState.UNKNOWN, "授权已变化，请重新检查")
                else inspect(connection.nodeId, connection.name, release.apiVersions) {
                    connection.api().call("/v1/node", reconnectProbe = true)
                }
            } }.awaitAll()
        }
        return UpdateCompatibility(results, release.apiVersions != null)
    }

    internal suspend fun inspect(id: String, name: String, candidate: Set<Int>?, read: suspend () -> org.json.JSONObject): NodeCompatibility = try {
        assessNode(id, name, candidate, withTimeout(12_000) { read() })
    } catch (e: TimeoutCancellationException) {
        // A timeout owned by an outer caller must remain cancellation, not an offline result.
        currentCoroutineContext().ensureActive()
        NodeCompatibility(id, name, CompatibilityState.UNKNOWN, "节点检查超时，暂缓安装")
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        NodeCompatibility(id, name, CompatibilityState.UNKNOWN, "无法读取节点声明，请检查连接与授权")
    }
}
