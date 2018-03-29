package org.apereo.cas.oidc.jwks;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apereo.cas.configuration.model.support.oidc.OidcProperties;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * This is {@link OidcJsonWebKeystoreGeneratorService}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Slf4j
@AllArgsConstructor
public class OidcJsonWebKeystoreGeneratorService {
    private final OidcProperties oidcProperties;

    /**
     * Generate.
     */
    @PostConstruct
    @SneakyThrows
    public void generate() {
        final var file = oidcProperties.getJwksFile().getFile();
        if (!file.exists()) {
            final var rsaJsonWebKey = RsaJwkGenerator.generateJwk(2048);
            final var jsonWebKeySet = new JsonWebKeySet(rsaJsonWebKey);
            final var data = jsonWebKeySet.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE);
            FileUtils.write(file, data, StandardCharsets.UTF_8);
            LOGGER.debug("Generated JSON web keystore at [{}]", file);
        } else {
            LOGGER.debug("Located JSON web keystore at [{}]", file);
        }
    }
}
