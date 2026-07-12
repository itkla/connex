package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

class SmtpDestinationGuardTest {

    @Test
    void classifiesUniqueLocalCarrierGradeNatAndPublicAddresses() throws Exception {
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("fc00::1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("100.64.1.1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("64:ff9b::a00:1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("64:ff9b:1:a00:0:100::")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("::10.0.0.1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("2002:0a00:1::")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:4860::5efe:a00:1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:4860::200:5efe:a00:1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:0:a00:1::")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:0:808:808::f5ff:fffe")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("192.0.2.1")));
        assertTrue(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("2001:db8::1")));
        assertFalse(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("64:ff9b:1:808:8:800::")));
        assertFalse(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:4860::5efe:808:808")));
        assertFalse(SmtpDestinationGuard.isInternalAddress(
            InetAddress.getByName("2001:0:808:808::fefe:fefe")));
        assertFalse(SmtpDestinationGuard.isInternalAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void trustedInstanceDestinationDoesNotUseWorkspaceSsrFPolicy() {
        SmtpDestinationGuard guard = new SmtpDestinationGuard(new MailProperties());

        assertNull(guard.resolveForSend(config("127.0.0.1", false)));
    }

    @Test
    void workspaceDestinationIsRevalidatedAtSendTime() {
        SmtpDestinationGuard guard = new SmtpDestinationGuard(new MailProperties());

        assertThrows(BadRequestException.class, () -> guard.resolveForSend(config("127.0.0.1", true)));
    }

    private static ResolvedMailConfig config(String host, boolean workspaceSupplied) {
        return new ResolvedMailConfig(
            host, 587, null, null, "sender@example.com", "Connex",
            true, false, false, 1000, 1000, 1000, workspaceSupplied);
    }
}
