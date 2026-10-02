package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import ooo.klae.connex.backend.storage.ObjectStorage;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins, over real HTTP on a real servlet container, that a managed download which sets its own
 * security headers reaches the client with exactly one value for each, carrying its own sandboxed
 * policy rather than the application-wide default (#1898).
 *
 * <p>#1894 made the security header writer eager so it no longer writes twice, and every assertion it
 * added ran on MockMvc. MockMvc is not proof of what Tomcat sends: #1780 was a case where it hid a
 * container-only dispatch. What this pins on Tomcat itself is the merge of a controller's
 * {@code ResponseEntity} headers over the headers the filter already wrote, and that the headers only
 * the filter writes stay single-valued too.
 *
 * <p>It is not a guard for #1894's eager writer itself: with the lazy writer every header here is
 * still single-valued, because Spring Security's writers skip a header the controller already set.
 * {@code SecurityHeadersTest} pins that every chain writes eagerly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
class ManagedContentSecurityHeadersIntegrationTest {
    private static final String PASSWORD = "Header-Fixture-Pw1!";
    private static final String MANAGED_CONTENT_POLICY =
        "default-src 'none'; sandbox; frame-ancestors 'none'; base-uri 'none'";

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ObjectStorage storage;
    @LocalServerPort private int port;

    private Organization organization;
    private Workspace workspace;
    private User member;
    private String storedImageKey;

    @BeforeEach
    void createFixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Header fixture " + suffix);
        organization.setSlug("header-fixture-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setName("Header fixture " + suffix);
        workspace.setSlug("header-fixture-" + suffix);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        member = new User();
        member.setUsername("header_fixture_" + suffix);
        member.setDisplayName("Header fixture");
        member.setEmail("header-fixture-" + suffix + "@example.com");
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
    }

    @AfterEach
    void deleteFixtures() {
        if (storedImageKey != null) {
            storage.delete(storedImageKey);
        }
        if (member != null) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                new AccountSessionIndex(member.getId()).getName());
        }
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (member != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", member.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void aManagedDownloadKeepsOneValueForEachOfItsOwnSecurityHeaders() throws Exception {
        try (HttpClient client = httpClient()) {
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(httpUri("/api/auth/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                    Map.of("username", member.getUsername(), "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), login.body());
            CsrfBootstrapDto csrf = csrf(client);
            try {
                HttpResponse<String> uploaded = uploadProfilePicture(client, csrf);
                assertEquals(200, uploaded.statusCode(), uploaded.body());
                String pictureUrl = objectMapper.readTree(uploaded.body()).path("profilePictureUrl").asString();
                assertFalse(pictureUrl.isBlank(), uploaded.body());
                storedImageKey = "users/" + member.getId() + "/profile-images/"
                    + pictureUrl.substring(pictureUrl.lastIndexOf('/') + 1);

                HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(httpUri(pictureUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Workspace-Id", Integer.toString(workspace.getId()))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

                assertEquals(200, download.statusCode());
                assertEquals(List.of(MANAGED_CONTENT_POLICY),
                    download.headers().allValues("Content-Security-Policy"));
                assertEquals(List.of("nosniff"), download.headers().allValues("X-Content-Type-Options"));
                assertEquals(List.of("same-origin"),
                    download.headers().allValues("Cross-Origin-Resource-Policy"));
                assertEquals(List.of("no-store"), download.headers().allValues("Cache-Control"));
                assertEquals(List.of("DENY"), download.headers().allValues("X-Frame-Options"));
                assertEquals(List.of("strict-origin-when-cross-origin"),
                    download.headers().allValues("Referrer-Policy"));
            } finally {
                HttpResponse<Void> logout = client.send(HttpRequest.newBuilder(httpUri("/api/auth/logout"))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Workspace-Id", Integer.toString(workspace.getId()))
                    .header(csrf.headerName(), csrf.token())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(200, logout.statusCode());
            }
        }
    }

    private HttpResponse<String> uploadProfilePicture(HttpClient client, CsrfBootstrapDto csrf) throws Exception {
        String boundary = "header-fixture-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"portrait.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(httpUri("/api/users/me/profile-picture"))
            .timeout(Duration.ofSeconds(15))
            .header("X-Workspace-Id", Integer.toString(workspace.getId()))
            .header(csrf.headerName(), csrf.token())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private CsrfBootstrapDto csrf(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(httpUri("/api/auth/csrf"))
            .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        CsrfBootstrapDto csrf = objectMapper.readValue(response.body(), CsrfBootstrapDto.class);
        assertNotNull(csrf.headerName());
        assertNotNull(csrf.token());
        return csrf;
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private URI httpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("PNG writer is unavailable");
        }
        return output.toByteArray();
    }
}
