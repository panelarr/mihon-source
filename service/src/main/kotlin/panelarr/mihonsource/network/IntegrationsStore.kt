// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single place network configuration comes from: solvers, profiles, assignments and
 * profiles pushed by a host. Today it's backed by local files edited through the admin
 * API; a host-driven source of configuration can implement the same interface later.
 */
interface IntegrationsStore {
    fun state(): IntegrationsState

    fun putSolver(solver: SolverSettings): SolverSettings

    fun deleteSolver(id: String)

    fun putProfile(profile: ProfileSettings): ProfileSettings

    fun deleteProfile(id: String)

    fun assign(
        sourceId: Long,
        profileId: String?,
    )

    fun setDefaultProfile(profileId: String?)

    fun pushed(sourceId: Long): PushedProfile?

    fun pushedAll(): Map<Long, PushedProfile>

    fun setPushed(
        sourceId: Long,
        profile: PushedProfile?,
    )

    /** Called after every change. */
    fun addListener(listener: () -> Unit)
}

/** [IntegrationsStore] kept in `config/integrations.json` and `config/pushed-profiles.json`. */
class FileIntegrationsStore(
    configDir: Path,
) : IntegrationsStore {
    private val file = JsonFile(configDir.resolve("integrations.json"), IntegrationsState.serializer(), private = true)
    private val pushedFile =
        JsonFile(
            configDir.resolve("pushed-profiles.json"),
            MapSerializer(String.serializer(), PushedProfile.serializer()),
            private = true,
        )
    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var state: IntegrationsState = file.read() ?: IntegrationsState()

    @Volatile
    private var pushed: Map<Long, PushedProfile> =
        pushedFile.read()?.mapKeys { it.key.toLong() } ?: emptyMap()

    override fun state(): IntegrationsState = state

    override fun putSolver(solver: SolverSettings): SolverSettings {
        Ids.require(solver.id)
        update { it.copy(solvers = it.solvers.filterNot { s -> s.id == solver.id } + solver) }
        return solver
    }

    override fun deleteSolver(id: String) {
        update { current ->
            if (current.solvers.none { it.id == id }) throw NoSuchElementException("solver '$id' not found")
            val users = current.profiles.filter { it.solverId == id }.map { it.id }
            check(users.isEmpty()) { "solver '$id' is used by profiles: ${users.joinToString()}" }
            current.copy(solvers = current.solvers.filterNot { it.id == id })
        }
    }

    override fun putProfile(profile: ProfileSettings): ProfileSettings {
        Ids.require(profile.id)
        update { current ->
            profile.solverId?.let { solverId ->
                require(current.solvers.any { it.id == solverId }) { "solver '$solverId' not found" }
            }
            current.copy(profiles = current.profiles.filterNot { it.id == profile.id } + profile)
        }
        return profile
    }

    override fun deleteProfile(id: String) {
        update { current ->
            if (current.profiles.none { it.id == id }) throw NoSuchElementException("profile '$id' not found")
            current.copy(
                profiles = current.profiles.filterNot { it.id == id },
                assignments = current.assignments.filterValues { it != id },
                defaultProfileId = current.defaultProfileId.takeUnless { it == id },
            )
        }
    }

    override fun assign(
        sourceId: Long,
        profileId: String?,
    ) {
        update { current ->
            if (profileId == null) {
                current.copy(assignments = current.assignments - sourceId.toString())
            } else {
                require(current.profiles.any { it.id == profileId }) { "profile '$profileId' not found" }
                current.copy(assignments = current.assignments + (sourceId.toString() to profileId))
            }
        }
    }

    override fun setDefaultProfile(profileId: String?) {
        update { current ->
            profileId?.let { id -> require(current.profiles.any { it.id == id }) { "profile '$id' not found" } }
            current.copy(defaultProfileId = profileId)
        }
    }

    override fun pushed(sourceId: Long): PushedProfile? = pushed[sourceId]

    override fun pushedAll(): Map<Long, PushedProfile> = pushed

    override fun setPushed(
        sourceId: Long,
        profile: PushedProfile?,
    ) {
        lock.withLock {
            pushed = if (profile == null) pushed - sourceId else pushed + (sourceId to profile)
            pushedFile.write(pushed.mapKeys { it.key.toString() })
        }
        listeners.forEach { it() }
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun update(change: (IntegrationsState) -> IntegrationsState) {
        lock.withLock {
            val next = change(state)
            file.write(next)
            state = next
        }
        listeners.forEach { it() }
    }
}

/** Ids of solvers and profiles: short, lowercase, URL-safe. */
object Ids {
    private val VALID = Regex("[a-z0-9][a-z0-9-]{0,62}")

    fun require(id: String) {
        require(VALID.matches(id)) { "invalid id '$id': use lowercase letters, digits and dashes (max 63)" }
    }
}
