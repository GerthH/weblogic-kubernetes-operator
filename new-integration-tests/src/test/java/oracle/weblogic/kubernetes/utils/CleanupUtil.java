// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Ingress;
import io.kubernetes.client.openapi.models.V1ClusterRole;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1ReplicaSet;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.kubernetes.TestConstants;
import oracle.weblogic.kubernetes.actions.TestActions;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import org.awaitility.core.ConditionFactory;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;
import static org.awaitility.Awaitility.with;

/**
 * The CleanupUtil class is used for cleaning up all the Kubernetes artifacts left behind by the integration tests.
 *
 */
public class CleanupUtil {

  private static ConditionFactory withStandardRetryPolicy = null;
  private static final boolean DEBUG = true;

  /**
   * Cleanup all artifacts in the Kubernetes cluster.
   *
   * <p>First it tries to do a graceful deletion of the domain and operator and then deletes everything else in the
   * namespaces.
   *
   * <p>Waits for the deletion to be completed until up to 3 minutes.
   *
   * @param namespaces list of namespaces
   */
  public static void cleanup(List<String> namespaces) {
    try {
      // iterate through the namespaces and delete domain, as a
      // first entity if its not operator namespace
      for (var namespace : namespaces) {
        if (!isOperatorNamespace(namespace)) {
          deleteDomains(namespace);
        }
      }
      // iterate through the namespaces and delete operator if
      // its operator namespace.
      for (var namespace : namespaces) {
        if (isOperatorNamespace(namespace)) {
          uninstallOperator(namespace);
        }
      }

      // uninstall traefik using helm
      // Delete all the artifacts in the list of namespaces.
      // This will cleanup everything regardless of the domain
      // is deleted and operator is uninstalled
      for (var namespace : namespaces) {
        deleteArtifacts(namespace);
      }
      // wait for the artifacts to be deleted, waiting for a maximum of 3 minutes
      withStandardRetryPolicy = with().pollDelay(2, SECONDS)
          .and().with().pollInterval(10, SECONDS)
          .atMost(3, MINUTES).await();
      namespaces.stream().map((namespace) -> {
        logger.info("Check for artifacts in namespace {0}", namespace);
        return namespace;
      }).forEachOrdered((namespace) -> {
        withStandardRetryPolicy
            .conditionEvaluationListener(
                condition -> logger.info("Waiting for artifacts to be deleted in namespace {0}, "
                    + "(elapsed time {1} , remaining time {2}",
                    namespace,
                    condition.getElapsedTimeInMS(),
                    condition.getRemainingTimeInMS()))
            .until(artifactsDoesntExist(namespace));
      });
    } catch (Exception ex) {
      logger.warning("Cleanup failed");
    }
  }

  /**
   * Delete domains.
   *
   * @param namespace name of the namespace
   */
  private static void deleteDomains(String namespace) {
    try {
      for (var item : Kubernetes.listDomains(namespace).getItems()) {
        String domainUid = item.getMetadata().getName();
        logger.info("Deleting domain {0}", domainUid);
        Kubernetes.deleteDomainCustomResource(domainUid, namespace);
        // do we need to wait here until all the server pods are deleted ?
      }
    } catch (Exception ex) {
      logger.severe(ex.getMessage());
      logger.severe("Failed to delete domain or the {0} is not a domain namespace", namespace);
    }
  }

  /**
   * Uninstalls the operator.
   *
   * @param namespace name of the namespace
   */
  private static void uninstallOperator(String namespace) {
    HelmParams opHelmParams = new HelmParams()
        .releaseName(TestConstants.OPERATOR_RELEASE_NAME)
        .namespace(namespace);
    TestActions.uninstallOperator(opHelmParams);
  }

  /**
   * Returns true if the namespace is operator namespace, otherwise false.
   *
   * @param namespace name of the namespace
   * @return true if the namespace is operator namespace, otherwise false
   * @throws ApiException when Kubernetes cluster query fails
   */
  public static boolean isOperatorNamespace(String namespace) throws ApiException {
    return !Kubernetes.listPods(namespace, "weblogic.operatorName").getItems().isEmpty();
  }

  /**
   * Returns true if no artifacts exists in given namespace.
   *
   * @param namespace name of the namespace
   * @return true if none of the artifacts exists, false otherwise
   */
  public static Callable<Boolean> artifactsDoesntExist(String namespace) {
    return () -> {
      return !artifactsExists(namespace);
    };
  }

  /**
   * Returns true if artifacts exists in the Kubernetes cluster.
   *
   * @param namespace name of the namespace
   * @return true if at least one artifact exist otherwise false
   */
  public static boolean artifactsExists(String namespace) {
    boolean exist = false;
    logger.info("Checking if artifacts exists in namespace {0}\n", namespace);

    // Check if domain exists
    try {
      if (!Kubernetes.listDomains(namespace).getItems().isEmpty()) {
        logger.info("Domain still exists !!!");
        List<Domain> items = Kubernetes.listDomains(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("DomainList is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list domains");
    }

    // Check if the replica sets exist
    try {
      if (!Kubernetes.listReplicaSets(namespace).getItems().isEmpty()) {
        logger.info("ReplicaSets still exists!!!");
        List<V1ReplicaSet> items = Kubernetes.listReplicaSets(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("ReplicaSet is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list replica sets");
    }

    // check if the jobs exist
    try {
      if (!Kubernetes.listJobs(namespace).getItems().isEmpty()) {
        logger.info("Jobs still exists!!!");
        List<V1Job> items = Kubernetes.listJobs(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("JobList is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list jobs");
    }

    // check if the configmaps exist
    try {
      if (!Kubernetes.listConfigMaps(namespace).getItems().isEmpty()) {
        logger.info("Config Maps still exists!!!");
        List<V1ConfigMap> items = Kubernetes.listConfigMaps(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("ConfigMap list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list config maps");
    }

    // check if the secrets exist
    try {
      if (!Kubernetes.listSecrets(namespace).getItems().isEmpty()) {
        logger.info("Secrets still exists!!!");
        List<V1Secret> items = Kubernetes.listSecrets(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Secret list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list secrets");
    }

    // check if persistent volume exist
    try {
      for (var item : Kubernetes.listPersistentVolumeClaims(namespace).getItems()) {
        String label = Optional.ofNullable(item)
            .map(pvc -> pvc.getMetadata())
            .map(metadata -> metadata.getLabels())
            .map(labels -> labels.get("weblogic.domainUID")).get();
        if (label != null) {
          if (!Kubernetes.listPersistentVolumes(
              String.format("weblogic.domainUID = %s", label))
              .getItems().isEmpty()) {
            logger.info("Persistent Volumes still exists!!!");
            List<V1PersistentVolume> items = Kubernetes.listPersistentVolumes(
                String.format("weblogic.domainUID = %s", label))
                .getItems();
            items.forEach((item1) -> {
              debug(item1.getMetadata().getName());
            });
            exist = true;
          } else {
            logger.info("Persistent Volume List is empty");
          }
        }
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list persistent volumes");
    }

    // check if deployments exist
    try {
      if (!Kubernetes.listDeployments(namespace).getItems().isEmpty()) {
        logger.info("Deployments still exists!!!");
        List<V1Deployment> items = Kubernetes.listDeployments(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Deployment list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list deployments");
    }

    // get pvc
    try {
      if (!Kubernetes.listPersistentVolumeClaims(namespace).getItems().isEmpty()) {
        logger.info("Persistent Volumes Claims still exists!!!");
        List<V1PersistentVolumeClaim> items = Kubernetes.listPersistentVolumeClaims(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Persistent Volume Claims list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list persistent volume claims");
    }

    // check if services exist
    try {
      if (!Kubernetes.listServices(namespace).getItems().isEmpty()) {
        logger.info("Services still exists!!!");
        List<V1Service> items = Kubernetes.listServices(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Services list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list services");
    }

    // check if service accounts exist
    try {
      if (!Kubernetes.listServiceAccounts(namespace).getItems().isEmpty()) {
        logger.info("Service Accounts still exists!!!");
        List<V1ServiceAccount> items = Kubernetes.listServiceAccounts(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Service Account list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list service accounts");
    }

    // check if ingress exist
    try {
      if (!Kubernetes.listIngressExtensions(namespace).getItems().isEmpty()) {
        logger.info("Ingress Extensions still exists!!!");
        List<ExtensionsV1beta1Ingress> items = Kubernetes.listIngressExtensions(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Ingress Extensions list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list Ingress Extensions");
    }

    // check if namespaced roles exist
    try {
      if (!Kubernetes.listNamespacedRole(namespace).getItems().isEmpty()) {
        logger.info("Namespaced roles still exists!!!");
        List<V1Role> items = Kubernetes.listNamespacedRole(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Namespaced role list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list namespaced roles");
    }

    // check if namespaced role bindings exist
    try {
      if (!Kubernetes.listNamespacedRoleBinding(namespace).getItems().isEmpty()) {
        logger.info("Namespaced role bindings still exists!!!");
        List<V1RoleBinding> items = Kubernetes.listNamespacedRoleBinding(namespace).getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Namespaced role bindings list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list namespaced role bindings");
    }

    // check if cluster roles exist, check with selector 'weblogic.operatorName'
    try {
      if (!Kubernetes.listClusterRoles("weblogic.operatorName").getItems().isEmpty()) {
        logger.info("Cluster Roles still exists!!!");
        List<V1ClusterRole> items = Kubernetes.listClusterRoles("weblogic.operatorName").getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Cluster Roles list is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list cluster roles");
    }

    // check if cluster rolebindings exist, check with selector 'weblogic.operatorName'
    try {
      if (!Kubernetes.listClusterRoleBindings("weblogic.operatorName").getItems().isEmpty()) {
        logger.info("Cluster RoleBindings still exists!!!");
        List<V1RoleBinding> items = Kubernetes.listClusterRoleBindings("weblogic.operatorName").getItems();
        items.forEach((item) -> {
          debug(item.getMetadata().getName());
        });
        exist = true;
      } else {
        logger.info("Cluster RoleBindings is empty");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list Cluster Role Bindings");
    }

    // get namespaces
    try {
      if (Kubernetes.listNamespaces().contains(namespace)) {
        logger.info("Namespace still exists!!!");
        List<String> items = Kubernetes.listNamespaces();
        items.forEach((item) -> {
          debug(item);
        });
        exist = true;
      } else {
        logger.info("Namespace doesn't exist");
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to list namespaces");
    }

    return exist;

  }

  /**
   * Deletes the artifacts in the Kubernetes cluster in the given namespace.
   *
   * @param namespace name of the namespace
   */
  public static void deleteArtifacts(String namespace) {
    logger.info("Cleaning up artifacts in namespace {0}", namespace);

    // Delete all Domain objects in given namespace
    try {
      for (var item : Kubernetes.listDomains(namespace).getItems()) {
        Kubernetes.deleteDomainCustomResource(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup domains");
    }

    // Delete replicasets
    try {
      for (var item : Kubernetes.listReplicaSets(namespace).getItems()) {
        Kubernetes.deleteReplicaSet(namespace, item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup replica sets");
    }

    // Delete jobs
    try {
      for (var item : Kubernetes.listJobs(namespace).getItems()) {
        Kubernetes.deleteJob(namespace, item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup jobs");
    }

    // Delete configmaps
    try {
      for (var item : Kubernetes.listConfigMaps(namespace).getItems()) {
        Kubernetes.deleteConfigMap(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup config maps");
    }

    // Delete secrets
    try {
      for (var item : Kubernetes.listSecrets(namespace).getItems()) {
        Kubernetes.deleteSecret(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup secrets");
    }

    // Delete pv
    try {
      for (var item : Kubernetes.listPersistentVolumeClaims(namespace).getItems()) {
        String label = Optional.ofNullable(item)
            .map(pvc -> pvc.getMetadata())
            .map(metadata -> metadata.getLabels())
            .map(labels -> labels.get("weblogic.domainUID")).get();
        if (label != null) {
          logger.info("Deleting all Persistent Volumes with label weblogic.domainUID: {0}", label);
          for (var pv : Kubernetes
              .listPersistentVolumes(String.format("weblogic.domainUID = %s", label))
              .getItems()) {
            Kubernetes.deletePv(pv.getMetadata().getName());
          }
        }
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup persistent volumes");
    }

    // Delete deployments
    try {
      for (var item : Kubernetes.listDeployments(namespace).getItems()) {
        Kubernetes.deleteDeployment(namespace, item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup deployments");
    }

    // Delete pvc
    try {
      for (var item : Kubernetes.listPersistentVolumeClaims(namespace).getItems()) {
        Kubernetes.deletePvc(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup persistent volume claims");
    }

    // Delete services
    try {
      for (var item : Kubernetes.listServices(namespace).getItems()) {
        Kubernetes.deleteService(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup services");
    }

    // Delete cluster roles
    try {
      for (var item : Kubernetes.listClusterRoles("weblogic.operatorName").getItems()) {
        Kubernetes.deleteClusterRole(item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup cluster roles");
    }

    // Delete cluster rolebindings
    try {
      for (var item : Kubernetes.listClusterRoleBindings("weblogic.operatorName").getItems()) {
        Kubernetes.deleteClusterRoleBinding(item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup cluster rolebindings");
    }

    // Delete namespaced roles
    try {
      for (var item : Kubernetes.listNamespacedRole(namespace).getItems()) {
        Kubernetes.deleteNamespacedRole(namespace, item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup namespaced roles");
    }

    // Delete namespaced role bindings
    try {
      for (var item : Kubernetes.listNamespacedRoleBinding(namespace).getItems()) {
        Kubernetes.deleteNamespacedRoleBinding(namespace, item.getMetadata().getName());
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup namespaced rolebindings");
    }

    // Delete service accounts
    try {
      for (var item : Kubernetes.listServiceAccounts(namespace).getItems()) {
        Kubernetes.deleteServiceAccount(item.getMetadata().getName(), namespace);
      }
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup service accounts");
    }
    // Delete namespace
    try {
      Kubernetes.deleteNamespace(namespace);
    } catch (Exception ex) {
      logger.warning(ex.getMessage());
      logger.warning("Failed to cleanup namespace");
    }

  }

  private static void debug(String log) {
    if (DEBUG) {
      logger.info(log);
    }
  }

}