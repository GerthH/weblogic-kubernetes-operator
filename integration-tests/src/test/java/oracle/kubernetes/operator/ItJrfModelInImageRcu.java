// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
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

import oracle.kubernetes.operator.utils.DbUtils;
import oracle.kubernetes.operator.utils.DomainCrd;
//import oracle.kubernetes.operator.utils.ExecCommand;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.JrfDomain;
import oracle.kubernetes.operator.utils.LoggerHelper;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.Operator.RestCertType;
import oracle.kubernetes.operator.utils.RcuSecret;
import oracle.kubernetes.operator.utils.Secret;
import oracle.kubernetes.operator.utils.TestUtils;
import oracle.kubernetes.operator.utils.WalletPasswordSecret;
//import org.junit.jupiter.api.AfterAll;
//import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Simple JUnit test file used for testing Model in Image.
 *
 * <p>This test is used for creating domain using model in image.
 */

public class ItJrfModelInImageRcu extends MiiBaseTest {
  private static Operator operator;
  private static String domainNS;
  private static String testClassName;
  private static StringBuffer namespaceList;
  private static String rcuSchemaPrefix = "jrfmii";
  private static String rcuSchemaPass = "Oradoc_db1";
  private static String walletPassword = "welcome1";
  private static int dbPort;
  private static String dbNamespace;
  private static String dbUrl;
  
  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes.
   *
   * @throws Exception exception
   */
  @BeforeAll
  public static void staticPrepare() throws Exception {
    namespaceList = new StringBuffer();
    testClassName = new Object() {
    }.getClass().getEnclosingClass().getSimpleName();
    // initialize test properties and create the directories
    initialize(APP_PROPS_FILE, testClassName);
  }

  /**
   * This method gets called before every test. It creates the result/pv root directories
   * for the test. Creates the operator and domain if its not running.
   *
   * @throws Exception exception if result/pv/operator/domain creation fails
   */
  @BeforeEach
  public void prepare() throws Exception {

    createResultAndPvDirs(testClassName);
    
    TestUtils.exec(
            "cp -rf " 
            + BaseTest.getProjectRoot() 
            + "/kubernetes/samples/scripts " 
            + getResultDir(),
            true);
    /*
    //delete leftover pods caused by test being aborted
    
    DbUtils.deleteRcuPod(getResultDir());
    DbUtils.deleteDbPod(getResultDir());
         
    dbNamespace = "db" + String.valueOf(getNewSuffixCount());
    String command = "kubectl create namespace " + dbNamespace;
    LoggerHelper.getLocal().log(Level.INFO, "Created namespace " + dbNamespace);
    ExecCommand.exec(command);
    dbPort = 30011 + getNewSuffixCount();
    dbUrl = "oracle-db." + dbNamespace + ".svc.cluster.local:1521/devpdb.k8s";
    LoggerHelper.getLocal().log(Level.INFO,"For test: " + testClassName 
        + " dbNamespace is: " + dbNamespace + " dbUrl:" + dbUrl + " dbPort: " + dbPort);
    
    DbUtils.createDockerRegistrySecret(dbNamespace);
    DbUtils.startOracleDB(getResultDir(), String.valueOf(dbPort), dbNamespace);
    DbUtils.createRcuSchema(getResultDir(),rcuSchemaPrefix, dbUrl, dbNamespace);
    */
    dbNamespace = "db" + String.valueOf(getNewSuffixCount());
    DbUtils.createNamespace(dbNamespace);
    dbPort = 30011 + getNewSuffixCount();
    dbUrl = "oracle-db." + dbNamespace + ".svc.cluster.local:1521/devpdb.k8s";
    LoggerHelper.getLocal().log(Level.INFO,"For test: " + testClassName 
        + " dbNamespace is: " + dbNamespace + " dbUrl:" + dbUrl + " dbPort: " + dbPort);
    DbUtils.createDbRcu(getResultDir(), dbPort, dbUrl, rcuSchemaPrefix, dbNamespace);
    
    // create operator1
    if (operator == null) {
      Map<String, Object> operatorMap = createOperatorMap(getNewSuffixCount(),
          true, testClassName);
      operator = TestUtils.createOperator(operatorMap, RestCertType.SELF_SIGNED);
      Assertions.assertNotNull(operator);
      domainNS = ((ArrayList<String>) operatorMap.get("domainNamespaces")).get(0);
      namespaceList.append((String)operatorMap.get("namespace"));
      namespaceList.append(" ").append(domainNS);
    }
  }
  
  //@AfterEach
  public void unPrepare() throws Exception {
    DbUtils.deleteRcuPod(getResultDir());
    DbUtils.deleteDbPod(getResultDir());
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories.
   *
   * @throws Exception when errors while running statedump.sh or cleanup.sh
   *         scripts or while renewing the lease for shared cluster run
   */
  //@AfterAll
  public static void staticUnPrepare() throws Exception {
    tearDown(new Object() {
    }.getClass().getEnclosingClass().getSimpleName(), namespaceList.toString());

    LoggerHelper.getLocal().info("SUCCESS");
  }

  /**
   * Create and deploy a JRF domain using model in image. Save and restore walletFileSecre.  
   * Modify the original domain yaml file to create a new one with same namespace, different domain UID 
   * different domain name and different domainHome. Startup the domain with new domain yaml file. Verify 
   * in the same namespace there are 2 domains sharing the dame RCU schema.
   *
   * @throws Exception when test fails 
   */
  @Test
  public void testReuseRCU2Deployments() throws Exception {
    Assumptions.assumeTrue(QUICKTEST);
    String testMethodName = new Object() {
    }.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);
    LoggerHelper.getLocal().log(Level.INFO,
        "Creating Domain & waiting for the script to complete execution");
    JrfDomain jrfdomain1 = null;
    JrfDomain jrfdomain2 = null;
    boolean testCompletedSuccessfully = false;
    try {
      Map<String, Object> domainMap =
          createModelInImageMap(getNewSuffixCount(), testClassName);
      domainMap.put("domainUID","jrfrcudomain2");
      domainMap.put("namespace", domainNS);
      domainMap.put("wdtModelFile", "./model.jrf2.yaml");
      domainMap.put("wdtModelPropertiesFile", "./model.properties");
      domainMap.put("domainHomeImageBase", BaseTest.getfmwImageName() + ":" + BaseTest.getfmwImageTag());
      domainMap.put("rcuSchemaPrefix", rcuSchemaPrefix);
      LoggerHelper.getLocal().log(Level.INFO, "DEBUG " + testClassName + "domain: dbUrl: " + dbUrl);
      domainMap.put("rcuDatabaseURL", dbUrl);
      domainMap.put("wdtDomainType", "JRF");
      domainMap.put("introspectorJobActiveDeadlineSeconds", "300");
      
      String domainUid = (String)domainMap.get("domainUID");
      String namespace = (String)domainMap.get("namespace");
      
      //create rcuAccess secret and walletPassword secret
      LoggerHelper.getLocal().log(Level.INFO, "DEBUG: going to create rcuAccess secret and walletPassword secret " 
          + testClassName + "domainUid " + domainUid + " namespace: " + namespace);
      Secret rcuAccess = new RcuSecret(namespace, domainUid + "-rcu-access", 
          rcuSchemaPrefix, rcuSchemaPass, dbUrl);
      Secret walletPass = new WalletPasswordSecret(namespace, domainUid 
          + "-opss-wallet-password-secret", walletPassword);
      
      jrfdomain1 = new JrfDomain(domainMap);
      
      jrfdomain1.verifyDomainCreated(40);
      
      //save and restore walletFile secret
      saveWalletFileSecret(getResultDir(), domainUid, namespace);
      String walletFileSecretName = domainUid + "-opss-walletfile-secret";
      restoreWalletFileSecret(getResultDir(), domainUid, namespace, walletFileSecretName);
      
      //shutdown the domain
      //jrfdomain.shutdownUsingServerStartPolicy();
      
      //modify the original domain to enable walletFileSecret
      String originalYaml = getUserProjectsDir() + "/weblogic-domains/" + jrfdomain1.getDomainUid()
          + "/domain.yaml"; 
      DomainCrd crd = new DomainCrd(originalYaml);
      Map<String, String> opssNode = new HashMap();
      opssNode.put("walletFileSecret", walletFileSecretName);
      crd.addObjectNodeToOpss(opssNode);
      
      //modify the original domain to change domain name, domainUID and domainHome
      String domain = testClassName.toLowerCase() + "-domain-" + getNewSuffixCount();
      String domainHome = "/u01/oracle/user_projects/domains" + domain;
      int nodePort = 30800 + getNewSuffixCount();
      crd.changeDomainName(domain);
      crd.changeDomainUID(domain);
      crd.changeDomainHome(domainHome);
      crd.changeAdminserverNodeport(nodePort);
      String modYaml = crd.getYamlTree();
      LoggerHelper.getLocal().log(Level.INFO, modYaml);
      // Write the modified yaml to a new file
      Path path = Paths.get(getUserProjectsDir() + "/weblogic-domains/" + jrfdomain1.getDomainUid(),
          "modified.domain.yaml");
      LoggerHelper.getLocal().log(Level.INFO, "Path of the modified domain.yaml :{0}", path.toString());
      Charset charset = StandardCharsets.UTF_8;
      Files.write(path, modYaml.getBytes(charset));
      
      //Use the new yaml to startup the domain
      LoggerHelper.getLocal().log(Level.INFO, "kubectl apply -f {0}", path.toString());
      ExecResult exec = TestUtils.exec("kubectl apply -f " + path.toString());
      LoggerHelper.getLocal().log(Level.INFO, exec.stdout());
      
      //jrfdomain2 = new JrfDomain(path.toString());
      //jrfdomain2.verifyDomainCreated(40);
      testCompletedSuccessfully = true;

    } catch (Exception ex) {
      ex.printStackTrace();
      Assertions.fail("FAILED - " + testMethodName);
    } finally {
      /*if (jrfdomain != null && (JENKINS || testCompletedSuccessfully)) {
        LoggerHelper.getLocal().log(Level.INFO, "jrfdomain1 DONE!!!");
        TestUtils.deleteWeblogicDomainResources(jrfdomain.getDomainUid());
      }
      if (jrfdomain2 != null && (JENKINS || testCompletedSuccessfully)) {
        LoggerHelper.getLocal().log(Level.INFO, "jrfdomain2 DONE!!!");
        TestUtils.deleteWeblogicDomainResources(jrfdomain.getDomainUid());
      }*/
      LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethodName);
    }

    LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethodName);
  }
  
  private static void saveWalletFileSecret(String scriptsDir, String domainUid, String nameSpace)throws Exception {
    String cmd = "sh " 
        + scriptsDir
        + "/scripts/create-weblogic-domain/model-in-image/opss_wallet_util.sh -d "
        + domainUid
        + " -n "
        + nameSpace
        + " -s";
    try {
      TestUtils.exec(cmd, true);
    } catch (Exception ex) {
      ex.printStackTrace();
      Assertions.fail("Failed to excute command.\n", ex.getCause());
    }   
  }
  
  private static void restoreWalletFileSecret(String scriptsDir, String domainUid, String nameSpace, 
      String secretName)throws Exception {
    String cmd = "sh " 
        + scriptsDir
        + "/scripts/create-weblogic-domain/model-in-image/opss_wallet_util.sh -d "
        + domainUid
        + " -n "
        + nameSpace
        + " -r"
        + " -ws "
        + secretName;
    try {
      TestUtils.exec(cmd, true);
    } catch (Exception ex) {
      ex.printStackTrace();
      Assertions.fail("Failed to excute command.\n", ex.getCause());
    }   
  }
       
}

