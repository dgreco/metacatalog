#!/usr/bin/env bash
# Builds and pushes the three container images (application, Iceberg REST catalog, Hive
# Metastore), shared by GitLab CI and GitHub Actions. The caller has already run
# ci/build.sh and logged Docker in to the registry; this script only decides tags.
#
# What gets published (see README § Releases and Versioning):
#   tag vX.Y.Z          X.Y.Z, X.Y and the SHA; plus `latest` when it is the newest release.
#                       X.Y moves only to the newest patch of its own line, so a late patch to
#                       an older line never drags `latest` or a newer line's tag back.
#   main / master       the SHA and `edge`: the tip of development, never a version tag.
#   develop             the SHA only.
#   anything else       nothing — release/* branches included; a release is published by its tag.
#
# Inputs (environment):
#   IMAGE_BASE  registry path of the application image, e.g. ghcr.io/dgreco/metacatalog
#               or $CI_REGISTRY_IMAGE. The other two images hang off it as
#               $IMAGE_BASE/iceberg-catalog and $IMAGE_BASE/hive-metastore.
#   GIT_SHA     short commit SHA, used as the immutable tag.
#   GIT_TAG     the tag being built, on a tag pipeline; empty otherwise.
#   GIT_BRANCH  the branch being built, on a branch pipeline; empty otherwise (merge requests).
#   PLATFORMS   optional, e.g. linux/amd64,linux/arm64: build with buildx for each platform and
#               push one multi-arch image (the caller sets up the builder and the emulation).
#               Unset, the images are built for the runner's own architecture only. GitHub's
#               runner is amd64 and the images also run on arm64 clusters, hence GitHub sets it;
#               GitLab's runner is arm64 and leaves it unset.
#   DRY_RUN     optional; 1 prints the image tags it would push and builds nothing.
#   RELEASE_TAGS optional; the release tags to compare against, one per line. Defaults to the
#               v* tags of RELEASE_REMOTE, since a shallow CI checkout does not have them locally.
#   RELEASE_REMOTE optional; the remote to list them from, `origin` unless set. GitLab passes its
#               job-token URL, which can read the repository whatever the clone left behind.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${IMAGE_BASE:?IMAGE_BASE is required}"
: "${GIT_SHA:?GIT_SHA is required}"
GIT_TAG="${GIT_TAG:-}"
GIT_BRANCH="${GIT_BRANCH:-}"

SEMVER='^v([0-9]+)\.([0-9]+)\.([0-9]+)$'

# Ask Maven rather than grepping pom.xml: the first <version> in the file is the Spring Boot
# parent's, and a grep that skips it silently breaks when the header moves.
pom_version() {
  mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout
}

release_tags() {
  if [ -n "${RELEASE_TAGS:-}" ]; then
    printf '%s\n' "$RELEASE_TAGS"
  else
    git ls-remote --tags --refs "${RELEASE_REMOTE:-origin}" 'v*' | sed 's|.*refs/tags/||'
  fi | grep -E "$SEMVER" | sort -V
}

TAGS=("$GIT_SHA")
if [ -n "$GIT_TAG" ]; then
  if ! [[ "$GIT_TAG" =~ $SEMVER ]]; then
    echo "Tag '$GIT_TAG' is not a release tag (vX.Y.Z); nothing to do."
    exit 0
  fi
  version="${GIT_TAG#v}"
  line="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
  # A tag on the wrong commit would publish one version's code under another's number.
  pom="$(pom_version)"
  if [ "$pom" != "$version" ]; then
    echo "Tag $GIT_TAG does not match the POM version $pom; refusing to publish." >&2
    exit 1
  fi
  known="$(release_tags; echo "$GIT_TAG")"
  TAGS+=("$version")
  if [ "$(grep -E "^v${line//./\\.}\." <<<"$known" | sort -V | tail -1)" = "$GIT_TAG" ]; then
    TAGS+=("$line")
  fi
  if [ "$(sort -V <<<"$known" | tail -1)" = "$GIT_TAG" ]; then
    TAGS+=(latest)
  fi
else
  case "$GIT_BRANCH" in
    main|master) TAGS+=(edge) ;;
    develop) ;;
    *)
      echo "Branch '${GIT_BRANCH:-<none>}' does not publish images; nothing to do."
      exit 0
      ;;
  esac
fi

# publish <module directory> <image name>
publish() {
  local module="$1" image="$2" tag
  if [ "${DRY_RUN:-}" = 1 ]; then
    for tag in "${TAGS[@]}"; do echo "would push ${image}:${tag}"; done
    return
  fi
  mvn package -pl "$module" -B -DskipTests
  if [ -n "${PLATFORMS:-}" ]; then
    # A multi-platform image cannot be loaded into the local image store to be tagged and pushed
    # afterwards, as below, so buildx pushes every tag in the one build.
    local args=()
    for tag in "${TAGS[@]}"; do args+=(-t "${image}:${tag}"); done
    docker buildx build --platform "$PLATFORMS" "${args[@]}" --push "$module/"
    return
  fi
  docker build -t "${image}:${GIT_SHA}" "$module/"
  for tag in "${TAGS[@]}"; do
    [ "$tag" = "$GIT_SHA" ] || docker tag "${image}:${GIT_SHA}" "${image}:${tag}"
    docker push "${image}:${tag}"
  done
}

publish metacatalog-application    "${IMAGE_BASE}"
publish metacatalog-iceberg-catalog "${IMAGE_BASE}/iceberg-catalog"
publish metacatalog-hive-metastore  "${IMAGE_BASE}/hive-metastore"
