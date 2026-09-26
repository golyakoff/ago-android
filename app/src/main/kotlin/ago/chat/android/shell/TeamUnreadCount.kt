package ago.chat.android.shell

import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.session.OperatorIdentityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-180`: Команда's own footer-badge count — "how many team messages have arrived from someone else
 * since Команда was last open", matching `ago-console`'s own `TeamChatUnreadProvider` (`workspace/`)
 * fact-for-fact: **session-only, client-local, with no server-side team read-receipt at all.** [count]
 * resets on process death exactly the way the console's own `useState(0)` does on a page reload — a
 * durable version would need a new `ago-chat` read-receipt store for the team room (out of scope here,
 * same as it is for the console; a future item's to build, not this one's).
 *
 * **Why a plain class next to [AppShellViewModel], not a port behind an interface the way
 * [ago.chat.android.data.bookings.PendingBookingsCount] is.** That port exists because
 * [ago.chat.android.data.bookings.PendingBookingsPoller] talks to a real backend
 * ([ago.chat.android.core.domain.bookings.BookingsApi]) an `AppShellViewModelTest` fake needs to stand in
 * for without a network. This class already depends on nothing but two interfaces
 * ([OperatorHubEvents]/[OperatorIdentityProvider]) `AppShellViewModelTest` already fakes for other
 * reasons — wrapping it in a third interface would buy no testability this file does not already have,
 * only one more layer to read through.
 *
 * `public`, not `internal`, for the identical reason [ago.chat.android.data.bookings.PendingBookingsCount]'s
 * own doc comment states: this class sits in [AppShellViewModel]'s own public constructor, and Kotlin
 * forbids a public signature from exposing a less-visible type.
 *
 * **Why [count] is `StateFlow<Int>`, never `Int?`.** [ago.chat.android.data.conversations.ConversationsUnreadTotal]
 * and [ago.chat.android.data.bookings.PendingBookingsCount] both start `null` because "not loaded yet" is
 * a real, distinct state for a number a server has to answer first. This count has no such state: from
 * the moment [AppShellViewModel] exists, "zero team messages have arrived since Команда was last open" is
 * already a true, known fact — there is nothing to wait for.
 *
 * **Compared by email, not by [ago.chat.android.core.network.realtime.TeamMessageDto.authorOperatorId].**
 * `TeamChatUnreadProvider` excludes an operator's own echoed-back send by comparing
 * `message.authorOperatorId` against `usePermissions().operatorId` — a stable id the console's own
 * session already holds. [OperatorIdentityProvider] (this app's identical fact) carries no such id: it
 * reads only `name`/`email` out of the signed-in operator's own ID token
 * ([OperatorIdentity]'s own doc comment — the two claims this app ever asked Keycloak for), so [start]
 * compares [OperatorIdentity.email] instead. A `null` on either side (no `email` claim on this token, or
 * a team-room row the server could not resolve an author email for) is read as "cannot confirm this is
 * mine" and the message counts as unread — the safe direction to guess wrong in, since the cost is one
 * extra badge digit until Команда is opened, never a genuinely new message silently swallowed as if it
 * were the operator's own.
 */
public class TeamUnreadCount
    @Inject
    constructor(
        private val hubEvents: OperatorHubEvents,
        private val identityProvider: OperatorIdentityProvider,
    ) {
        private val mutableCount = MutableStateFlow(0)
        public val count: StateFlow<Int> = mutableCount.asStateFlow()

        /** `true` for exactly as long as Команда is the active tab — set by [setActive], read only from
         * inside the [hubEvents.teamMessages] collector [start] launches. Plain `var`, not a `StateFlow`:
         * both reader and writer run on the same `viewModelScope` dispatcher
         * (`ago.chat.android.bookings.BookingsViewModel`'s own `busyBookingIds` doc comment states the
         * identical "no lock needed" reasoning for the identical single-dispatcher shape). */
        private var isTeamTabActive = false

        /**
         * Starts collecting [OperatorHubEvents.teamMessages] for the caller's own [scope] — the shell's
         * own `viewModelScope`, so this runs for as long as the shell itself is alive, counting messages
         * that arrive while Команда is *not* the active tab (`TeamChatUnreadProvider`'s own "the badge
         * exists for every other route, not this one" rule). [OperatorIdentityProvider.currentIdentity]
         * is read once, here, rather than on every message — the identical "this app already asks
         * Keycloak... once per session" cost [AppShellViewModel]'s own `mutableIdentity` load already
         * pays, not a second one.
         */
        public fun start(scope: CoroutineScope) {
            scope.launch {
                val myEmail = identityProvider.currentIdentity()?.email
                hubEvents.teamMessages.collect { message ->
                    // Команда renders this message directly while it is open - the badge exists for
                    // every *other* route, not this one (`TeamChatUnreadProvider`'s own `isOpenRef`
                    // check, restated).
                    if (isTeamTabActive) return@collect
                    // This operator's own sent message is echoed back through the identical push every
                    // other operator's arrives through (`OperatorHubEvents.sendTeamMessage`'s own
                    // remarks: "the caller's own tab learns the outcome through its own... push") - the
                    // same "an author's own echoed-back send is not unread to them" exclusion this
                    // class's own doc comment states, worded there in full.
                    if (myEmail != null && message.authorEmail == myEmail) return@collect
                    mutableCount.update { it + 1 }
                }
            }
        }

        /**
         * [AppShellContent]'s own signal for "Команда just became (or just stopped being) the active
         * tab" — `true` both resets [count] to zero and suppresses further counting while it stays
         * `true`; `false` resumes counting, so a message that arrives after the operator has moved to a
         * different tab is not lost. The identical two-effect `notifyOpen(open)` shape
         * `TeamChatUnreadProvider`'s own doc comment states in full, restated here for one flag instead
         * of a ref plus a `useState` setter.
         */
        public fun setActive(active: Boolean) {
            isTeamTabActive = active
            if (active) mutableCount.value = 0
        }
    }
