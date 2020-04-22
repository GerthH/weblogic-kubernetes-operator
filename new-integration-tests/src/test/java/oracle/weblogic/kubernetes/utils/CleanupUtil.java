// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import io.kubernetes.client.openapi.models.ExtensionsV1beta1Ingress;
import io.kubernetes.client.openapi.models.NetworkingV1beta1Ingress;
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
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import oracle.weblogic.domain.Domain;

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
   * Cleanup all artifacts in the Kubernetes cluster. Waits for the deletion to be completed until up to 3 minutes.
   *
   * @param namespaces list of namespaces
   */
  public static void cleanup(List<String> namespaces) {

    // Delete all the artifacts in the list of namespaces
    deleteArtifacts(namespaces);

    // wait for the artifacts to be deleted waiting for a maximum of 3 minutes
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
          .until(CleanupUtil.artifactsDoesntExist(namespace));
    });
  }

  /**
   * Checks if the artifacts in given namespace still exists.
   *
   * @param namespace name of the namespace
   * @return true if none of the artifacts exists, false otherwise
   */
  public static Callable<Boolean> artifactsDoesntExist(String namespace) {
    return () -> {
      boolean doesntExist = true;

      // Check if domain exists
      try {
        if (!Kubernetes.listDomains(namespace).getItems().isEmpty()) {
          List<Domain> items = Kubernetes.listDomains(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Domain still exists");
          doesntExist = false;
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
          List<V1ReplicaSet> items = Kubernetes.listReplicaSets(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("ReplicaSets still exists");
          doesntExist = false;
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
          List<V1Job> items = Kubernetes.listJobs(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Jobs still exists");
          doesntExist = false;
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
          List<V1ConfigMap> items = Kubernetes.listConfigMaps(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Config Maps still exists");
          doesntExist = false;
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
          List<V1Secret> items = Kubernetes.listSecrets(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Secrets still exists");
          doesntExist = false;
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
                String.format("weblogic.domainUID in (%s)", label))
                .getItems().isEmpty()) {
              List<V1PersistentVolume> items = Kubernetes.listPersistentVolumes(
                  String.format("weblogic.domainUID in (%s)", label))
                  .getItems();
              items.forEach((item1) -> {
                debug(item1.getMetadata().getName());
              });
              logger.info("Persistent Volumes still exists");
              doesntExist = false;
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
          List<V1Deployment> items = Kubernetes.listDeployments(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Deployments still exists");
          doesntExist = false;
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
          List<V1PersistentVolumeClaim> items = Kubernetes.listPersistentVolumeClaims(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Persistent Volumes Claims still exists");
          doesntExist = false;
        } else {
          logger.info("Persistent Volume Claims list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list persistent volume claims");
      }

      // check if service accounts exist
      try {
        if (!Kubernetes.listServiceAccounts(namespace).getItems().isEmpty()) {
          List<V1ServiceAccount> items = Kubernetes.listServiceAccounts(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Service Accounts still exists");
          doesntExist = false;
        } else {
          logger.info("Service Account list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list service accounts");
      }

      // check if ingress exist
      try {
        if (!Kubernetes.listIngress(namespace).getItems().isEmpty()) {
          List<NetworkingV1beta1Ingress> items = Kubernetes.listIngress(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Ingress service still exists");
          doesntExist = false;
        } else {
          logger.info("Ingress list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list Ingress");
      }

      // check if ingress extensions exist
      try {
        if (!Kubernetes.listIngressExtensions(namespace).getItems().isEmpty()) {
          List<ExtensionsV1beta1Ingress> items = Kubernetes.listIngressExtensions(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Ingress extensions still exists");
          doesntExist = false;
        } else {
          logger.info("Ingress extension list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list IngressExtensions");
      }

      // check if namespaced roles exist
      try {
        if (!Kubernetes.listNamespacedRole(namespace).getItems().isEmpty()) {
          List<V1Role> items = Kubernetes.listNamespacedRole(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Namespaced roles still exists");
          doesntExist = false;
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
          List<V1RoleBinding> items = Kubernetes.listNamespacedRoleBinding(namespace).getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Namespaced role bindings still exists");
          doesntExist = false;
        } else {
          logger.info("Namespaced role bindings list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list namespaced role bindings");
      }

      // check if cluster roles exist
      try {
        if (!Kubernetes.listClusterRoles().getItems().isEmpty()) {
          List<V1ClusterRole> items = Kubernetes.listClusterRoles().getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Cluster Roles still exists");
          doesntExist = false;
        } else {
          logger.info("Cluster Roles list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list cluster roles");
      }

      // check if cluster rolebindings exist
      try {
        if (!Kubernetes.listClusterRoleBindings().getItems().isEmpty()) {
          List<V1RoleBinding> items = Kubernetes.listClusterRoleBindings().getItems();
          items.forEach((item) -> {
            debug(item.getMetadata().getName());
          });
          logger.info("Cluster RoleBings still exists");
          doesntExist = false;
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
          List<String> items = Kubernetes.listNamespaces();
          items.forEach((item) -> {
            debug(item);
          });
          logger.info("Namespace still exists");
          doesntExist = false;
        } else {
          logger.info("Namespace list is empty");
        }
      } catch (Exception ex) {
        logger.warning(ex.getMessage());
        logger.warning("Failed to list namespaces");
      }

      return doesntExist;
    };
  }

  /**
   * Deletes the artifacts in the Kubernetes cluster in the namespaces list.
   *
   * @param namespaces list of namespaces
   */
  public static void deleteArtifacts(List<String> namespaces) {
    for (String namespace : namespaces) {
      logger.info("Cleaning up artifacts in namespace {0}", namespace);

      // Delete all Domain objects in given namespace
      try {
        for (var item : Kubernetes.listDomains(namespace).getItems()) {
          Kubernetes.deleteDomainCustomResource(
              item.getMetadata().getLabels().get("weblogic.domainUID"),
              namespace
          );
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
            for (var listPersistentVolume : Kubernetes
                .listPersistentVolumes(String.format("weblogic.domainUID in (%s)", label))
                .getItems()) {
              Kubernetes.deletePv(listPersistentVolume.getMetadata().getName());
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
        debug("");
      }
    }
  }

  private static void debug(String log, Object... params) {
    if (DEBUG) {
      String message = "DEBUG\n" + log + "\n";
      logger.info(message, params);
    }
  }

}