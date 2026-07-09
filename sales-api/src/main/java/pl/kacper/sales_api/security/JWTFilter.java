package pl.kacper.sales_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JWTFilter extends OncePerRequestFilter {

    private final JWTService jwtService;
    private final UserDetailsService userDetailsService;

    public JWTFilter(JWTService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        final String prefixAuthorizationHeader = "Bearer ";

        String authorizationHeader = request.getHeader("Authorization");
        if(authorizationHeader == null || !authorizationHeader.startsWith(prefixAuthorizationHeader)){
            filterChain.doFilter(request,response);
            return;
        }

        try {
            if(SecurityContextHolder.getContext().getAuthentication() == null){

                int beginIndex = prefixAuthorizationHeader.length();
                String token = authorizationHeader.substring(beginIndex);

                String email = jwtService.extractUsernameClaim(token);

                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                if(jwtService.validateToken(token,userDetails)){


                    UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );

                    WebAuthenticationDetails webAuthenticationDetails = new WebAuthenticationDetails(request);
                    usernamePasswordAuthenticationToken.setDetails(webAuthenticationDetails);
                    SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);

                    filterChain.doFilter(request,response);
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
