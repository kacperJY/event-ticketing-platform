package pl.kacper.sales_api.domain.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.domain.user.dto.UserLoginDto;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;
import pl.kacper.sales_api.security.JWTService;

@Service
public class AuthService {

    private final JWTService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserProvisioningService userProvisioningService;

    @Autowired
    public AuthService(JWTService jwtService, AuthenticationManager authenticationManager, UserProvisioningService userProvisioningService) {
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userProvisioningService = userProvisioningService;
    }

    public void registerUser(UserRegisterDto userRegisterDto) {
        userProvisioningService.createUser(userRegisterDto, Role.ROLE_USER);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void registerAdmin(UserRegisterDto userRegisterDto) {
        userProvisioningService.createUser(userRegisterDto, Role.ROLE_ADMIN);
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
