package org.jboss.eap.qe.microprofile.openapi.integration.cdi;

import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.equalToIgnoringCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.eap.qe.microprofile.openapi.OpenApiDeploymentUrlProvider;
import org.jboss.eap.qe.microprofile.openapi.OpenApiServerConfiguration;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.RouterApplication;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.legacy.Contact;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.ConfigurationException;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientProvider;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientRelatedException;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.yaml.snakeyaml.Yaml;

/**
 * Test cases for MP OpenAPI and CDI integration, see https://javaee.github.io/tutorial/jaxrs-advanced004.html
 */
@RunWith(Arquillian.class)
@RunAsClient
public class IntegrationWithCDITest {
    private final static String ROUTER_DEPLOYMENT_NAME = "localServicesRouterDeployment";
    private static String openApiUrl;
    private static OnlineManagementClient onlineManagementClient;

    @BeforeClass
    public static void setup() throws ManagementClientRelatedException, ConfigurationException {
        openApiUrl = OpenApiDeploymentUrlProvider.composeDefaultOpenApiUrl();
        //  MP OpenAPI up
        onlineManagementClient = ManagementClientProvider.onlineStandalone();
        if (!OpenApiServerConfiguration.openapiSubsystemExists(onlineManagementClient)) {
            OpenApiServerConfiguration.enableOpenApi(onlineManagementClient);
        }
    }

    @AfterClass
    public static void tearDown() throws ManagementClientRelatedException, IOException {
        //  MP OpenAPI down
        try {
            OpenApiServerConfiguration.disableOpenApi(onlineManagementClient);
        } finally {
            onlineManagementClient.close();
        }
    }

    @Deployment(testable = false)
    public static Archive<?> localServicesRouterDeployment() {
        WebArchive deployment = ShrinkWrap.create(
                WebArchive.class, ROUTER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        RouterApplication.class,
                        Contact.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return deployment;
    }

    /**
     * @tpTestDetails Integration test to verify that a "legacy" CDI bean converted to "requestScoped" JAX-RS resource
     *                and having JAX-RS annotated constructor is returning expected content
     * @tpPassCrit The endpoint is returning expected response code and content
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testAppEndpoint() {
        String id = "1";
        String response = get(
                OpenApiDeploymentUrlProvider.composeDefaultDeploymentBaseUrl(
                        ROUTER_DEPLOYMENT_NAME + String.format("/contact/%s/details", id)))
                                .then()
                                .statusCode(200)
                                //  RestEasy >3.9.3 adds charset info unless resteasy.add.charset is set to false,
                                //  thus expecting for it to be there, see
                                //  https://docs.jboss.org/resteasy/docs/3.9.3.Final/userguide/html/Installation_Configuration.html#configuration_switches
                                .contentType(equalToIgnoringCase("text/plain;charset=UTF-8"))
                                .extract().asString();
        Assert.assertEquals(response, String.format("ID: %s", id));
    }

    /**
     * @tpTestDetails Tests for proper OpenAPI documentation of application endpoint represented by a JAX-RS resource
     *                having its constructor accepting a {@code @PathParam} annotated argument, see
     *                https://javaee.github.io/tutorial/jaxrs-advanced004.html
     * @tpPassCrit Verifies that the returned YAML data has corresponding values for JAX-RS annotations
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testOpenApiDocumentForDocumentedConstructorParam() {

        String responseContent = get(openApiUrl)
                .then()
                .statusCode(200)
                .extract().asString();

        Yaml yaml = new Yaml();
        Object yamlObject = yaml.load(responseContent);
        Map<String, Object> yamlMap = (Map<String, Object>) yamlObject;

        Map<String, Object> paths = (Map<String, Object>) yamlMap.get("paths");
        Assert.assertFalse("\"paths\" property is empty", paths.isEmpty());

        Map<String, Object> getConctactIdDetailsPath = (Map<String, Object>) paths.get("/contact/{id}/details");
        Assert.assertFalse("\"/contact/{id}/details\" property is empty", getConctactIdDetailsPath.isEmpty());

        Map<String, Object> getMethod = (Map<String, Object>) getConctactIdDetailsPath.get("get");
        Assert.assertFalse("\"/contact/{id}/details\" \"get\" property is empty", getMethod.isEmpty());
        Assert.assertNotNull("\"/contact/{id}/details\" \"responses\" for GET verb is null", getMethod.get("responses"));

        Map<String, Object> responses = (Map<String, Object>) getMethod.get("responses");
        Assert.assertNotNull("\"/contact/{id}/details\" \"response\" for GET verb and HTTP status 200 is null",
                responses.get(200));

        Map<String, Object> http200Response = (Map<String, Object>) responses.get(200);
        Assert.assertNotNull(
                "\"/contact/{id}/details\" \"response\" for GET verb and HTTP status 200 has null \"content\" property",
                http200Response.get("content"));

        Map<String, Object> http200ResponseContent = (Map<String, Object>) http200Response.get("content");
        Assert.assertNotNull(
                "\"/contact/{id}/details\" \"response\" for GET verb and HTTP status 200 has \"content\" but null \"application/json\" property",
                http200ResponseContent.get("text/plain"));

        List<Object> parameters = (List<Object>) getConctactIdDetailsPath.get("parameters");
        Assert.assertEquals("\"/contact/{id}/details\" operation for GET verb should have exactly 1 parameters",
                parameters.size(), 1);

        Map<String, Object> pathParam = (Map<String, Object>) parameters.get(0);
        Assert.assertFalse("Parameter [0] for \"/contact/{id}/details\" operation for GET verb is empty", pathParam.isEmpty());
        Assert.assertEquals(
                "\"name\" property of parameter [0] for \"/contact/{id}/details\" operation (GET verb) should be set to \"id\"",
                pathParam.get("name"), "id");
        Assert.assertEquals(
                "\"in\" property of parameter [0] for \"/contact/{id}/details\" operation (GET verb) should be set to \"path\"",
                pathParam.get("in"), "path");
        Assert.assertEquals(
                "\"required\" property of parameter [0] for \"/contact/{id}/details\" operation (GET verb) should be set to \"true\"",
                pathParam.get("required"), Boolean.TRUE);
    }
}