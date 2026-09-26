package ago.chat.android.data.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-179`: [PendingBookingsCount]'s only implementation — a poll loop over
 * [BookingsApi.fetchPendingQueue], the same call [ago.chat.android.bookings.BookingsViewModel] makes for
 * the real Записи screen, asked again every [POLL_INTERVAL_MILLIS] instead of once per screen visit.
 *
 * **A cold [flow], not a `@Singleton` holding its own [kotlinx.coroutines.CoroutineScope].**
 * [ago.chat.android.shell.AppShellViewModel] is this port's one caller, and it already has a scope whose
 * lifetime is exactly right — `viewModelScope`, alive for as long as the shell itself is
 * ([ago.chat.android.shell.AppShellViewModel]'s own doc comment on why that scope outlives every
 * individual bottom-tab). `observeCount()` returning a `flow { }` builder means collecting it from
 * `viewModelScope.stateIn(..., SharingStarted.Eagerly, ...)` — the identical recipe
 * [ago.chat.android.data.conversations.ConversationsUnreadTotal] is read through — both starts the
 * polling loop and ties its lifetime to that scope with no separate `start()`/`stop()` method for a
 * caller to remember to pair up: cancelling `viewModelScope` cancels this `flow`'s own collection, which
 * is what stops the `while` loop below, the same way any other suspend call in that scope would be.
 * `Eagerly`, not `WhileSubscribed`, is what makes this run *while Записи is not the active tab* — the
 * bottom bar reads [ago.chat.android.shell.AppShellViewModel.pendingBookingsTotal] for as long as the
 * shell is on screen, so there is no "nobody is collecting" window in which this loop would be correct
 * to stop.
 */
internal class PendingBookingsPoller
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : PendingBookingsCount {
        override fun observeCount(): Flow<Int?> =
            flow {
                // `null` first - the "not loaded yet" answer [PendingBookingsCount.observeCount]'s own
                // doc comment promises, held until the very first poll below actually answers.
                var lastKnown: Int? = null
                emit(lastKnown)
                while (true) {
                    lastKnown = poll(lastKnown)
                    emit(lastKnown)
                    delay(POLL_INTERVAL_MILLIS)
                }
            }

        /**
         * One [BookingsApi.fetchPendingQueue] round trip, folded into the previous answer:
         * [PendingBookingsResult.Loaded] is the fresh count, [PendingBookingsResult.NotConfigured] is a
         * genuine, stable `0` (this deployment has no AGO Calendar backend at all - the identical fact
         * [PendingBookingsResult.NotConfigured]'s own doc comment states, restated here as "no bookings
         * are pending because there is no calendar to hold any"), and [PendingBookingsResult.Failed] keeps
         * whatever [current] already was - a transient fetch failure must never crash this loop, and must
         * never flash the badge back to "not loaded" either.
         */
        private suspend fun poll(current: Int?): Int? =
            when (val result = withContext(ioDispatcher) { api.fetchPendingQueue() }) {
                is PendingBookingsResult.Loaded -> result.bookings.size
                PendingBookingsResult.NotConfigured -> 0
                is PendingBookingsResult.Failed -> current
            }

        internal companion object {
            /** Sensible-interval polling, not real time — a badge that lags the real queue by up to this
             * long is the cost of not building a live push for a footer count (`docs/backlog/26-179-*.md`'s
             * own Scope: this is a poll, not a second `OperatorHubEvents`-style channel). Not `private`:
             * `PendingBookingsPollerTest` (same module) advances a `TestCoroutineScheduler` by exactly
             * this many milliseconds to prove the second poll actually happens, rather than repeating the
             * number as a second, driftable literal. */
            const val POLL_INTERVAL_MILLIS = 45_000L
        }
    }
