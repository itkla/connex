package ooo.klae.connex.backend.integration;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The collector's session-neutrality with Spring Session registered below its library default.
 *
 * <p>{@code spring.session.servlet.filter-order} is an operator-facing property, and a deployment
 * that lowers it used to break the guarantee silently: the cookie filter sat at the constant
 * {@code SessionRepositoryFilter.DEFAULT_ORDER - 1}, so Spring Session ran first and resolved —
 * and therefore refreshed — the session before the cookies were hidden. The order is now derived
 * from Spring Session's own registration, so this context, which is identical to
 * {@link CspReportEndpointSecurityTest}'s except for that property, must reach the same verdict on
 * the inherited cases rather than fail them.
 */
@SpringBootTest(properties = "spring.session.servlet.filter-order=-2147483647")
class CspReportCookieFilterOrderTest extends AbstractCspReportSessionNeutralityTest {
}
