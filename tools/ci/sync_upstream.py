#!/usr/bin/env python3
"""Prepare an upstream sync branch; no build, push, merge-to-main, or release."""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys
from pathlib import Path
from urllib import request, error, parse

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / ".github" / "upstream-state.json"
UPSTREAM_REPO = "MiamBao/cardface-miuix"
TARGET_REPO = "Cokelce/cardface-miuix"
UPSTREAM_SHA_HINT = "c0833af5ff1392f84f431a9d0ae78892062aad6e"
APP_GRADLE = ROOT / "app" / "build.gradle.kts"
BUILD_INFO = ROOT / "app" / "src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt"


def sh(*args: str, check=True, capture=True) -> str:
    p = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.PIPE if capture else None)
    if check and p.returncode:
        raise RuntimeError(f"command failed: {' '.join(args)}\n{p.stderr or ''}")
    return (p.stdout or "").strip()


def git(*args: str, check=True) -> str:
    return sh("git", *args, check=check)


def read_state() -> dict:
    if not STATE.exists():
        return {"commit": UPSTREAM_SHA_HINT, "base_version": "2.0.0", "suffix": 5}
    return json.loads(STATE.read_text(encoding="utf-8"))


def write_state(commit: str, branch: str, version: str, suffix: int, base_version: str) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps({"commit": commit, "branch": branch, "version": version,
                                 "suffix": suffix, "base_version": base_version},
                                ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def api(method: str, path: str, payload=None):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY", TARGET_REPO)
    if not token:
        raise RuntimeError("GH_TOKEN/GITHUB_TOKEN is required for GitHub API commands")
    data = None if payload is None else json.dumps(payload).encode()
    req = request.Request("https://api.github.com/repos/" + (repo if path.startswith("/") else TARGET_REPO) + path,
                          data=data, method=method, headers={"Accept": "application/vnd.github+json",
                          "Authorization": "Bearer " + token, "User-Agent": "cardface-upstream-sync",
                          **({"Content-Type": "application/json"} if data else {})})
    try:
        with request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode() or "{}")
    except error.HTTPError as e:
        body = e.read().decode(errors="replace")
        raise RuntimeError(f"GitHub API {e.code}: {body[:500]}")


def version_code(text: str) -> int:
    m = re.search(r"versionCode\s*=\s*(\d+)", text)
    return int(m.group(1)) if m else 0


def highest_tag_code() -> int:
    best = 0
    for tag in git("tag", "--format=%(refname:short)").splitlines():
        text = git("show", f"{tag}:app/build.gradle.kts", check=False)
        best = max(best, version_code(text))
    return best


def parse_base_version(text: str) -> str:
    m = re.search(r'versionName\s*=\s*["\'](\d+\.\d+\.\d+)', text)
    if not m:
        raise RuntimeError("upstream app/build.gradle.kts has no X.Y.Z versionName")
    return m.group(1)


def next_version(base: str, old_base: str, old_suffix: int, current_code: int, tag_code: int):
    # Same upstream base means continuation; a new upstream base starts at .1.
    suffix = old_suffix + 1 if base == old_base else 1
    return f"{base}-oneplus.{suffix}", max(current_code, tag_code) + 1, suffix


def replace_once(path: Path, pattern: str, repl: str, flags=0):
    text = path.read_text(encoding="utf-8")
    new, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise RuntimeError(f"expected one config match in {path}: {pattern}")
    path.write_text(new, encoding="utf-8")


def apply_config(base: str, code: int, version: str):
    replace_once(APP_GRADLE, r'applicationId\s*=\s*["\'][^"\']+["\']', 'applicationId = "io.github.miambao.cardface.oneplus"')
    replace_once(APP_GRADLE, r'versionCode\s*=\s*\d+', f'versionCode = {code}')
    replace_once(APP_GRADLE, r'versionName\s*=\s*["\'][^"\']+["\']', f'versionName = "{version}"')
    replace_once(BUILD_INFO, r'INTERNAL_VERSION\s*=\s*["\'][^"\']+["\']', f'INTERNAL_VERSION = "{base}.{code}"')
    updates = ROOT / "app/src/main/kotlin/com/dsh/miuix/cardface/core/Updates.kt"
    if updates.exists():
        replace_once(updates, r'REPOSITORY\s*=\s*["\'][^"\']+["\']', 'REPOSITORY = "Cokelce/cardface-miuix"')
    scope = ROOT / "app/src/main/resources/META-INF/xposed/scope.list"
    scope.write_text("com.finshell.wallet\n", encoding="utf-8")


def create_issue(title: str, body: str):
    # Repeated scheduled failures update one issue instead of producing hourly spam.
    for item in api("GET", "/issues?state=open&per_page=100"):
        if "pull_request" not in item and item.get("title") == title:
            return api("PATCH", f"/issues/{item['number']}", {"body": body})
    return api("POST", "/issues", {"title": title, "body": body})


def emit(**values):
    lines = "".join(f"{key}={value}\n" for key, value in values.items())
    print(lines, end="")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as out:
            out.write(lines)


TRUSTED_PATHS = [".github", "tools/ci", "build.gradle.kts", "gradle.properties", "settings.gradle.kts", "gradlew", "gradle", ".gitignore"]
UPSTREAM_CONFIG = ["app/build.gradle.kts", "app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt"]


def managed_conflict(path):
    return path in UPSTREAM_CONFIG or any(path == entry or path.startswith(entry + "/") for entry in TRUSTED_PATHS)



def report_conflict(sha: str, files: list[str]):
    body = (f"上游同步 `{sha}` 在自动合并阶段发生冲突，脚本已 abort merge，保留目标工作树逻辑。\n\n"
            "受影响文件：\n" + "\n".join(f"- `{x}`" for x in files) +
            "\n\n请人工处理冲突后再运行构建验证；`upstream-state.json` 保持不变。")
    create_issue(f"upstream sync conflict: {sha[:12]}", body)


def prepare() -> int:
    state = read_state()
    git("remote", "get-url", "origin")
    if git("remote", "get-url", "upstream", check=False) == "":
        git("remote", "add", "upstream", "https://github.com/MiamBao/cardface-miuix.git")
    git("fetch", "origin", "main", "--tags")
    git("fetch", "upstream", "main", "--tags")
    sha = git("rev-parse", "upstream/main")
    old = state.get("commit", UPSTREAM_SHA_HINT)
    if sha == old:
        print(f"No upstream changes: {sha}")
        emit(changed="false")
        return 0
    ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", old, sha], cwd=ROOT)
    if ancestor.returncode != 0:
        raise RuntimeError(f"upstream is not a fast-forward from state commit {old}: {sha}")
    branch = f"automation/upstream-{sha[:12]}"
    existing = git("ls-remote", "--heads", "origin", f"refs/heads/{branch}")
    if existing:
        publish(branch, state.get("version", "upstream update"))
        emit(changed="false")
        print(f"Existing sync branch preserved: {branch}")
        return 0
    # The branch is deterministic and starts at the target's origin/main.
    git("switch", "-c", branch, "origin/main")
    merge = subprocess.run(["git", "merge", "--no-ff", "--no-commit", sha], cwd=ROOT, text=True, capture_output=True)
    if merge.returncode:
        files = git("diff", "--name-only", "--diff-filter=U", check=False).splitlines()
        unmanaged = [name for name in files if not managed_conflict(name)]
        if not files or unmanaged:
            git("merge", "--abort", check=False)
            report_conflict(sha, unmanaged or files)
            print(f"CONFLICT upstream={sha} files={','.join(unmanaged or files)}", file=sys.stderr)
            return 2
        for name in files:
            git("checkout", "--theirs" if name in UPSTREAM_CONFIG else "--ours", "--", name)
            git("add", "--", name)
    # Never execute or deploy an upstream change to our workflow, secrets handling or tools.
    git("restore", "--source=origin/main", "--staged", "--worktree", "--", *TRUSTED_PATHS)
    merged = git("show", f"{sha}:app/build.gradle.kts")
    base = parse_base_version(merged)
    if tuple(map(int, base.split("."))) < tuple(map(int, state["base_version"].split("."))):
        git("merge", "--abort", check=False)
        raise RuntimeError("upstream base version decreased; manual review required")
    current = version_code(git("show", "origin/main:app/build.gradle.kts"))
    new_version, code, suffix = next_version(base, state.get("base_version", ""),
                                               int(state.get("suffix", 0)), current, highest_tag_code())
    apply_config(base, code, new_version)
    check = subprocess.run([sys.executable, str(ROOT / "tools/ci/check_oneplus.py")], cwd=ROOT)
    if check.returncode:
        git("merge", "--abort", check=False)
        raise RuntimeError("check_oneplus.py failed; merge aborted and state unchanged")
    write_state(sha, branch, new_version, suffix, base)
    emit(changed="true", branch=branch, upstream_sha=sha, version=new_version)
    return 0


def publish(branch: str, version: str):
    repo = os.environ.get("GITHUB_REPOSITORY", TARGET_REPO)
    prs = api("GET", f"/pulls?state=open&head={repo.split('/')[0]}:{branch}&base=main")
    if prs:
        print(f"PR already exists: {prs[0].get('html_url', prs[0].get('number'))}")
        return 0
    p = api("POST", "/pulls", {"title": f"chore: sync upstream ({version})", "head": branch,
                                "base": "main", "body": f"Automated OnePlus adaptation `{version}`.\n\n- Upstream: `{read_state().get('commit', '')}`\n- Static checks, unit tests and signed APK build passed.\n- Test APK: {os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{repo}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}\n- Real OnePlus wallet rendering remains a device test; merge manually after review."})
    print(p.get("html_url", p.get("number")))
    return 0


def report(message: str):
    create_issue("upstream sync report", message)
    print("Issue created")
    return 0


def main():
    ap = argparse.ArgumentParser(description="CardFace upstream sync CLI")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("prepare", help="fetch, fast-forward-check, merge, bump, state, static-check (default)")
    p = sub.add_parser("publish", help="create a deduplicated PR after workflow pushes the branch")
    p.add_argument("--branch", required=True); p.add_argument("--version", required=True)
    r = sub.add_parser("report", help="create an issue containing a failure/change report")
    r.add_argument("message")
    args = ap.parse_args()
    if args.cmd in (None, "prepare"): return prepare()
    if args.cmd == "publish": return publish(args.branch, args.version)
    return report(args.message)

if __name__ == "__main__":
    try: sys.exit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr); sys.exit(1)
