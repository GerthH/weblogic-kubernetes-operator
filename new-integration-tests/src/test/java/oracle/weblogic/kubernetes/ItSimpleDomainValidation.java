// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ObjectMetaBuilder;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1PersistentVolumeSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.kubernetes.actions.TestActions;
import oracle.weblogic.kubernetes.actions.impl.OperatorParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.annotations.tags.Slow;
import oracle.weblogic.kubernetes.extensions.LoggedTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_CHART_DIR;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_EMAIL;
import static oracle.weblogic.kubernetes.TestConstants.REPO_PASSWORD;
import static oracle.weblogic.kubernetes.TestConstants.REPO_REGISTRY;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_USERNAME;
import static oracle.weblogic.kubernetes.actions.TestActions.createDockerConfigJson;
import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.createSecret;
import static oracle.weblogic.kubernetes.actions.TestActions.createServiceAccount;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorImageName;
import static oracle.weblogic.kubernetes.actions.TestActions.installOperator;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.isHelmReleaseDeployed;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.operatorIsRunning;
import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Simple validation of basic domain functions")
// Every test class needs to tagged with this annotation for log collection, diagnostic messages logging
// and namespace creation.
@IntegrationTest
class ItSimpleDomainValidation implements LoggedTest {

  final String domainUid = "domain1";
  String domainNamespace;
  String opNamespace;
  String serviceAccountName;
  V1ServiceAccount serviceAccount;
  String pvcName;
  String pvName;
  private HelmParams opHelmParams = null;

  private void installingOperator() {

    // Create a service account for the unique opNamespace
    logger.info("Creating service account");
    String serviceAccountName = opNamespace + "-sa";
    assertDoesNotThrow(() -> createServiceAccount(new V1ServiceAccount()
        .metadata(
            new V1ObjectMeta()
                .namespace(opNamespace)
                .name(serviceAccountName))));
    logger.info("Created service account: {0}", serviceAccountName);

    String image = getOperatorImageName();
    assertFalse(image.isEmpty(), "Operator image name can not be empty");
    logger.info("Operator image name {0}", image);

    // Create docker registry secret in the operator namespace to pull the image from repository
    logger.info("Creating docker registry secret in namespace {0}", opNamespace);
    JsonObject dockerConfigJsonObject = createDockerConfigJson(
        REPO_USERNAME, REPO_PASSWORD, REPO_EMAIL, REPO_REGISTRY);
    String dockerConfigJson = dockerConfigJsonObject.toString();

    // Create the V1Secret configuration
    V1Secret repoSecret = new V1Secret()
        .metadata(new V1ObjectMeta()
            .name(REPO_SECRET_NAME)
            .namespace(opNamespace))
        .type("kubernetes.io/dockerconfigjson")
        .putDataItem(".dockerconfigjson", dockerConfigJson.getBytes());

    boolean secretCreated = assertDoesNotThrow(() -> createSecret(repoSecret),
        String.format("createSecret failed for %s", REPO_SECRET_NAME));
    assertTrue(secretCreated, String.format("createSecret failed while creating secret %s", REPO_SECRET_NAME));

    // map with secret
    Map<String, Object> secretNameMap = new HashMap<String, Object>();
    secretNameMap.put("name", REPO_SECRET_NAME);

    // helm install parameters
    opHelmParams = new HelmParams()
        .releaseName(OPERATOR_RELEASE_NAME)
        .namespace(opNamespace)
        .chartDir(OPERATOR_CHART_DIR);

    // Operator chart values to override
    OperatorParams opParams =
        new OperatorParams()
            .helmParams(opHelmParams)
            .image(image)
            .imagePullSecrets(secretNameMap)
            .domainNamespaces(Arrays.asList(domainNamespace))
            .serviceAccount(serviceAccountName);

    // install Operator
    logger.info("Installing Operator in namespace {0}", opNamespace);
    assertTrue(installOperator(opParams),
        String.format("Operator install failed in namespace %s", opNamespace));
    logger.info("Operator installed in namespace {0}", opNamespace);

    // list helm releases matching Operator release name in operator namespace
    logger.info("Checking Operator release {0} status in namespace {1}",
        OPERATOR_RELEASE_NAME, opNamespace);
    assertTrue(isHelmReleaseDeployed(OPERATOR_RELEASE_NAME, opNamespace),
        String.format("Operator release %s is not in deployed status in namespace %s",
        OPERATOR_RELEASE_NAME, opNamespace));
    logger.info("Operator release {0} status is deployed in namespace {1}",
        OPERATOR_RELEASE_NAME, opNamespace);

    // check operator is running
    logger.info("Check Operator pod is running in namespace {0}", opNamespace);
    with().pollDelay(2, SECONDS)
        .and().with().pollInterval(10, SECONDS)
        .atMost(5, MINUTES).await()
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for operator to be running in namespace {0} "
                    + "(elapsed time {1}ms, remaining time {2}ms)",
                opNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(operatorIsRunning(opNamespace));

  }

  /**
   * Setup for test suite. Creates service account, namespace, and persistent volumes.
   * @param namespaces injected by Junit extension
   */
  @BeforeAll
  public void setup(@Namespaces(2) List<String> namespaces) {

    // get a new unique namespace
    logger.info("Assigning unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace is null");
    opNamespace = namespaces.get(0);

    logger.info("Assigning unique namespace for domain");
    assertNotNull(namespaces.get(1), "Namespace is null");
    domainNamespace = namespaces.get(1);

    installingOperator();

    // Create a service account for the unique namespace
    serviceAccountName = domainNamespace + "-sa";
    serviceAccount = assertDoesNotThrow(
        () -> Kubernetes.createServiceAccount(new V1ServiceAccount()
            .metadata(new V1ObjectMeta().namespace(domainNamespace).name(serviceAccountName))));
    logger.info("Created service account: {0}", serviceAccount.getMetadata().getName());

    // create persistent volume and persistent volume claim
    pvcName = domainUid + "-pvc"; // name of the persistent volume claim
    pvName = domainUid + "-pv"; // name of the persistent volume

    V1PersistentVolumeClaim v1pvc = new V1PersistentVolumeClaim()
        .spec(new V1PersistentVolumeClaimSpec()
            .addAccessModesItem("ReadWriteMany")
            .storageClassName(domainUid + "-weblogic-domain-storage-class")
            .volumeName(pvName)
            .resources(new V1ResourceRequirements()
                .putRequestsItem("storage", Quantity.fromString("10Gi"))))
        .metadata(new V1ObjectMetaBuilder()
            .withName(pvcName)
            .withNamespace(domainNamespace)
            .build()
            .putLabelsItem("weblogic.resourceVersion", "domain-v2")
            .putLabelsItem("weblogic.domainUid", domainUid));

    boolean success = assertDoesNotThrow(
        () -> TestActions.createPersistentVolumeClaim(v1pvc),
        "Persistent volume claim creation failed, "
        + "look at the above console log messages for failure reason in ApiException responsebody"
    );
    assertTrue(success, "PersistentVolumeClaim creation failed");

    V1PersistentVolume v1pv = new V1PersistentVolume()
        .spec(new V1PersistentVolumeSpec()
            .addAccessModesItem("ReadWriteMany")
            .storageClassName(domainUid + "-weblogic-domain-storage-class")
            .volumeMode("Filesystem")
            .putCapacityItem("storage", Quantity.fromString("10Gi"))
            .persistentVolumeReclaimPolicy("Recycle")
            .hostPath(new V1HostPathVolumeSource()
                .path(System.getProperty("java.io.tmpdir") + "/" + domainUid + "-persistentVolume")))
        .metadata(new V1ObjectMetaBuilder()
            .withName(pvName)
            .withNamespace(domainNamespace)
            .build()
            .putLabelsItem("weblogic.resourceVersion", "domain-v2")
            .putLabelsItem("weblogic.domainUid", domainUid));
    success = assertDoesNotThrow(
        () -> TestActions.createPersistentVolume(v1pv),
        "Persistent volume creation failed, "
        + "look at the above console log messages for failure reason in ApiException responsebody"
    );
    assertTrue(success, "PersistentVolume creation failed");
  }

  /**
   * Create a simple domain and checks if pods are coming up.
   */
  @Test
  @DisplayName("Create a domain")
  @Slow
  public void testCreatingDomain() {

    // create the domain CR
    V1ObjectMeta metadata = new V1ObjectMetaBuilder()
        .withName(domainUid)
        .withNamespace(domainNamespace)
        .build();
    DomainSpec domainSpec = new DomainSpec()
        .domainHome("/shared/domains/sample-domain1")
        .domainHomeInImage(false)
        .image("store/oracle/weblogic:12.2.1.3")
        .imagePullPolicy("IfNotPresent");
    Domain domain = new Domain()
        .apiVersion("weblogic.oracle/v7")
        .kind("Domain")
        .metadata(metadata)
        .spec(domainSpec);
    boolean success = assertDoesNotThrow(
        () -> createDomainCustomResource(domain),
        "Domain failed to be created, "
        + "look at the above console log messages for failure reason in ApiException responsebody"
    );
    assertTrue(success);

    // wait for the domain to exist
    with().pollDelay(2, SECONDS)
        .and().with().pollInterval(10, SECONDS)
        .conditionEvaluationListener(
            condition -> logger.info(
                "Waiting for domain to be running (elapsed time {0} ms, remaining time {1} ms)",
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        // and here we can set the maximum time we are prepared to wait
        .await().atMost(5, MINUTES)
        // operatorIsRunning() is one of our custom, reusable assertions
        .until(domainExists(domainUid, "v7", domainNamespace));
  }

}
