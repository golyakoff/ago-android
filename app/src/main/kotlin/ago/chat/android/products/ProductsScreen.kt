package ago.chat.android.products

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-249` (`ago-console`'s own `ProductsPage`, ported): Ещё → Администрирование → «Продукты» — every
 * product AGO offers, each marked «Уже есть»/«Пока нет», with a "contact AGO" note under a product this
 * workspace does not hold. **Read-only, by the platform's own design.** `adr/0151`/`decisions.md` §6 make
 * enabling a product owner-only (a runbook, not a console write): there is no enable/disable/provision
 * control anywhere on this screen, only the console's own status-and-contact-note shape. Obtains its own
 * [ProductsViewModel] via [hiltViewModel] — the identical wiring [ago.chat.android.faq.ModulesFaqRoute]
 * establishes for its own sibling Ещё drill-in.
 *
 * `MoreScreen` composes this row only for an operator holding `site:configure` (hide-not-disable), the
 * same permission `ago-console`'s own `PRODUCTS_PERMISSION` gates its `/account/products` on — so the
 * gate lives one level up in the row list, not as an in-screen check, exactly as every other real Ещё row
 * gates itself.
 */
@Composable
internal fun ProductsRoute(
    onBack: () -> Unit,
    viewModel: ProductsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProductsScreen(state = state, onRetry = viewModel::refresh, onBack = onBack)
}

/**
 * The stateless half — the identical Route/Screen split [ago.chat.android.faq.ModulesFaqScreen] follows:
 * no [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a top-level destination), a
 * back arrow in its place, and a manual «Обновить» action rather than an auto-refresh poll — a status an
 * operator opens to check, not a live queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductsScreen(
    state: ProductsUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.products_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.products_refresh_action))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    ProductsUiState.Loading -> LoadingBody()
                    is ProductsUiState.Failed -> ProductsFailedBody(reason = state.reason, onRetry = onRetry)
                    is ProductsUiState.Loaded -> ProductsLoadedBody(products = state.products)
                }
            }
        }
    }
}

/** The read itself failed — the identical "message, retry" shape
 * [ago.chat.android.faq.ModulesFaqScreen]'s own private failed body establishes, restated here since
 * neither file imports composables from the other. */
@Composable
private fun ProductsFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** The catalogue — the console's own intro line, then one card per product in catalogue order. Never the
 * "ask us" empty note the sibling module screen shows: the catalogue is fixed and always carries at least
 * the base product, so an empty branch would be unreachable. */
@Composable
private fun ProductsLoadedBody(products: List<ProductHolding>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "intro") {
            Text(
                text = stringResource(R.string.products_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
        }
        items(products, key = { it.product.name }) { holding ->
            ProductCard(holding)
            HorizontalDivider()
        }
    }
}

/** One product: its name, what it does, an «Уже есть»/«Пока нет» status badge, and — only when the tenant
 * does not hold it — the "contact AGO" note (`decisions.md` §6: enabling a product is not self-service, so
 * the honest next step is prose, never a control that looks like it provisions). */
@Composable
private fun ProductCard(holding: ProductHolding) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(holding.product.nameRes()),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = stringResource(holding.product.descriptionRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        ProductStatusBadge(held = holding.held)
        if (!holding.held) {
            Text(
                text = stringResource(R.string.products_contact_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** «Уже есть» (held) or «Пока нет» (not held) — the console's own two-tone `Badge`, drawn here as the same
 * small filled [Surface] pill [ago.chat.android.faq.ModulesFaqScreen]'s own owner badge uses, tinted with
 * the theme's primary when held and its surface-variant when not. */
@Composable
private fun ProductStatusBadge(held: Boolean) {
    Surface(
        color = if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = stringResource(if (held) R.string.products_status_held else R.string.products_status_not_held),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The friendly product name a card heads with — never the raw module key the wire carries (the console's
 * own "the copy never shows a module key" rule). */
private fun Product.nameRes(): Int =
    when (this) {
        Product.Chat -> R.string.products_chat_name
        Product.Calendar -> R.string.products_calendar_name
        Product.Faq -> R.string.products_faq_name
    }

/** What the product does for the tenant's own customers — the console's own `products*Description` copy,
 * carried over verbatim. */
private fun Product.descriptionRes(): Int =
    when (this) {
        Product.Chat -> R.string.products_chat_description
        Product.Calendar -> R.string.products_calendar_description
        Product.Faq -> R.string.products_faq_description
    }
