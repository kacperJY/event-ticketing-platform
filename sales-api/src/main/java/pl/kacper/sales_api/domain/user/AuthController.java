package pl.kacper.sales_api.domain.user;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.kacper.sales_api.domain.user.dto.UserLoginDto;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody @Valid UserRegisterDto userRegisterDto) {

        authService.registerUser(userRegisterDto);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody UserLoginDto userLoginDto, HttpServletResponse httpServletResponse) {

        String token = authService.login(userLoginDto);

        String responseToken = "Bearer " + token;

        return ResponseEntity.status(HttpStatus.OK).body(responseToken);
    }

}
