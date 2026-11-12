package ph.edu.zscmst.library.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import ph.edu.zscmst.library.user.AppUser;
import ph.edu.zscmst.library.user.AppUserRepository;
import ph.edu.zscmst.library.user.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP security rules, through the real filter chain. */
class SecurityTest extends IntegrationTest {

    @LocalServerPort int port;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(String username, String password) throws Exception {
        HttpResponse<String> r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body()).get("accessToken").asString();
    }

    @Test
    void staffEndpointsNeedASignIn() throws Exception {
        assertEquals(401, call("GET", "/api/patrons", null, null).statusCode());
        assertEquals(200, call("GET", "/api/opac/profile", null, null).statusCode());
    }

    @Test
    void wrongPasswordIsRefused() throws Exception {
        assertEquals(401, call("POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"nope-nope\"}").statusCode());
    }

    @Test
    void parallelGuessesStillLockTheAccount() throws Exception {
        String name = "guess-" + UUID.randomUUID().toString().substring(0, 8);
        AppUser staff = users.save(new AppUser(name, passwords.encode("Right-Pass-123"), "Guess Target", Role.STAFF));
        try {
            String wrong = "{\"username\":\"" + name + "\",\"password\":\"wrong-guess\"}";
            List<CompletableFuture<HttpResponse<String>>> burst = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                burst.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(wrong)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            long checked = burst.stream().map(CompletableFuture::join).filter(r -> r.statusCode() == 401).count();
            // Only the allowed number of attempts reached the password check; the rest were refused as locked.
            assertTrue(checked <= 5, checked + " guesses were checked");
            assertEquals(423, call("POST", "/api/auth/login", null,
                    "{\"username\":\"" + name + "\",\"password\":\"Right-Pass-123\"}").statusCode());
        } finally {
            users.delete(staff);
        }
    }

    @Test
    void onlyAdminsManageUsers() throws Exception {
        String admin = login("admin", "Admin-Test-123");
        assertEquals(200, call("GET", "/api/users", admin, null).statusCode());

        String name = "staff-" + UUID.randomUUID().toString().substring(0, 8);
        AppUser staff = new AppUser(name, passwords.encode("Staff-Pass-123"), "Test Staff", Role.STAFF);
        users.save(staff);
        try {
            String token = login(name, "Staff-Pass-123");
            assertEquals(403, call("GET", "/api/users", token, null).statusCode());
            assertEquals(200, call("GET", "/api/patrons", token, null).statusCode());
        } finally {
            users.delete(staff);
        }
    }

    @Test
    void temporaryPasswordMustBeChangedFirst() throws Exception {
        String admin = login("admin", "Admin-Test-123");
        String name = "temp-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = call("POST", "/api/users", admin,
                "{\"username\":\"" + name + "\",\"password\":\"Temp-Pass-123\",\"fullName\":\"Temp\",\"role\":\"STAFF\"}");
        assertEquals(201, created.statusCode(), created.body());
        long id = json.readTree(created.body()).get("id").asLong();
        try {
            HttpResponse<String> r = call("POST", "/api/auth/login", null,
                    "{\"username\":\"" + name + "\",\"password\":\"Temp-Pass-123\"}");
            JsonNode body = json.readTree(r.body());
            assertTrue(body.get("user").get("mustChangePassword").asBoolean());
            String token = body.get("accessToken").asString();
            assertEquals(403, call("GET", "/api/patrons", token, null).statusCode());
            assertEquals(204, call("POST", "/api/auth/change-password", token,
                    "{\"currentPassword\":\"Temp-Pass-123\",\"newPassword\":\"Own-Pass-456\"}").statusCode());
            assertEquals(200, call("GET", "/api/patrons", login(name, "Own-Pass-456"), null).statusCode());
        } finally {
            users.deleteById(id);
        }
    }

    @Test
    void kioskConnectsWithADeviceKeyUntilItIsRevoked() throws Exception {
        String admin = login("admin", "Admin-Test-123");
        String name = "kiosk-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = call("POST", "/api/users", admin,
                "{\"username\":\"" + name + "\",\"password\":\"Kiosk-Pass-123\",\"fullName\":\"Kiosk\",\"role\":\"KIOSK\"}");
        long id = json.readTree(created.body()).get("id").asLong();
        try {
            HttpResponse<String> issued = call("POST", "/api/users/" + id + "/device-key", admin, null);
            String key = json.readTree(issued.body()).get("deviceKey").asString();
            assertEquals(200, withKey("/api/kiosk/config", key).statusCode());
            assertEquals(403, withKey("/api/patrons", key).statusCode(), "a kiosk key must not reach staff data");
            assertEquals(401, withKey("/api/kiosk/config", "kd_not-a-real-key").statusCode());

            assertEquals(200, call("DELETE", "/api/users/" + id + "/device-key", admin, null).statusCode());
            assertEquals(401, withKey("/api/kiosk/config", key).statusCode(), "a revoked key stops working");
        } finally {
            users.deleteById(id);
        }
    }

    HttpResponse<String> withKey(String path, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Device-Key", key).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
