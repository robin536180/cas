package org.apereo.cas.support.saml.authentication.principal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.principal.AbstractWebApplicationServiceResponseBuilder;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.Response;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.support.saml.SamlProtocolConstants;
import org.apereo.cas.support.saml.SamlUtils;
import org.apereo.cas.support.saml.util.GoogleSaml20ObjectBuilder;
import org.apereo.cas.util.RandomUtils;
import org.apereo.cas.util.crypto.PrivateKeyFactoryBean;
import org.apereo.cas.util.crypto.PublicKeyFactoryBean;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.ResourceUtils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the google accounts service response.
 *
 * @author Misagh Moayyed
 * @since 4.2
 */
@Slf4j
@Getter
@Setter
@EqualsAndHashCode(callSuper = true,
    of = {"publicKeyLocation", "privateKeyLocation", "keyAlgorithm", "samlObjectBuilder", "skewAllowance"})
public class GoogleAccountsServiceResponseBuilder extends AbstractWebApplicationServiceResponseBuilder {

    private static final long serialVersionUID = -4584732364007702423L;

    private PrivateKey privateKey;

    private PublicKey publicKey;

    private final String publicKeyLocation;

    private final String privateKeyLocation;

    private final String keyAlgorithm;

    private GoogleSaml20ObjectBuilder samlObjectBuilder;

    private int skewAllowance;

    private String casServerPrefix;

    @SneakyThrows
    public GoogleAccountsServiceResponseBuilder(final String privateKeyLocation,
                                                final String publicKeyLocation, final String keyAlgorithm,
                                                final ServicesManager servicesManager,
                                                final GoogleSaml20ObjectBuilder samlObjectBuilder,
                                                final int skewAllowance, final String casServerPrefix) {
        super(servicesManager);
        this.privateKeyLocation = privateKeyLocation;
        this.publicKeyLocation = publicKeyLocation;
        this.keyAlgorithm = keyAlgorithm;
        this.skewAllowance = skewAllowance;
        this.samlObjectBuilder = samlObjectBuilder;
        this.casServerPrefix = casServerPrefix;

        createGoogleAppsPrivateKey();
        createGoogleAppsPublicKey();

    }

    @Override
    public Response build(final WebApplicationService webApplicationService, final String serviceTicket,
                          final Authentication authentication) {
        final var service = (GoogleAccountsService) webApplicationService;
        final Map<String, String> parameters = new HashMap<>();
        final var samlResponse = constructSamlResponse(service, authentication);
        final var signedResponse = this.samlObjectBuilder.signSamlResponse(samlResponse, this.privateKey, this.publicKey);
        parameters.put(SamlProtocolConstants.PARAMETER_SAML_RESPONSE, signedResponse);
        parameters.put(SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE, service.getRelayState());
        return buildPost(service, parameters);
    }

    /**
     * Construct SAML response.
     * <a href="http://bit.ly/1uI8Ggu">See this reference for more info.</a>
     *
     * @param service        the service
     * @param authentication the authentication
     * @return the SAML response
     */
    protected String constructSamlResponse(final GoogleAccountsService service,
                                           final Authentication authentication) {
        final var currentDateTime = ZonedDateTime.now(ZoneOffset.UTC);
        final var notBeforeIssueInstant = ZonedDateTime.parse("2003-04-17T00:46:02Z");
        final var registeredService = servicesManager.findServiceBy(service);
        if (registeredService == null || !registeredService.getAccessStrategy().isServiceAccessAllowed()) {
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE);
        }

        final var principal = authentication.getPrincipal();
        final var userId = registeredService.getUsernameAttributeProvider()
            .resolveUsername(principal, service, registeredService);

        final var response = this.samlObjectBuilder.newResponse(
            this.samlObjectBuilder.generateSecureRandomId(), currentDateTime, null, service);
        response.setStatus(this.samlObjectBuilder.newStatus(StatusCode.SUCCESS, null));

        final var sessionIndex = '_' + String.valueOf(Math.abs(RandomUtils.getNativeInstance().nextLong()));
        final var authnStatement = this.samlObjectBuilder.newAuthnStatement(AuthnContext.PASSWORD_AUTHN_CTX, currentDateTime, sessionIndex);
        final var assertion = this.samlObjectBuilder.newAssertion(authnStatement, casServerPrefix,
            notBeforeIssueInstant, this.samlObjectBuilder.generateSecureRandomId());

        final var conditions = this.samlObjectBuilder.newConditions(notBeforeIssueInstant,
            currentDateTime.plusSeconds(this.skewAllowance), service.getId());
        assertion.setConditions(conditions);

        final var subject = this.samlObjectBuilder.newSubject(NameID.EMAIL, userId,
            service.getId(), currentDateTime.plusSeconds(this.skewAllowance), service.getRequestId(), null);
        assertion.setSubject(subject);

        response.getAssertions().add(assertion);

        final var result = SamlUtils.transformSamlObject(this.samlObjectBuilder.getConfigBean(), response, true).toString();
        LOGGER.debug("Generated Google SAML response: [{}]", result);
        return result;
    }

    /**
     * Create the private key.
     *
     * @throws Exception if key creation ran into an error
     */
    protected void createGoogleAppsPrivateKey() throws Exception {
        if (!isValidConfiguration()) {
            LOGGER.debug("Google Apps private key bean will not be created, because it's not configured");
            return;
        }

        final var bean = new PrivateKeyFactoryBean();

        if (this.privateKeyLocation.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
            bean.setLocation(new ClassPathResource(StringUtils.removeStart(this.privateKeyLocation, ResourceUtils.CLASSPATH_URL_PREFIX)));
        } else if (this.privateKeyLocation.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
            bean.setLocation(new FileSystemResource(StringUtils.removeStart(this.privateKeyLocation, ResourceUtils.FILE_URL_PREFIX)));
        } else {
            bean.setLocation(new FileSystemResource(this.privateKeyLocation));
        }

        bean.setAlgorithm(this.keyAlgorithm);
        LOGGER.debug("Loading Google Apps private key from [{}] with key algorithm [{}]",
            bean.getLocation(), bean.getAlgorithm());
        bean.afterPropertiesSet();
        LOGGER.debug("Creating Google Apps private key instance via [{}]", this.privateKeyLocation);
        this.privateKey = bean.getObject();
    }

    /**
     * Create the public key.
     *
     * @throws Exception if key creation ran into an error
     */
    protected void createGoogleAppsPublicKey() throws Exception {
        if (!isValidConfiguration()) {
            LOGGER.debug("Google Apps public key bean will not be created, because it's not configured");
            return;
        }

        final var bean = new PublicKeyFactoryBean();
        if (this.publicKeyLocation.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
            bean.setResource(new ClassPathResource(StringUtils.removeStart(this.publicKeyLocation, ResourceUtils.CLASSPATH_URL_PREFIX)));
        } else if (this.publicKeyLocation.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
            bean.setResource(new FileSystemResource(StringUtils.removeStart(this.publicKeyLocation, ResourceUtils.FILE_URL_PREFIX)));
        } else {
            bean.setResource(new FileSystemResource(this.publicKeyLocation));
        }

        bean.setAlgorithm(this.keyAlgorithm);
        LOGGER.debug("Loading Google Apps public key from [{}] with key algorithm [{}]",
            bean.getResource(), bean.getAlgorithm());
        bean.afterPropertiesSet();
        LOGGER.debug("Creating Google Apps public key instance via [{}]", this.publicKeyLocation);
        this.publicKey = bean.getObject();
    }

    private boolean isValidConfiguration() {
        return StringUtils.isNotBlank(this.privateKeyLocation)
            || StringUtils.isNotBlank(this.publicKeyLocation)
            || StringUtils.isNotBlank(this.keyAlgorithm);
    }

    @Override
    public boolean supports(final WebApplicationService service) {
        return service instanceof GoogleAccountsService;
    }
}
