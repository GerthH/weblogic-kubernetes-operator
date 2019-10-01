// Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.DomainCrd;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.LoggerHelper;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Simple JUnit test file used for testing domain pod templates.
 *
 * <p>This test is used for creating Operator(s) and domain which uses pod templates.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItPodTemplates extends BaseTest {

  private static Operator operator1;
  private static String domainNS;
  private static String testClassName;

  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes.
   *
   * @throws Exception exception
   */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    // initialize test properties and create the directories
    if (QUICKTEST) {
      testClassName = new Object() {
      }.getClass().getEnclosingClass().getSimpleName();

      // initialize test properties and create the directories
      initialize(APP_PROPS_FILE, testClassName);
      // create operator1
      if (operator1 == null) {
        Map<String, Object> operatorMap = TestUtils.createOperatorMap(getNewNumber(), true, testClassName);
        operator1 = TestUtils.createOperator(operatorMap, Operator.RestCertType.SELF_SIGNED);
        Assert.assertNotNull(operator1);
        domainNS = ((ArrayList<String>) operatorMap.get("domainNamespaces")).get(0);
      }
    }


  }

  /**
   * Releases k8s cluster lease, archives result, pv directories.
   *
   * @throws Exception exception
   */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    if (QUICKTEST) {
      LoggerHelper.getLocal().log(Level.INFO, "+++++++++++++++++++++++++++++++++---------------------------------+");
      LoggerHelper.getLocal().log(Level.INFO, "BEGIN");
      LoggerHelper.getLocal().log(Level.INFO, "Run once, release cluster lease");

      tearDown(new Object() {
      }.getClass().getEnclosingClass().getSimpleName());

      LoggerHelper.getLocal().log(Level.INFO, "SUCCESS");
    }
  }

  /**
   * Test pod templates using all the variables $(SERVER_NAME), $(DOMAIN_NAME), $(DOMAIN_UID),
   * $(DOMAIN_HOME), $(LOG_HOME) and $(CLUSTER_NAME) in serverPod. Make sure the domain comes up
   * successfully.
   *
   * @throws Exception when the domain crd creation fails or when updating the serverPod with
   *                   variables
   */
  @Test
  public void testPodTemplateUsingVariables() throws Exception {
    Assume.assumeTrue(QUICKTEST);
    String testMethodName = new Object() {
    }.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);
    LoggerHelper.getLocal().log(Level.INFO, "Creating Operator & waiting for the script to complete execution");
    Domain domain = null;
    boolean testCompletedSuccessfully = false;
    try {
      Map<String, Object> domainMap = TestUtils.createDomainMap(getNewNumber(), testClassName);
      // domainMap.put("domainUID", "podtemplatedomain");
      domainMap.put("namespace", domainNS);
      // just create domain yaml, dont apply
      domain = TestUtils.createDomain(domainMap, false);
      String originalYaml =
          BaseTest.getUserProjectsDir()
              + "/weblogic-domains/"
              + domain.getDomainUid()
              + "/domain.yaml";

      // Modify the original domain yaml to include labels in serverPod
      // node
      final DomainCrd crd = new DomainCrd(originalYaml);
      // add labels to serverPod
      Map<String, String> labelKeyValue = new HashMap();
      labelKeyValue.put("servername", "$(SERVER_NAME)");
      labelKeyValue.put("domainname", "$(DOMAIN_NAME)");
      labelKeyValue.put("domainuid", "$(DOMAIN_UID)");
      crd.addObjectNodeToServerPod("labels", labelKeyValue);

      // add annotations to serverPod as DOMAIN_HOME and LOG_HOME contains "/" which is not allowed
      // in labels
      Map<String, String> envKeyValue = new HashMap();
      envKeyValue.put("domainhome", "$(DOMAIN_HOME)");
      envKeyValue.put("loghome", "$(LOG_HOME)");
      crd.addObjectNodeToServerPod("annotations", envKeyValue);

      // add label to cluster serverPod for CLUSTER_NAME
      Map<String, String> clusterLabelKeyValue = new HashMap();
      clusterLabelKeyValue.put("clustername", "$(CLUSTER_NAME)");
      crd.addObjectNodeToClusterServerPod(domain.getClusterName(), "labels", clusterLabelKeyValue);

      String modYaml = crd.getYamlTree();
      LoggerHelper.getLocal().log(Level.INFO, modYaml);

      // Write the modified yaml to a new file
      Path path =
          Paths.get(
              BaseTest.getUserProjectsDir() + "/weblogic-domains/" + domain.getDomainUid(),
              "domain.modified.yaml");
      LoggerHelper.getLocal().log(Level.INFO, "Path of the modified domain.yaml :{0}", path.toString());
      Charset charset = StandardCharsets.UTF_8;
      Files.write(path, modYaml.getBytes(charset));

      // Apply the new yaml to update the domain
      LoggerHelper.getLocal().log(Level.INFO, "kubectl apply -f {0}", path.toString());
      ExecResult exec = TestUtils.exec("kubectl apply -f " + path.toString());
      LoggerHelper.getLocal().log(Level.INFO, exec.stdout());

      domain.verifyDomainCreated();
      testCompletedSuccessfully = true;
    } finally {
      if (domain != null && (JENKINS || testCompletedSuccessfully)) {
        domain.shutdownUsingServerStartPolicy();
        TestUtils.deleteWeblogicDomainResources(domain.getDomainUid());
        TestUtils.verifyAfterDeletion(domain);
      }
    }

    LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethodName);
  }
}
