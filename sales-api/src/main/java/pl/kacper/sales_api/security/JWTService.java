package pl.kacper.sales_api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.function.Function;

@Component
public class JWTService {

    @Value("${jwt-secret-key}")
    private String secretKeyText;

    private SecretKey getSecretkey() {
        byte[] encode = Decoders.BASE64.decode(secretKeyText);
        SecretKey secretKey = Keys.hmacShaKeyFor(encode);
        return secretKey;
    }

    public boolean validateToken(String token, UserDetails userDetails){
        String username = userDetails.getUsername();
        String usernameFromToken = extractUsernameClaim(token);
        Date expirationDate = extractExpirationDateClaim(token);

        return (username.equals(usernameFromToken) && expirationDate.after(new Date()));
    }

    public String generateTokenJWT(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + (1000 * 1800)))
                .signWith(getSecretkey())
                .compact();
    }

    private Date extractExpirationDateClaim(String token){
        return extractSingleClaim(token, Claims::getExpiration);
    }

    public String extractUsernameClaim(String token){
        return extractSingleClaim(token, Claims::getSubject);
    }

    private <T> T extractSingleClaim(String token, Function<Claims,T> function){
        Claims allClaims = getAllClaims(token);
        return function.apply(allClaims);
    }

    private Claims getAllClaims(String token){
        return Jwts.parser()
                .verifyWith(getSecretkey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
