#!/usr/bin/env python3
"""Wait for an uploaded build to finish App Store Connect processing, then make it
available to an INTERNAL TestFlight group (creating "Internal - Owner" if needed).

    scripts/asc-testflight-status.py --build-number N [--no-wait] [--group NAME]

Uses the App Store Connect API key from ASC_KEY_ID / ASC_ISSUER_ID / ASC_KEY_PATH.
Never creates external groups, never submits for beta review, never invites anyone.
Requires the `cryptography` package (e.g. a venv: python3 -m venv .venv && .venv/bin/pip install cryptography).
"""
import argparse, base64, json, os, sys, time, urllib.error, urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

def _load_env_file():
    path = os.environ.get("ASC_ENV_FILE", os.path.expanduser("~/.appstoreconnect/nuviocodeine.env"))
    if os.path.exists(path):
        for line in open(path):
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.strip().split("=", 1)
                os.environ.setdefault(key, value)


_load_env_file()
KEY_ID = os.environ["ASC_KEY_ID"]
ISSUER = os.environ["ASC_ISSUER_ID"]
KEY_PATH = os.environ.get("ASC_KEY_PATH", os.path.expanduser(f"~/.appstoreconnect/private_keys/AuthKey_{KEY_ID}.p8"))
BUNDLE_ID = "com.calebfeliciano.nuviocodeine"


def _b64(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def _token() -> str:
    with open(KEY_PATH, "rb") as handle:
        key = serialization.load_pem_private_key(handle.read(), password=None)
    now = int(time.time())
    header = _b64(json.dumps({"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}).encode())
    payload = _b64(json.dumps({"iss": ISSUER, "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"}).encode())
    r, s = decode_dss_signature(key.sign(header + b"." + payload, ec.ECDSA(hashes.SHA256())))
    return (header + b"." + payload + b"." + _b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))).decode()


def api(method: str, path: str, body=None):
    request = urllib.request.Request(
        "https://api.appstoreconnect.apple.com" + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + _token(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read()
            return response.status, (json.loads(text) if text else {})
    except urllib.error.HTTPError as error:
        text = error.read()
        return error.code, (json.loads(text) if text else {})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-number", required=True)
    parser.add_argument("--group", default="Internal - Owner")
    parser.add_argument("--no-wait", action="store_true")
    parser.add_argument("--timeout-minutes", type=int, default=90)
    args = parser.parse_args()

    status, apps = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}")
    if status != 200 or not apps.get("data"):
        print(f"No App Store Connect app record for {BUNDLE_ID} yet (HTTP {status}).")
        return 2
    app_id = apps["data"][0]["id"]
    print(f"App: {apps['data'][0]['attributes']['name']} (id {app_id})")
    print(f"TestFlight: https://appstoreconnect.apple.com/apps/{app_id}/testflight/ios")

    deadline = time.time() + args.timeout_minutes * 60
    build = None
    while True:
        status, builds = api("GET", f"/v1/builds?filter[app]={app_id}&filter[version]={args.build_number}&include=buildBetaDetail")
        if builds.get("data"):
            build = builds["data"][0]
            state = build["attributes"]["processingState"]
            detail = next((i for i in builds.get("included", []) if i["type"] == "buildBetaDetails"), None)
            internal = detail["attributes"].get("internalBuildState") if detail else None
            print(f"Build {args.build_number}: processing={state} internal={internal}", flush=True)
            if state in ("VALID", "FAILED", "INVALID"):
                break
        else:
            print(f"Build {args.build_number}: not visible yet", flush=True)
        if args.no_wait or time.time() > deadline:
            return 3
        time.sleep(60)

    if build["attributes"]["processingState"] != "VALID":
        print("Processing did not succeed.")
        return 4

    # Export compliance: the app only uses standard, published algorithms (TLS/HTTPS,
    # OpenSSL in mpv, BitTorrent protocol); Info.plist declares ITSAppUsesNonExemptEncryption=NO.
    if build["attributes"].get("usesNonExemptEncryption") is None:
        api("PATCH", f"/v1/builds/{build['id']}", {"data": {"type": "builds", "id": build["id"], "attributes": {"usesNonExemptEncryption": False}}})

    status, groups = api("GET", f"/v1/apps/{app_id}/betaGroups?limit=50")
    group = next((g for g in groups.get("data", []) if g["attributes"]["name"] == args.group and g["attributes"].get("isInternalGroup")), None)
    if group is None:
        status, created = api("POST", "/v1/betaGroups", {"data": {
            "type": "betaGroups",
            "attributes": {"name": args.group, "isInternalGroup": True, "hasAccessToAllBuilds": False},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
        }})
        if status >= 300:
            print(f"Could not create internal group (HTTP {status}): {[e.get('detail') for e in created.get('errors', [])]}")
            return 5
        group = created["data"]
        print(f"Created internal group '{args.group}'.")
    status, result = api("POST", f"/v1/betaGroups/{group['id']}/relationships/builds", {"data": [{"type": "builds", "id": build["id"]}]})
    print(f"Added build to internal group '{args.group}': HTTP {status} {[e.get('detail') for e in result.get('errors', [])] if result else ''}")

    # Internal testers must be App Store Connect users. Add only the account holder
    # (the owner); nobody else is invited.
    _, testers = api("GET", f"/v1/betaGroups/{group['id']}/betaTesters?limit=50")
    if not testers.get("data"):
        _, users = api("GET", "/v1/users?limit=50")
        owner = next((u for u in users.get("data", []) if "ACCOUNT_HOLDER" in (u["attributes"].get("roles") or [])), None)
        if owner is None:
            print("Could not find the account holder to add as internal tester.")
        else:
            attrs = owner["attributes"]
            status, created = api("POST", "/v1/betaTesters", {"data": {
                "type": "betaTesters",
                "attributes": {"email": attrs["username"], "firstName": attrs.get("firstName"), "lastName": attrs.get("lastName")},
                "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": group["id"]}]}},
            }})
            print(f"Added account holder as internal tester: HTTP {status} {[e.get('detail') for e in created.get('errors', [])] if created else ''}")
        _, testers = api("GET", f"/v1/betaGroups/{group['id']}/betaTesters?limit=50")
    print(f"Internal testers in group: {len(testers.get('data', []))}")
    status, builds = api("GET", f"/v1/builds/{build['id']}?include=buildBetaDetail")
    detail = next((i for i in builds.get("included", []) if i["type"] == "buildBetaDetails"), {})
    print(f"Final internal TestFlight state: {detail.get('attributes', {}).get('internalBuildState')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
