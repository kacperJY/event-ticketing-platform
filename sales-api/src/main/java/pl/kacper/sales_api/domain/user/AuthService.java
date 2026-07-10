package pl.kacper.sales_api.domain.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.exception.DuplicateUsernameException;
import pl.kacper.sales_api.domain.user.dto.UserLoginDto;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;
import pl.kacper.sales_api.security.JWTService;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JWTService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(UserRepository userRepository, JWTService jwtService, AuthenticationManager authenticationManager, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordEncoder = passwordEncoder;
    }

    public void register(UserRegisterDto userRegisterDto) {
        if(userRepository.existsByEmail(userRegisterDto.email()))
            throw new DuplicateUsernameException("User with such email address already exists");

        String encodedPassword = passwordEncoder.encode(userRegisterDto.password());

        UserEntity userEntity = new UserEntity(
                userRegisterDto.email(),
                encodedPassword,
                userRegisterDto.firstname(),
                userRegisterDto.lastname()
        );

        userRepository.save(userEntity);
    }

    public String login(UserLoginDto userLoginDto) {

        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(
                userLoginDto.email(),
                userLoginDto.password()
        );

        Authentication secureAuthenticationToken = authenticationManager.authenticate(usernamePasswordAuthenticationToken);
        UserDetails userDetails = (UserDetails) secureAuthenticationToken.getPrincipal();
        return jwtService.generateTokenJWT(userDetails);
    }
}
