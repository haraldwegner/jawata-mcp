#!/usr/bin/env bash
# Builds the Eclipse update site that jawata.org serves at /eclipse/ from the releases of haraldwegner/jawata-eclipse.
#
#   assemble-eclipse-site.sh list                 prints the release tags that belong on the site, oldest first, one per line
#   assemble-eclipse-site.sh assemble <site-dir>  downloads each of them and writes <site-dir>/eclipse/
#
# What the site is. Every release of jawata-eclipse carries one asset, jawata-eclipse-update-site-<version>.zip: a complete p2 repository
# (content.jar, artifacts.jar, features/, plugins/) for that version. This script unpacks each into <site-dir>/eclipse/<version>/ and writes a p2
# COMPOSITE repository at <site-dir>/eclipse/ (compositeContent.xml, compositeArtifacts.xml, p2.index) that lists the versions as children.
# Eclipse is given https://jawata.org/eclipse/ ONCE (Help > Install New Software) and sees every listed version at that address; Check for
# Updates finds a newer one the next time this runs after a new release. Older versions stay listed (the newest KEEP releases, default 8,
# because every version is about 55 MB and GitHub Pages holds at most 1 GB), so a person can also go back.
#
# Each zip is checked against the sha256 digest GitHub publishes for the asset before it is used (the same check as jawata-eclipse's own
# scripts/fetch-engine.sh makes for the engine). Only releases that are published, not drafts and not pre-releases, with a tag of the form
# vMAJOR.MINOR.MICRO, count. A release whose zip is missing or fails its digest FAILS this script: a site that silently lacks a version a person
# was told about is worse than a deploy that stops. releases.txt, written next to the composite files, is the list this run used; the workflow
# compares it with the live one to skip a deploy when nothing changed.
#
# Needs: gh (logged in through GH_TOKEN; the repository is public), jq, unzip, sha256sum. Environment: ECLIPSE_REPO, KEEP.
set -euo pipefail

repo="${ECLIPSE_REPO:-haraldwegner/jawata-eclipse}"
keep="${KEEP:-8}"

die() { echo "assemble-eclipse-site: $*" >&2; exit 1; }

list_tags() {
    gh release list -R "$repo" --exclude-drafts --exclude-pre-releases --limit 200 --json tagName --jq '.[].tagName' \
        | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } | sort -V | tail -n "$keep"
}

composite() {  # $1 = file, $2 = processing instruction name, $3 = p2 repository class, $4 = stamp, rest = tags
    local file="$1" pi="$2" class="$3" stamp="$4"; shift 4
    {
        echo "<?xml version='1.0' encoding='UTF-8'?>"
        echo "<?${pi} version='1.0.0'?>"
        echo "<repository name='jawata for Eclipse' type='${class}' version='1.0.0'>"
        echo "  <properties size='2'>"
        echo "    <property name='p2.compressed' value='false'/>"
        echo "    <property name='p2.timestamp' value='${stamp}'/>"
        echo "  </properties>"
        echo "  <children size='$#'>"
        for tag in "$@"; do echo "    <child location='${tag#v}'/>"; done
        echo "  </children>"
        echo "</repository>"
    } > "$file"
}

assemble() {
    local site="$1" out tags tag ver asset want have stamp
    out="$site/eclipse"
    tags="$(list_tags)"
    if [ -z "$tags" ]; then
        echo "assemble-eclipse-site: $repo has no published release vX.Y.Z; nothing is served under /eclipse/"
        return 0
    fi
    rm -rf "$out"; mkdir -p "$out"
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    for tag in $tags; do
        ver="${tag#v}"
        asset="jawata-eclipse-update-site-${ver}.zip"
        want="$(gh api "repos/$repo/releases/tags/$tag" | jq -r --arg a "$asset" '.assets[] | select(.name == $a) | .digest // empty | sub("^sha256:"; "")')"
        [ -n "$want" ] || die "release $tag has no asset $asset with a published sha256 digest"
        gh release download "$tag" -R "$repo" -p "$asset" -D "$tmp" --clobber
        have="$(sha256sum "$tmp/$asset" | cut -d' ' -f1)"
        [ "$have" = "$want" ] || die "$asset: sha256 $have does not match GitHub's digest $want"
        mkdir -p "$out/$ver"
        unzip -q "$tmp/$asset" -d "$out/$ver"
        if [ ! -f "$out/$ver/content.jar" ] && [ ! -f "$out/$ver/content.xml.xz" ]; then
            die "$asset does not unpack to a p2 repository (no content.jar or content.xml.xz at its top)"
        fi
        rm -f "$tmp/$asset"
        echo "  $tag: $(du -sh "$out/$ver" | cut -f1)"
    done

    stamp="$(date +%s)000"
    # shellcheck disable=SC2086  # the tags are words by construction (vX.Y.Z)
    composite "$out/compositeContent.xml" compositeMetadataRepository \
        org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository "$stamp" $tags
    # shellcheck disable=SC2086
    composite "$out/compositeArtifacts.xml" compositeArtifactRepository \
        org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository "$stamp" $tags
    printf 'version=1\nmetadata.repository.factory.order=compositeContent.xml,\\!\nartifact.repository.factory.order=compositeArtifacts.xml,\\!\n' > "$out/p2.index"
    echo "$tags" > "$out/releases.txt"
    echo "assemble-eclipse-site: $(echo "$tags" | wc -l | tr -d ' ') version(s) under $out: $(echo "$tags" | tr '\n' ' ')"
}

case "${1:-}" in
    list) list_tags ;;
    assemble) [ -n "${2:-}" ] || die "usage: assemble <site-dir>"; assemble "$2" ;;
    *) die "usage: $0 list | assemble <site-dir>" ;;
esac
