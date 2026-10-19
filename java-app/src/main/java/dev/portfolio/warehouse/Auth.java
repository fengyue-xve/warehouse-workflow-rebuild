package dev.portfolio.warehouse;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Component
public class Auth {
    private final SecretKey key;
    public record Actor(String name, String role) {}
    public Auth(@Value("${demo.jwt-secret}") String secret) {
        key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public String session(String username, String password) {
        if (!(username.equals("operator") || username.equals("viewer")) || !password.equals("demo-only"))
            throw new ApiError(401,"BAD_LOGIN","演示账号或口令不正确");
        return sign(username,"session",Map.of("role",username),3600);
    }
    public String sign(String subject, String purpose, Map<String,?> values, long seconds) {
        return Jwts.builder().issuer("warehouse-portfolio").subject(subject).claims(values)
                .claim("purpose",purpose).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(seconds)))
                .signWith(key).compact();
    }
    public Claims verify(String token, String purpose) {
        try {
            Claims claims=Jwts.parser().verifyWith(key).requireIssuer("warehouse-portfolio").require("purpose",purpose)
                    .build().parseSignedClaims(token).getPayload();
            return claims;
        } catch (Exception e) { throw new ApiError(401,"INVALID_TOKEN","凭证无效或已经过期"); }
    }
    public Actor actor(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer ")) throw new ApiError(401,"LOGIN_REQUIRED","请先登录");
        Claims c=verify(header.substring(7),"session");
        String role=c.get("role",String.class);
        if (!"operator".equals(role) && !"viewer".equals(role)) throw new ApiError(403,"FORBIDDEN","无效角色");
        return new Actor(c.getSubject(),role);
    }
    public void requireOperator(Actor actor) {
        if (!actor.role().equals("operator")) throw new ApiError(403,"OPERATOR_REQUIRED","只有 operator 可以确认执行");
    }
}
