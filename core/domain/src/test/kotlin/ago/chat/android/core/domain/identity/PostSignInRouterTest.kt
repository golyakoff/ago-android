package ago.chat.android.core.domain.identity

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-12`: every arm of the post-authentication tree, including the two that are wrong in a way that
 * looks correct — the `5xx`/network arm (`11-17`) and the owner probe (`12-04`/`adr/0063`).
 *
 * The fakes below record *what was asked and in what order*, not only what was answered, because two
 * of this router's properties are about sequencing rather than about a return value: the active site
 * is published before the probe that needs it, and nothing is probed at all while the tenancy
 * question is still open.
 */
class PostSignInRouterTest {
    private val oneShop = Tenancy(siteId = "11111111-1111-1111-1111-111111111111", siteName = "Один магазин")
    private val otherShop = Tenancy(siteId = "22222222-2222-2222-2222-222222222222", siteName = "Другой магазин")

    // ---------------------------------------------------------------- the four arms the item names

    @Test
    fun `an operator seat routes to the operator arm`() =
        runTest {
            val api = FakeIdentityApi(tenancies = TenancyListing.Known(listOf(oneShop)), seat = ProbeOutcome.Accepted)
            val site = RecordingActiveSite()

            val destination = routerFor(api, site).route()

            assertEquals(SignInDestination.Operator(oneShop.siteId), destination)
            assertEquals("the owner question is never asked once the seat answered yes", 0, api.ownerProbes)
        }

    @Test
    fun `no seat plus an accepted owner probe routes to the platform-owner terminal`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(emptyList()),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Accepted,
                )

            val destination = routerFor(api, RecordingActiveSite()).route()

            assertEquals(SignInDestination.PlatformOwnerTerminal, destination)
        }

    @Test
    fun `no seat plus a refused owner probe routes to registration`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(emptyList()),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Refused,
                )

            val destination = routerFor(api, RecordingActiveSite()).route()

            assertEquals(SignInDestination.Registration, destination)
        }

    /**
     * The arm `11-17` is about. Three different non-answers, each asserted to be *neither* terminal
     * outcome rather than merely "not registration" — an assertion that would still pass if the code
     * routed a `5xx` to the owner screen is not the assertion this arm needs.
     */
    @Test
    fun `an unanswerable seat probe is neither terminal arm, for every kind of non-answer`() =
        runTest {
            val nonAnswers =
                listOf(
                    ProbeFailure.UnexpectedStatus(401),
                    ProbeFailure.UnexpectedStatus(500),
                    ProbeFailure.UnexpectedStatus(503),
                    ProbeFailure.Transport("Unable to resolve host chat-api.reserve-me.ru"),
                )

            for (reason in nonAnswers) {
                val api =
                    FakeIdentityApi(
                        tenancies = TenancyListing.Known(emptyList()),
                        seat = ProbeOutcome.Unanswered(reason),
                        // Deliberately accepting: if the tree ever asked this question anyway, the
                        // destination would flip to the owner terminal and this test would say so.
                        owner = ProbeOutcome.Accepted,
                    )

                val destination = routerFor(api, RecordingActiveSite()).route()

                assertEquals(
                    SignInDestination.Unavailable(
                        RoutingFailure.ProbeDidNotAnswer(RoutingStep.OPERATOR_SEAT, reason),
                    ),
                    destination,
                )
                assertTrue(
                    "$reason must render a retry, never the registration form",
                    destination !is SignInDestination.Registration,
                )
                assertEquals("a non-answer ends the tree; it does not fall through", 0, api.ownerProbes)
            }
        }

    // ------------------------------------------------- the owner probe's own non-answer (narrowed)

    @Test
    fun `an unanswerable owner probe renders a retry rather than the registration form`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(emptyList()),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Unanswered(ProbeFailure.UnexpectedStatus(500)),
                )

            val destination = routerFor(api, RecordingActiveSite()).route()

            assertEquals(
                SignInDestination.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(
                        RoutingStep.OWNER_ELIGIBILITY,
                        ProbeFailure.UnexpectedStatus(500),
                    ),
                ),
                destination,
            )
        }

    // ------------------------------------------------------------------------------ the tenancies

    @Test
    fun `several tenancies and no choice yet asks the question and probes nothing`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(listOf(oneShop, otherShop)),
                    // Both probes are set to answers that would produce a *terminal* destination, so
                    // a router that asked them anyway could not pass this test.
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Accepted,
                )
            val site = RecordingActiveSite()

            val destination = routerFor(api, site).route()

            assertEquals(SignInDestination.ChooseSite(listOf(oneShop, otherShop)), destination)
            assertEquals(0, api.seatProbes)
            assertEquals(0, api.ownerProbes)
        }

    @Test
    fun `a site already chosen is used, and is named before the seat probe is built`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(listOf(oneShop, otherShop)),
                    seat = ProbeOutcome.Accepted,
                )
            val site = RecordingActiveSite(initial = otherShop.siteId)

            val destination = routerFor(api, site).route()

            assertEquals(SignInDestination.Operator(otherShop.siteId), destination)
            assertEquals(
                "the active site must be readable by the plugin building the seat probe's own request",
                otherShop.siteId,
                api.activeSiteWhenSeatProbed,
            )
        }

    @Test
    fun `a single tenancy is selected before the seat probe, with nothing for the operator to answer`() =
        runTest {
            val api = FakeIdentityApi(tenancies = TenancyListing.Known(listOf(oneShop)), seat = ProbeOutcome.Accepted)
            val site = RecordingActiveSite()

            routerFor(api, site).route()

            assertEquals(oneShop.siteId, api.activeSiteWhenSeatProbed)
            assertEquals(listOf<String?>(oneShop.siteId), site.selections)
        }

    @Test
    fun `a chosen site that is no longer one of this identity's own is cleared and re-asked`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(listOf(oneShop, otherShop)),
                    seat = ProbeOutcome.Accepted,
                )
            val site = RecordingActiveSite(initial = "99999999-9999-9999-9999-999999999999")

            val destination = routerFor(api, site).route()

            assertEquals(SignInDestination.ChooseSite(listOf(oneShop, otherShop)), destination)
            assertNull("a stale selection is dropped, never sent", site.currentSiteId())
        }

    @Test
    fun `no tenancy at all sends no active site with the probes`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(emptyList()),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Refused,
                )
            val site = RecordingActiveSite(initial = "left-over-from-a-previous-identity")

            routerFor(api, site).route()

            assertNull(api.activeSiteWhenSeatProbed)
        }

    @Test
    fun `an unanswerable tenancy read ends in a retry and probes nothing`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Unanswered(ProbeFailure.Transport("connection reset")),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Refused,
                )

            val destination = routerFor(api, RecordingActiveSite()).route()

            assertEquals(
                SignInDestination.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(
                        RoutingStep.TENANCIES,
                        ProbeFailure.Transport("connection reset"),
                    ),
                ),
                destination,
            )
            assertEquals(0, api.seatProbes)
            assertEquals(0, api.ownerProbes)
        }

    /**
     * `12-04`'s defect class reached from the other direction: the identity demonstrably holds a
     * seat (the server listed it as switchable) and the seat probe refuses anyway. Offering the
     * registration form there would invite an existing operator to register a second shop.
     */
    @Test
    fun `a refused seat probe while tenancies are listed never reaches the registration arm`() =
        runTest {
            val api =
                FakeIdentityApi(
                    tenancies = TenancyListing.Known(listOf(oneShop)),
                    seat = ProbeOutcome.Refused,
                    owner = ProbeOutcome.Refused,
                )

            val destination = routerFor(api, RecordingActiveSite()).route()

            assertEquals(
                SignInDestination.Unavailable(RoutingFailure.OperatorSeatRefusedDespiteTenancies),
                destination,
            )
            assertEquals(0, api.ownerProbes)
        }

    // ------------------------------------------------------------------------------------- fakes

    private class FakeIdentityApi(
        private val tenancies: TenancyListing,
        private val seat: ProbeOutcome = ProbeOutcome.Refused,
        private val owner: ProbeOutcome = ProbeOutcome.Refused,
    ) : IdentityApi {
        var seatProbes: Int = 0
            private set
        var ownerProbes: Int = 0
            private set

        /**
         * What the active-site source read at the moment the seat probe was made. In production that
         * read happens inside the Ktor plugin building the request; here it stands in for it, which
         * is what makes "selected before the probe" an assertion rather than a comment.
         */
        var activeSiteWhenSeatProbed: String? = null
            private set

        lateinit var activeSite: ActiveSiteSelection

        override suspend fun listMyTenancies(): TenancyListing = tenancies

        override suspend fun probeOperatorSeat(): ProbeOutcome {
            seatProbes++
            activeSiteWhenSeatProbed = activeSite.currentSiteId()
            return seat
        }

        override suspend fun probeOwnerEligibility(): ProbeOutcome {
            ownerProbes++
            return owner
        }
    }

    private class RecordingActiveSite(
        initial: String? = null,
    ) : ActiveSiteSelection {
        private var current: String? = initial
        val selections: MutableList<String?> = mutableListOf()

        override fun currentSiteId(): String? = current

        override fun select(siteId: String?) {
            current = siteId
            selections += siteId
        }
    }

    /**
     * Wires the two fakes to each other before handing them to the real router — the fake API has to
     * be able to read the active site at probe time, which is what the production plugin does.
     */
    private fun routerFor(
        api: FakeIdentityApi,
        site: RecordingActiveSite,
    ): PostSignInRouter {
        api.activeSite = site
        return PostSignInRouter(api, site)
    }
}
