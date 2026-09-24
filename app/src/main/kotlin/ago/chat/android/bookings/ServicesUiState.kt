package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService

/**
 * `26-96`: [ServicesViewModel]'s whole state — the identical four-arm shape
 * [BookingsUiState]/[ConfirmedBookingsUiState]/[ContactsUiState] already establish, restated rather
 * than shared because [Loaded] carries [ConfiguredService].
 */
internal sealed interface ServicesUiState {
    data object Loading : ServicesUiState

    /**
     * @param editing the service whose edit form is open, or `null` for none. A *copy* the form edits
     *   freely, not a reference into [services]: the list stays exactly what the server last said
     *   while an operator types, so cancelling an edit needs no undo and a mid-edit refresh cannot
     *   half-apply. [ServiceDraft] carries the typed values, which are strings rather than parsed
     *   numbers for the same reason `ago-console`'s own form holds a `priceRubles` string - a
     *   half-typed price is a real state a data class of `Int?` cannot hold.
     * @param busyServiceIds which services have a write in flight — a *set*, not a single nullable id,
     *   for the identical reason [ContactsUiState.Loaded.revealingCustomerIds]'s own doc comment
     *   states: a second row must stay tappable while a first is still out on the network.
     */
    data class Loaded(
        val services: List<ConfiguredService>,
        val editing: ServiceDraft? = null,
        val busyServiceIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
    ) : ServicesUiState

    data object NotConfigured : ServicesUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ServicesUiState
}

/**
 * `26-96`: what the edit form currently holds — the five editable fields plus the on-offer flag,
 * carried as the strings a text field actually contains.
 *
 * **[durationMinutes] and [priceRubles] are `String`, deliberately.** An operator clearing a field to
 * retype it passes through "" and "4"; an `Int?` cannot represent the first without meaning "no
 * duration" and cannot hold the second as a stepping stone to "45" without the field fighting back.
 * The parse happens once, at submit ([ServiceDraft.durationMinutesOrNull]/[ServiceDraft.priceMinorUnits]),
 * and anything the server would refuse is refused *by the server*, with its own sentence — this type
 * never invents a validation message (`BookingActionResult.Refused`'s own rule).
 *
 * **[priceRubles] is rubles, [priceMinorUnits] is kopecks**, and this class is the only place either
 * crosses into the other — the identical single-conversion-site discipline
 * `ago-console`'s `toPriceMinorUnits` states.
 */
internal data class ServiceDraft(
    val serviceId: String,
    val name: String,
    val durationMinutes: String,
    val priceRubles: String,
    val priceIsFrom: Boolean,
    val description: String,
    val isActive: Boolean,
) {
    /** `null` when the field is blank or not a whole number — the submit path refuses locally only
     * here, because there is no request to send at all without a number to put in it. Every *other*
     * refusal (zero, negative, longer than a working day) belongs to the server, which already owns
     * those rules and states them in Russian. */
    fun durationMinutesOrNull(): Int? = durationMinutes.trim().toIntOrNull()

    /** Kopecks, or `null` for "no stated price" — blank, whitespace-only and unparseable all mean the
     * same thing the console's own `toPriceMinorUnits` makes them mean: the operator stated nothing. */
    fun priceMinorUnits(): Int? {
        val trimmed = priceRubles.trim()
        if (trimmed.isEmpty()) return null
        val rubles = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        return Math.round(rubles * 100).toInt()
    }

    /** Blank and whitespace-only both mean "none" — never an empty string, which the server would
     * store as an honest-looking description nobody wrote (`Ago.Calendar.Domain.Service`'s own
     * `ValidateDescription`, mirrored so the two never disagree about what blank means). */
    fun descriptionOrNull(): String? = description.trim().ifEmpty { null }
}

/** `26-96`: the edit form, opened on one row. A copy rather than a reference - see
 * [ServicesUiState.Loaded.editing]. */
internal fun ConfiguredService.toDraft(): ServiceDraft =
    ServiceDraft(
        serviceId = serviceId,
        name = name,
        // Kopecks back into a ruble string, the exact inverse of [ServiceDraft.priceMinorUnits] - a
        // whole number of rubles prefills without a decimal part, so "1500 ₽" never opens as "1500.0".
        durationMinutes = durationMinutes.toString(),
        priceRubles =
            priceMinorUnits?.let { minorUnits ->
                if (minorUnits % 100 == 0) (minorUnits / 100).toString() else (minorUnits / 100.0).toString()
            } ?: "",
        priceIsFrom = priceIsFrom,
        description = description.orEmpty(),
        isActive = isActive,
    )
