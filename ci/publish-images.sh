#!/usr/bin/env bash
# Builds and pushes the three container images (application, Iceberg REST catalog, Hive
# Metastore), shared by GitLab CI and GitHub Actions. The caller has already run
# ci/build.sh and logged Docker in to the registry; this script only decides tags.
#
# Inputs (environment):
#   IMAGE_BASE  registry path of the application image, e.g. ghcr.io/dgreco/metacatalog
#               or $CI_REGISTRY_IMAGE. The other two images hang off it as
#               $IMAGE_BASE/iceberg-catalog and $IMAGE_BASE/hive-metastore.
#   GIT_BRANCH  branch being built. Images are pushed only from main, master and develop;
#               the version tag only from main and master, so a develop build never
#               overwrites a released version's tag.
#   GIT_SHA     short commit SHA, used as the immutable tag.
#   PLATFORMS   optional, e.g. linux/amd64,linux/arm64: build with buildx for each platform and
#               push one multi-arch image (the caller sets up the builder and the emulation).
#               Unset, the images are built for the runner's own architecture only. GitHub's
#               runner is amd64 and the images also run on arm64 clusters, hence GitHub sets it;
#               GitLab's runner is arm64 and leaves it unset.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${IMAGE_BASE:?IMAGE_BASE is required}"
: "${GIT_BRANCH:?GIT_BRANCH is required}"
: "${GIT_SHA:?GIT_SHA is required}"

case "$GIT_BRANCH" in
  main|master|develop) ;;
  *) echo "Branch '$GIT_BRANCH' does not publish images; nothing to do."; exit 0 ;;
esac

VERSION=""
if [ "$GIT_BRANCH" = "main" ] || [ "$GIT_BRANCH" = "master" ]; then
  # Ask Maven rather than grepping pom.xml: the first <version> in the file is the
  # Spring Boot parent's, and a grep that skips it silently breaks when the header moves.
  VERSION=$(mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout)
fi

# publish <module directory> <image name>
publish() {
  local module="$1" image="$2"
  mvn package -pl "$module" -B -DskipTests
  if [ -n "${PLATFORMS:-}" ]; then
    # A multi-platform image cannot be loaded into the local image store to be tagged and pushed
    # afterwards, as below, so buildx pushes every tag in the one build.
    local tags=(-t "${image}:${GIT_SHA}" -t "${image}:latest")
    if [ -n "$VERSION" ]; then
      tags+=(-t "${image}:${VERSION}")
    fi
    docker buildx build --platform "$PLATFORMS" "${tags[@]}" --push "$module/"
    return
  fi
  docker build -t "${image}:${GIT_SHA}" "$module/"
  docker push "${image}:${GIT_SHA}"
  docker tag "${image}:${GIT_SHA}" "${image}:latest"
  docker push "${image}:latest"
  if [ -n "$VERSION" ]; then
    docker tag "${image}:${GIT_SHA}" "${image}:${VERSION}"
    docker push "${image}:${VERSION}"
  fi
}

publish metacatalog-application    "${IMAGE_BASE}"
publish metacatalog-iceberg-catalog "${IMAGE_BASE}/iceberg-catalog"
publish metacatalog-hive-metastore  "${IMAGE_BASE}/hive-metastore"
