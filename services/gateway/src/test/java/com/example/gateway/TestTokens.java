package com.example.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Synthetic Supabase-style tokens signed with throwaway keys. */
public final class TestTokens {

    public static final String ISSUER = "http://localhost:8080/auth/v1";
    public static final String AUDIENCE = "authenticated";

    private final ECKey ecKey;
    private final ECKey otherEcKey;
    private final RSAKey rsaKey;

    public TestTokens() {
        try {
            ecKey = new ECKeyGenerator(Curve.P_256).keyID("es-1").generate();
            otherEcKey = new ECKeyGenerator(Curve.P_256).keyID("es-unknown").generate();
            rsaKey = new RSAKeyGenerator(2048).keyID("rs-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public JWKS. It includes an RSA key so RS256 tokens fail on algorithm, not on key lookup. */
    public String jwks() {
        return new JWKSet(List.of(ecKey.toPublicJWK(), rsaKey.toPublicJWK())).toString();
    }

    public String valid(String subject) {
        return es256(claims(subject).build());
    }

    public String valid() {
        return valid(UUID.randomUUID().toString());
    }

    public String withClaims(UnaryOperator<JWTClaimsSet.Builder> customize) {
        return es256(customize.apply(claims(UUID.randomUUID().toString())).build());
    }

    public String signedWithUnknownKey() {
        try {
            return sign(new ECDSASigner(otherEcKey), JWSAlgorithm.ES256, otherEcKey.getKeyID(),
                    claims(UUID.randomUUID().toString()).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String rs256() {
        try {
            return sign(new RSASSASigner(rsaKey), JWSAlgorithm.RS256, rsaKey.getKeyID(), claims(UUID.randomUUID().toString()).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String hs256() {
        try {
            return sign(new MACSigner("0123456789abcdef0123456789abcdef"), JWSAlgorithm.HS256, null,
                    claims(UUID.randomUUID().toString()).build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JWTClaimsSet.Builder claims(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(subject)
                .claim("role", "authenticated")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
    }

    private String es256(JWTClaimsSet claims) {
        try {
            return sign(new ECDSASigner(ecKey), JWSAlgorithm.ES256, ecKey.getKeyID(), claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(com.nimbusds.jose.JWSSigner signer, JWSAlgorithm algorithm, String keyId, JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(keyId).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
