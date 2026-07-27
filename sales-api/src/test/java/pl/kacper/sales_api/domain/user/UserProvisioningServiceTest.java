package pl.kacper.sales_api.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.kacper.sales_api.common.exception.DuplicateUsernameException;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class UserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserProvisioningService userProvisioningService;

    @Test
    @DisplayName("Should throws DuplicateUsernameException when registering user with email that already exists")
    void shouldThrowExceptionWhenRegisteringUserWithDuplicatedEmail(){
        UserRegisterDto userRegisterDto = new UserRegisterDto(
                "kacper@gmail.com",
                "Kacper123",
                "Kacper",
                "Lastname"
        );

        Mockito.when(userRepository.existsByEmail(userRegisterDto.email())).thenReturn(true);

        assertThatThrownBy(() -> userProvisioningService.createUser(userRegisterDto, Role.ROLE_USER))
                .hasMessageContaining("already exists")
                .isInstanceOf(DuplicateUsernameException.class);
    }

    @Test
    @DisplayName("Should register user with unique email")
    void shouldRegisterUserUserWithUniqueEmail(){
        UserRegisterDto userRegisterDto = new UserRegisterDto(
                "kacper@gmail.com",
                "Kacper123",
                "Kacper",
                "Lastname"
        );
        String encodedPassword = "Encoded_"+userRegisterDto.password();
        Mockito.when(userRepository.existsByEmail(userRegisterDto.email())).thenReturn(false);
        Mockito.when(passwordEncoder.encode(userRegisterDto.password())).thenReturn(encodedPassword);

        userProvisioningService.createUser(userRegisterDto, Role.ROLE_USER);

        ArgumentCaptor<UserEntity> userEntityArgumentCaptor = ArgumentCaptor.forClass(UserEntity.class);
        Mockito.verify(userRepository,Mockito.times(1)).save(userEntityArgumentCaptor.capture());

        UserEntity caputredUserEntity = userEntityArgumentCaptor.getValue();

        assertThat(caputredUserEntity.getEmail()).isEqualTo(userRegisterDto.email());
        assertThat(caputredUserEntity.getPassword()).isEqualTo(encodedPassword);
        assertThat(caputredUserEntity.getFirstname()).isEqualTo(userRegisterDto.firstname());
        assertThat(caputredUserEntity.getLastname()).isEqualTo(userRegisterDto.lastname());
    }
}
