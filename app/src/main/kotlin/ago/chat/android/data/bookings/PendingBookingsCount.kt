package ago.chat.android.data.bookings

import kotlinx.coroutines.flow.Flow

/**
 * `26-179`: "how many bookings are waiting to auto-confirm right now" — the one fact Записи's own
 * footer badge needs, observable from [ago.chat.android.shell.AppShellViewModel] without hoisting
 * [ago.chat.android.bookings.BookingsViewModel] out of its own nav entry (the identical "the badge's
 * source must survive a tab switch, the screen's own view model does not" reasoning
 * [ago.chat.android.data.conversations.ConversationsUnreadTotal]'s own doc comment states for Диалоги).
 *
 * **Not the same shape as [ago.chat.android.data.conversations.ConversationsUnreadTotal] underneath,
 * on purpose.** That port is a passive read over a Room table every write already keeps current — there
 * is no local cache of the pending-booking queue for this port to read the same way, and building one
 * only for a badge would be a second, independently-timed copy of the exact queue
 * [ago.chat.android.bookings.BookingsViewModel] already fetches for real when Записи is open
 * (`docs/architecture/caching.md`'s own "never cache what a write decision depends on" is not this
 * badge's concern — a stale count for a few seconds is cosmetic, not a write-safety hazard — but a
 * second source of truth for the same number is still worth avoiding). [PendingBookingsPoller] is this
 * port's only implementation: a small, `:app`-local poll loop over
 * [ago.chat.android.core.domain.bookings.BookingsApi.fetchPendingQueue] — the same call
 * [ago.chat.android.bookings.BookingsViewModel] makes for the real screen, just asked on a timer instead
 * of on that screen's own `refresh()`/`init`.
 *
 * `public`, not `internal`, for the identical reason [ConversationsUnreadTotal] states: this interface
 * sits in [ago.chat.android.shell.AppShellViewModel]'s own public constructor, and Kotlin forbids a
 * public signature from exposing a less-visible type.
 */
public interface PendingBookingsCount {
    /**
     * `null` until the first [ago.chat.android.core.domain.bookings.BookingsApi.fetchPendingQueue] call
     * this instance ever makes has answered — the identical "never loaded" vs "loaded, and genuinely
     * holds nothing" distinction [ConversationsUnreadTotal.observeTotal]'s own doc comment draws, so a
     * caller renders no badge for either "unknown" or a real "0" pending bookings.
     *
     * Live, on a timer: [PendingBookingsPoller] re-asks
     * [ago.chat.android.core.domain.bookings.BookingsApi.fetchPendingQueue] on its own interval for as
     * long as this [Flow] is being collected — starting the collection (from
     * [ago.chat.android.shell.AppShellViewModel]'s own `viewModelScope`) is what starts the polling, and
     * cancelling that scope is what stops it, with no separate start/stop call needed on this interface.
     */
    public fun observeCount(): Flow<Int?>
}
