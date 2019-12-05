#!/bin/bash
# Copyright (c) 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

export DOMAIN_HOME=${DOMAIN_HOME_DIR}

# Create the domain
wlst.sh -skipWLSModuleScanning \
        ${CREATE_DOMAIN_SCRIPT_DIR}/createFMWDomain.py \
        -oh /u01/oracle \
        -jh /usr/java/latest \
        -parent ${DOMAIN_HOME}/.. \
        -name ${CUSTOM_DOMAIN_NAME} \
        -user `cat /weblogic-operator/secrets/username` \
        -password `cat /weblogic-operator/secrets/password` \
        -rcuDb ${CUSTOM_CONNECTION_STRING} \
        -rcuPrefix ${CUSTOM_RCUPREFIX} \
        -rcuSchemaPwd `cat /weblogic-operator/rcu-secrets/password` \
        -adminServerPort ${CUSTOM_ADMIN_SERVER_PORT} \
        -adminServerSSLPort ${CUSTOM_ADMIN_SERVER_SSL_PORT} \
        -adminName ${CUSTOM_ADMIN_NAME} \
        -managedNameBase ${CUSTOM_MANAGED_BASE_NAME} \
        -managedServerPort ${CUSTOM_MANAGEDSERVER_PORT} \
        -managedServerSSLPort ${CUSTOM_MANAGEDSERVER_SSL_PORT} \
        -useKSSForDemo ${USE_KSS_FOR_DEMO} \
        -sslEnabled ${SSL_ENABLED} \
        -prodMode ${CUSTOM_PRODUCTION_MODE} \
        -managedServerCount ${CUSTOM_MANAGED_SERVER_COUNT} \
        -clusterName ${CUSTOM_CLUSTER_NAME} \
        -exposeAdminT3Channel ${EXPOSE_T3_CHANNEL_PREFIX} \
        -t3ChannelPublicAddress ${T3_PUBLIC_ADDRESS} \
        -t3ChannelPort ${T3_CHANNEL_PORT}