package pl.kacper.sales_api;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pl.kacper.sales_api.domain.user.Role;
import pl.kacper.sales_api.domain.user.UserProvisioningService;
import pl.kacper.sales_api.domain.user.UserRepository;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;

@Component
@Profile("dev")
public class DatabaseSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserProvisioningService userProvisioningService;

    public DatabaseSeeder(UserRepository userRepository, UserProvisioningService userProvisioningService) {
        this.userRepository = userRepository;
        this.userProvisioningService = userProvisioningService;
    }

    @Override
    public void run(String... args) {
        seedUser();
        seedAdmin();
    }

    private void seedUser() {
        String userEmail = "user@gmail.com";
        if (!userRepository.existsByEmail(userEmail)) {
            UserRegisterDto userDto = new UserRegisterDto(
                    userEmail,
                    "User123",
                    "FirstnameUser",
                    "LastnameUser"
            );

            userProvisioningService.createUser(userDto, Role.ROLE_USER);
        }
    }

    private void seedAdmin() {
        String adminEmail = "admin@gmail.com";
        if (!userRepository.existsByEmail(adminEmail)) {
            UserRegisterDto adminDto = new UserRegisterDto(
                    adminEmail,
                    "Admin123",
                    "FirstnameAdmin",
                    "LastnameAdmin"
            );

            userProvisioningService.createUser(adminDto, Role.ROLE_ADMIN);
        }
    }
}
