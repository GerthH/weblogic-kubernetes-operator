#!/bin/bash
# Copyright (c) 2019, 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

#
# This script deploys secrets for the Model in Image sample. 
#
# Optional parameter:
#
#   -dry                      - Dry run. Show but don't do. Dry run
#                               output is prefixed with 'dryrun:'.
#
# Optional environment variables:
#
#   WORKDIR                   - Working directory for the sample with at least
#                               10GB of space. Defaults to 
#                               '/tmp/$USER/model-in-image-sample-work-dir'.
#   DOMAIN_UID                - defaults to 'sample-domain1'
#   DOMAIN_NAMESPACE          - defaults to 'sample-domain1-ns'
#   WDT_DOMAIN_TYPE           - WLS (default), RestrictedJRF, or JRF
#   DB_NAMESPACE              - default (default)
#   INCLUDE_MODEL_CONFIGMAP   - 'true' if sample is deploying its
#                               configuration.model.configMap
#

set -eu
set -o pipefail

SCRIPTDIR="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
source $SCRIPTDIR/env-init.sh

DRY_RUN=""
if [ "${1:-}" = "-dry" ]; then
  # TBD add error checking for parameterization
  DRY_RUN="-dry $2"
fi

#
# WebLogic Credential Secret referenced by domain resource 
# field 'spec.weblogicCredentialsSecret'.
#

echo "@@ Info: Creating weblogic domain secret"
$MIISAMPLEDIR/utils/create-secret.sh $DRY_RUN -s ${DOMAIN_UID}-weblogic-credentials \
  -d $DOMAIN_UID -n $DOMAIN_NAMESPACE \
  -l username=weblogic \
  -l password=welcome1

#
# Model runtime encryption secret referenced by domain resource
# field 'spec.configuration.model.runtimeEncryptionSecret'.
# This secret can have any password but the password must remain
# the same throughout the life of a running model domain.
#

echo "@@ Info: Creating model runtime encryption secret"
$MIISAMPLEDIR/utils/create-secret.sh $DRY_RUN -s ${DOMAIN_UID}-runtime-encryption-secret \
  -d $DOMAIN_UID -n $DOMAIN_NAMESPACE \
  -l password=my_runtime_password

#
# JRF Domain's RCU secret and wallet password secret. Only needed for JRF
# domains.
#

if [ "$WDT_DOMAIN_TYPE" = "JRF" ]; then
  echo "@@ Info: Creating rcu access secret (referenced by model yaml macros if domain type is JRF)"
  $MIISAMPLEDIR/utils/create-secret.sh $DRY_RUN -s ${DOMAIN_UID}-rcu-access \
    -d $DOMAIN_UID -n $DOMAIN_NAMESPACE \
    -l rcu_prefix=FMW1 \
    -l rcu_schema_password=Oradoc_db1 \
    -l rcu_db_conn_string=oracle-db.${DB_NAMESPACE}.svc.cluster.local:1521/devpdb.k8s
  echo "@@ Info: Creating OPSS wallet password secret (ignored unless domain type is JRF)"
  $MIISAMPLEDIR/utils/create-secret.sh $DRY_RUN -s ${DOMAIN_UID}-opss-wallet-password-secret \
    -d $DOMAIN_UID -n $DOMAIN_NAMESPACE \
    -l walletPassword=welcome1
fi

#
# Datasource access secret. This is needed for the sample's optional
# 'configuration.model.configMap' because it contains a model file
# with an '@@SECRET' macro that references this datasource secret.
#

if [ "${INCLUDE_MODEL_CONFIGMAP}" = "true" ]; then
  # this secret is referenced by the datasource in this sample's optional config.configMap
  echo "@@ Info: Creating datasource secret"
  $MIISAMPLEDIR/utils/create-secret.sh $DRY_RUN \
    -d $DOMAIN_UID -n $DOMAIN_NAMESPACE \
    -n ${DOMAIN_NAMESPACE} \
    -s ${DOMAIN_UID}-datasource-secret \
    -l password=Oradoc_db1 \
    -l url=jdbc:oracle:thin:@oracle-db.${DB_NAMESPACE}.svc.cluster.local:1521/devpdb.k8s
fi