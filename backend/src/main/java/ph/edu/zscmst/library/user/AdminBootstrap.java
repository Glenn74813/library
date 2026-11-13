package ph.edu.zscmst.library.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first administrator account on an empty database. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final boolean mustChange;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${app.bootstrap-admin.username}") String username,
                          @Value("${app.bootstrap-admin.password:}") String password,
                          @Value("${app.bootstrap-admin.must-change:true}") boolean mustChange) {
        this.mustChange = mustChange;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        // No password given: a random one, shown once in the log. Either way it is replaced at first sign-in.
        boolean generated = password == null || password.isBlank();
        String initial = generated ? randomPassword() : password;
        AppUser admin = new AppUser(username, passwordEncoder.encode(initial), "System Administrator", Role.ADMIN);
        admin.setMustChangePassword(mustChange);
        users.save(admin);
        if (generated) {
            log.warn("Created initial admin account '{}' with password: {}  (choose a new one at first sign-in)", username, initial);
        } else {
            log.warn("Created initial admin account '{}' with ADMIN_PASSWORD; choose a new password at first sign-in.", username);
        }
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        return b.toString();
    }
}
