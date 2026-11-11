#!/usr/bin/env bash
# Archive, sign, validate and upload the iOS "full" distribution of this fork to
# App Store Connect for INTERNAL TestFlight testing.
#
#   scripts/deploy-ios-testflight.sh [--build-number N] [--skip-upload] [--prebuilt-framework PATH|--from-github]
#
# --from-github downloads the release ComposeApp.framework that the
# "iOS Kotlin framework (release)" GitHub Actions workflow linked for the current commit,
# so this Mac only compiles Swift, signs and uploads (the Kotlin link needs >8 GB RAM).
#
# Credentials come from the environment (defaults point at the owner's existing
# App Store Connect API key on this Mac). The private key is read by Xcode/altool
# from ASC_KEY_PATH and is never copied into the repository or printed.
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"

# Key ID / Issuer ID / Team ID live outside the repo (default ~/.appstoreconnect/nuviocodeine.env).
ASC_ENV_FILE="${ASC_ENV_FILE:-${HOME}/.appstoreconnect/nuviocodeine.env}"
# shellcheck disable=SC1090
[[ -f "${ASC_ENV_FILE}" ]] && source "${ASC_ENV_FILE}"
: "${ASC_KEY_ID:?Set ASC_KEY_ID (or create ${ASC_ENV_FILE})}"
: "${ASC_ISSUER_ID:?Set ASC_ISSUER_ID (or create ${ASC_ENV_FILE})}"
ASC_KEY_PATH="${ASC_KEY_PATH:-${HOME}/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
TEAM_ID="${TEAM_ID:?Set TEAM_ID (or create ${ASC_ENV_FILE})}"
BUNDLE_ID="com.calebfeliciano.nuviocodeine"

build_number=""
skip_upload=0
prebuilt_framework=""
from_github=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --build-number) build_number="$2"; shift 2 ;;
        --skip-upload) skip_upload=1; shift ;;
        --prebuilt-framework) prebuilt_framework="$2"; shift 2 ;;
        --from-github) from_github=1; shift ;;
        *) echo "Usage: $0 [--build-number N] [--skip-upload] [--prebuilt-framework PATH|--from-github]" >&2; exit 2 ;;
    esac
done

[[ -f "${ASC_KEY_PATH}" ]] || { echo "App Store Connect key not found at ASC_KEY_PATH." >&2; exit 1; }

# Unique, monotonically increasing build number unless one is given.
if [[ -z "${build_number}" ]]; then
    build_number="$(date -u +%Y%m%d%H%M)"
fi
marketing_version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' iosApp/Configuration/Version.xcconfig | head -n1)"

# Kotlin/Native toolchain: JDK 17 and memory limits that fit an 8 GB Mac.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export NUVIO_IOS_DISTRIBUTION=full
export NUVIO_GRADLE_EXTRA_ARGS="${NUVIO_GRADLE_EXTRA_ARGS:--Dorg.gradle.jvmargs=-Xmx6500m -Pkotlin.daemon.jvmargs=-Xmx2g -Pkotlin.native.jvmArgs=-Xmx6500m}"

./scripts/prepare-ios-dependencies.sh

if [[ "${from_github}" == 1 ]]; then
    commit="$(git rev-parse HEAD)"
    git diff --quiet HEAD -- composeApp || { echo "composeApp has uncommitted changes; the GitHub framework would not match." >&2; exit 1; }
    download_dir="${repository_root}/build/prebuilt/${commit}"
    if [[ ! -d "${download_dir}/ComposeApp.framework" ]]; then
        github_repo="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
        run_id="$(gh run list -R "${github_repo}" --workflow ios-kotlin-framework.yml --commit "${commit}" --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
        [[ -n "${run_id}" ]] || { echo "No successful GitHub framework build for ${commit} yet." >&2; exit 1; }
        rm -rf "${download_dir}" && mkdir -p "${download_dir}"
        gh run download "${run_id}" -R "${github_repo}" --name "ComposeApp-iosArm64-release-${commit}" --dir "${download_dir}"
        ditto -x -k "${download_dir}/ComposeApp-iosArm64-release.zip" "${download_dir}"
    fi
    prebuilt_framework="${download_dir}/ComposeApp.framework"
fi
if [[ -n "${prebuilt_framework}" ]]; then
    [[ -d "${prebuilt_framework}" ]] || { echo "Prebuilt framework not found: ${prebuilt_framework}" >&2; exit 1; }
    export NUVIO_PREBUILT_KOTLIN_FRAMEWORK="$(cd "${prebuilt_framework}" && pwd -P)"
    echo "Using prebuilt Kotlin framework ${NUVIO_PREBUILT_KOTLIN_FRAMEWORK}"
fi

run_root="${repository_root}/build/testflight/${marketing_version}-${build_number}"
archive_path="${run_root}/Nuvio.xcarchive"
export_path="${run_root}/export"
mkdir -p "${run_root}"

auth=(-allowProvisioningUpdates
      -authenticationKeyPath "${ASC_KEY_PATH}"
      -authenticationKeyID "${ASC_KEY_ID}"
      -authenticationKeyIssuerID "${ASC_ISSUER_ID}")

xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "${archive_path}" \
    -derivedDataPath "${repository_root}/build/ios-derived-testflight" \
    "${auth[@]}" \
    DEVELOPMENT_TEAM="${TEAM_ID}" \
    CURRENT_PROJECT_VERSION="${build_number}" \
    archive 2>&1 | tee "${run_root}/archive.log" | grep -E "error:|warning: .*sign|ARCHIVE (SUCCEEDED|FAILED)" || true
[[ -d "${archive_path}" ]] || { echo "Archive failed; see ${run_root}/archive.log" >&2; exit 1; }

cat > "${run_root}/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>destination</key><string>export</string>
    <key>method</key><string>app-store-connect</string>
    <key>signingStyle</key><string>automatic</string>
    <key>teamID</key><string>${TEAM_ID}</string>
    <key>manageAppVersionAndBuildNumber</key><false/>
    <key>testFlightInternalTestingOnly</key><true/>
    <key>uploadSymbols</key><true/>
    <key>stripSwiftSymbols</key><true/>
</dict>
</plist>
PLIST

xcodebuild -exportArchive \
    -archivePath "${archive_path}" \
    -exportPath "${export_path}" \
    -exportOptionsPlist "${run_root}/ExportOptions.plist" \
    "${auth[@]}" 2>&1 | tee "${run_root}/export.log" | grep -E "error:|EXPORT (SUCCEEDED|FAILED)" || true
ipa_path="$(find "${export_path}" -maxdepth 1 -name '*.ipa' -print -quit)"
[[ -n "${ipa_path}" ]] || { echo "Export failed; see ${run_root}/export.log" >&2; exit 1; }

codesign --verify --deep --strict "${archive_path}/Products/Applications/Nuvio.app"
echo "Signed IPA: ${ipa_path} (${BUNDLE_ID} ${marketing_version} (${build_number}))"

xcrun altool --validate-app -f "${ipa_path}" -t ios \
    --api-key "${ASC_KEY_ID}" --api-issuer "${ASC_ISSUER_ID}" 2>&1 | tee "${run_root}/validate.log"

if [[ "${skip_upload}" == 1 ]]; then
    echo "Skipping upload (--skip-upload)."
    exit 0
fi

xcrun altool --upload-app -f "${ipa_path}" -t ios \
    --api-key "${ASC_KEY_ID}" --api-issuer "${ASC_ISSUER_ID}" 2>&1 | tee "${run_root}/upload.log"
grep -qiE "UPLOAD SUCCEEDED|No errors uploading" "${run_root}/upload.log" || { echo "Upload did not report success." >&2; exit 1; }
echo "Uploaded ${marketing_version} (${build_number}). Track processing with scripts/asc-testflight-status.py."
