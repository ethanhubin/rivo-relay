package dev.rivo.relay.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Follow the actual default route, including Wi-Fi/cellular switches, without probing public hosts. */
fun defaultNetwork(context: Context): Network? = context.getSystemService(ConnectivityManager::class.java).activeNetwork

fun observeDefaultNetwork(context: Context) = callbackFlow<Network?> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val lock = Any()
    var current = manager.activeNetwork
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { synchronized(lock) { current = network; trySend(network) } }
        override fun onLost(network: Network) {
            // activeNetwork can still report the lost route during this callback.
            synchronized(lock) { if (current == network) { current = null; trySend(null) } }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    synchronized(lock) { trySend(current) }
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
