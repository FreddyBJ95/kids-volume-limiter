package dk.kidsvolume.limiter.volume

import dk.kidsvolume.limiter.settings.CorrectionEntry
import java.util.concurrent.CopyOnWriteArraySet

/** Simpel event-bus inden for app-processen (service, plugin og selvtest deler den). */
object LimiterEvents {

    sealed interface Event {
        /** Lydstyrke, lydudgang eller indstillinger er ændret. */
        data object StateChanged : Event

        /** Lydstyrken blev sænket automatisk. */
        data class Corrected(val entry: CorrectionEntry) : Event
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun emit(event: Event) {
        listeners.forEach { it.onEvent(event) }
    }
}
