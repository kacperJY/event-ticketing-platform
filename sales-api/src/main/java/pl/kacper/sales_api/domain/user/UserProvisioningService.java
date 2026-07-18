package pl.kacper.sales_api.domain.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.exception.DuplicateUsernameException;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;

@Service
public class UserProvisioningService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserProvisioningService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void createUser(UserRegisterDto userRegisterDto, Role role) {
        if (userRepository.existsByEmail(userRegisterDto.email()))
            throw new DuplicateUsernameException("User with such email address already exists");

        String encodedPassword = passwordEncoder.encode(userRegisterDto.password());

        UserEntity userEntity = new UserEntity(
                userRegisterDto.email(),
                encodedPassword,
                userRegisterDto.firstname(),
                userRegisterDto.lastname()
        );
        userEntity.setRole(role);

        userRepository.save(userEntity);
    }
}
