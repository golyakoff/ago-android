package ago.chat.android.core.domain.bookings

/**
 * `26-96`: one row of the tenant's own service dictionary — `Ago.Calendar.Contracts.ConfiguredServiceResponse`,
 * reduced to the fields this app's Услуги screen actually reads and writes.
 *
 * Every field is carried verbatim, unparsed: [durationMinutes] is whole minutes because the server
 * refuses anything else, and [priceMinorUnits] is **kopecks**, never rubles — the conversion happens
 * exactly once, in the UI that renders or parses it, the identical "convert at the boundary, store the
 * server's own unit" discipline `ago-console`'s `formatPrice`/`toPriceMinorUnits` pair already states.
 *
 * [priceCurrencyCode] is on the wire and kept here rather than assumed: v1 only ever writes `"RUB"`,
 * but a renderer that hard-codes a symbol for a code it never read is the bug that shows up the day a
 * second currency exists. `null` exactly when [priceMinorUnits] is.
 *
 * [isActive] is `26-96`'s own field. `false` means the tenant has taken the service out of rotation:
 * the booking widget stops offering it and a claim naming it is refused, while every worker who
 * performs it and every booking that used it still resolve its name through this same record. The
 * console read this comes from deliberately keeps returning archived services — see
 * `Ago.Calendar.Domain.Service.IsActive` for why this product archives instead of deleting, and why
 * there is no `deleteService` on [BookingsApi] to pair with [BookingsApi.updateService].
 */
public data class ConfiguredService(
    val serviceId: String,
    val name: String,
    val durationMinutes: Int,
    val priceMinorUnits: Int?,
    val priceCurrencyCode: String?,
    val priceIsFrom: Boolean,
    val description: String?,
    val isActive: Boolean,
)
