package dev.rivo.relay.connection

import kotlinx.coroutines.Job

/** Main-thread owner of runtimes whose cancellation may still be saving recovery state. */
internal class NodeJobHandoff {
    private val retiring = mutableListOf<Job>()
    fun retire(job: Job?): List<Job> {
        job?.let { it.cancel(); retiring += it }
        retiring.removeAll { it.isCompleted }
        return retiring.toList()
    }
}
