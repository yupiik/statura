#!/usr/bin/env bash
set -euo pipefail

DRY_RUN="${DRY_RUN:-true}"
VERBOSE="${VERBOSE:-false}"
ALVEOLUS="${ALVEOLUS:-all}"
MASTER_KEY="${MASTER_KEY:-notset}"
PLACEHOLDERS_SHARED="${PLACEHOLDERS_SHARED:-shared}"
PLACEHOLDERS_SPECIFIC="${PLACEHOLDERS_SPECIFIC:-dev}"
REGISTRY_KEY="${REGISTRY_KEY:-dev.local}"
REGISTRY_USERNAME="${REGISTRY_USERNAME:-none}"
REGISTRY_PASSWORD="${REGISTRY_PASSWORD:-none}"
GOAL="${GOAL:-bundlebee:apply@deploy}"

mvn -f pom.xml \
    -Dbundlebee.kube.dryRun="${DRY_RUN}" \
    -Dbundlebee.kube.verbose="${VERBOSE}" \
    -Dbundlebee.apply.alveolus="${ALVEOLUS}" \
    -Dcipher.masterKey="${MASTER_KEY}" \
    -Dshared.placeholder="${PLACEHOLDERS_SHARED}" \
    -Dspecific.placeholder="${PLACEHOLDERS_SPECIFIC}" \
    -Dregistry.key="${REGISTRY_KEY}" \
    -Dregistry.username="${REGISTRY_USERNAME}" \
    -Dregistry.password="${REGISTRY_PASSWORD}" \
    yupiik-tools:decrypt-properties@shared \
    yupiik-tools:decrypt-properties@specific \
    "${GOAL}"
