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

    public AdminBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${app.bootstrap-admin.username}") String username,
                          @Value("${app.bootstrap-admin.password}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() == 0) {
            users.save(new AppUser(username, passwordEncoder.encode(password), "System Administrator", Role.ADMIN));
            log.warn("Created initial admin account '{}'. Change its password after first login.", username);
        }
    }
}
