#!/usr/bin/env python3
"""Import latest upstream source plus the latest owner-maintained OnePlus and OPPO wallet adaptation, without git merge."""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys, tempfile
import publish_release as official_release
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
                                 "suffix": suffix, "base_version": base_version}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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
    suffix = old_suffix + 1 if base == old_base else 1
    return f"{base}-oneplus.{suffix}", max(current_code, tag_code) + 1, suffix


def replace_once(path: Path, pattern: str, repl: str, flags=0):
    text = path.read_text(encoding="utf-8")
    new, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise RuntimeError(f"expected one config match in {path}: {pattern}")
    path.write_text(new, encoding="utf-8")


def apply_config(base: str, code: int, version: str):
    replace_once(APP_GRADLE, r'applicationId\s*=\s*["\'][^"\']+["\']', 'applicationId = "io.github.cardface.one"')
    replace_once(APP_GRADLE, r'versionCode\s*=\s*\d+', f'versionCode = {code}')
    replace_once(APP_GRADLE, r'versionName\s*=\s*["\'][^"\']+["\']', f'versionName = "{version}"')
    replace_once(BUILD_INFO, r'INTERNAL_VERSION\s*=\s*["\'][^"\']+["\']', f'INTERNAL_VERSION = "{base}.{code}"')
    updates = ROOT / "app/src/main/kotlin/com/dsh/miuix/cardface/core/Updates.kt"
    if updates.exists():
        replace_once(updates, r'REPOSITORY\s*=\s*["\'][^"\']+["\']', 'REPOSITORY = "Cokelce/cardface-miuix"')
    (ROOT / "app/src/main/resources/META-INF/xposed/scope.list").write_text("com.finshell.wallet\n", encoding="utf-8")


def create_issue(title: str, body: str):
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


TRUSTED_PATHS = ["README.md", ".github", "tools/ci", "build.gradle.kts", "gradle.properties", "settings.gradle.kts", "gradlew", "gradle", ".gitignore"]
UPSTREAM_CONFIG = ["app/build.gradle.kts", "app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt"]



def managed_conflict(path: str) -> bool:
    return path in UPSTREAM_CONFIG or any(path == entry or path.startswith(entry + "/") for entry in TRUSTED_PATHS)

def report_conflict(sha: str, files: list[str]):
    body = (f"上游同步 `{sha}` 应用OnePlus 和 OPPO 钱包适配补丁时发生冲突，已回退未提交的源码导入，保留你仓库当前代码。\n\n"
            "受影响文件：\n" + "\n".join(f"- `{x}`" for x in files) +
            "\n\n请人工处理冲突后再运行构建验证；`upstream-state.json` 保持不变。")
    create_issue(f"upstream sync conflict: {sha[:12]}", body)


def record_release_notes(version, old, sha):
    path = ROOT / ".github/release-notes.json"
    entries = json.loads(path.read_text()) if path.exists() else {}
    if version in entries:
        return
    subjects = git("log", "--reverse", "--format=%s", f"{old}..{sha}").splitlines()
    changes = []
    for subject in subjects:
        if re.match(r"^(docs|ci|build|test|chore)(?:\([^)]*\))?[!:]", subject, re.I):
            continue
        clean = re.sub(r"^(feat|fix|perf|refactor|release)(?:\([^)]*\))?!?:\s*", "", subject, flags=re.I).strip()
        clean = re.sub(r"^v?\d+\.\d+\.\d+\s*[:：-]?\s*", "", clean)
        if clean and clean not in changes:
            changes.append(clean)
    if not changes:
        changed = git("diff", "--name-only", old, sha).splitlines()
        changes = ["更新项目说明与维护内容。" if all(x.endswith('.md') or x.startswith('docs/') for x in changed) else "同步上游源码更新。"]
    entries[version] = {"upstream_changes": changes[:12], "adaptation_changes": [
        "同步本次上游变更，保留一加钱包的卡片识别、卡面替换和图片同步适配。",
        "保留模块四入口布局、关于页一加说明和本仓库更新源。"]}
    path.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n")


def sync_readme_from_upstream(sha: str) -> None:
    readme = git("show", f"{sha}:README.md")
    readme = readme.replace("给小米智能卡换一张喜欢的卡面", "给小米智能卡、一加钱包换一张喜欢的卡面", 1)
    readme = readme.replace("「卡面工坊」是一个给小米智能卡换卡面的 LSPosed 模块。", "「卡面工坊」是一个给小米智能卡、一加钱包换卡面的 LSPosed 模块。", 1)
    readme = re.sub(r'(?m)^  <a href="https://github\.com/Xposed-Modules-Repo/io\.github\.miambao\.cardface"><img[^\n]*\n', "", readme)
    readme = readme.replace("- 卡包：小米智能卡（`com.miui.tsmclient`）\n", "- 卡包：小米智能卡（`com.miui.tsmclient`）、一加钱包（`com.finshell.wallet`）；本分支维护一加钱包适配。\n", 1)
    readme = readme.replace("2. 在 LSPosed 里启用「卡面工坊」，作用域勾选：小米智能卡。", "2. 在 LSPosed 里启用「卡面工坊」，作用域勾选：小米智能卡、一加钱包；本分支重点适配一加钱包（`com.finshell.wallet`）。", 1)
    readme = re.sub(r'(?m)^- GitHub Releases：<https://github\.com/MiamBao/cardface-miuix/releases/latest>\n', "- GitHub Releases：<https://github.com/Cokelce/cardface-miuix/releases/latest>\n", readme)
    readme = re.sub(r'(?m)^- LSPosed 模块仓库：<https://github\.com/Xposed-Modules-Repo/io\.github\.miambao\.cardface>\n?', "", readme)
    prefix = "> 本分支为 **OnePlus 和 OPPO 钱包适配版**，自动同步上游最新源码并叠加本仓库 main 的OnePlus 和 OPPO 钱包适配；在线卡面页面与功能沿用上游，编辑页保留「从相册选、粘贴网址、从图库选、在线卡面」四入口。\n>\n"
    if not readme.startswith("> 本分支为 **OnePlus 和 OPPO 钱包适配版**"):
        readme = prefix + readme
    (ROOT / "README.md").write_text(readme, encoding="utf-8")


def import_snapshot(old, sha):
    folder = ROOT / '.ci-output'
    folder.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='snapshot-index-', dir=folder) as temporary:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / 'index'))
        def index_git(*args, data=None):
            return subprocess.run(['git', *args], cwd=ROOT, env=env, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
        index_git('read-tree', 'origin/main')
        for name in UPSTREAM_CONFIG:
            baseline = git('show', f'{old}:{name}')
            current = git('show', f'origin/main:{name}')
            patterns = ([r'applicationId\s*=\s*"[^"\n]+"', r'versionCode\s*=\s*\d+', r'versionName\s*=\s*"[^"\n]+"'] if name == 'app/build.gradle.kts' else [r'INTERNAL_VERSION\s*=\s*"[^"\n]+"'])
            for pattern in patterns:
                match = re.search(pattern, baseline)
                if match:
                    current = re.sub(pattern, lambda _: match.group(0), current, count=1)
            blob = index_git('hash-object', '-w', '--stdin', data=(current + '\n').encode()).decode().strip()
            mode = git('ls-tree', 'origin/main', '--', name).split()[0]
            index_git('update-index', '--add', '--cacheinfo', f'{mode},{blob},{name}')
        patch = index_git('diff', '--cached', '--binary', old, '--', '.', *[':(exclude)' + entry for entry in TRUSTED_PATHS])
    git('read-tree', '--reset', '-u', sha)
    git('restore', '--source=origin/main', '--staged', '--worktree', '--', *TRUSTED_PATHS)
    if patch:
        applied = subprocess.run(['git', 'apply', '--3way', '--index', '-'], input=patch, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if applied.returncode:
            files = git('diff', '--name-only', '--diff-filter=U', check=False).splitlines()
            git('read-tree', '--reset', '-u', 'origin/main')
            report_conflict(sha, files or ['adaptation patch did not apply cleanly'])
            return False
    return True


def prepare() -> int:
    state = read_state()
    git('remote', 'get-url', 'origin')
    if git('remote', 'get-url', 'upstream', check=False) == '':
        git('remote', 'add', 'upstream', 'https://github.com/MiamBao/cardface-miuix.git')
    git('fetch', 'origin', 'main', '--tags')
    git('fetch', 'upstream', 'main', '--tags')
    sha = git('rev-parse', 'upstream/main')
    old = state.get('commit', UPSTREAM_SHA_HINT)
    if sha == old:
        emit(changed='false'); return 0
    if subprocess.run(['git', 'merge-base', '--is-ancestor', old, sha], cwd=ROOT).returncode != 0:
        raise RuntimeError(f'upstream is not a fast-forward from state commit {old}: {sha}')
    branch = f'automation/upstream-{sha[:12]}'
    existing = git('ls-remote', '--heads', 'origin', f'refs/heads/{branch}')
    if existing:
        git('fetch', 'origin', f'refs/heads/{branch}:refs/remotes/origin/{branch}')
        branch_state = json.loads(git('show', f'origin/{branch}:.github/upstream-state.json'))
        branch_version = branch_state.get('version', '')
        git('switch', '-C', branch, f'origin/{branch}')
        if not import_snapshot(old, sha): return 2
        sync_readme_from_upstream(sha)
        existing_code = version_code(git('show', f'origin/{branch}:app/build.gradle.kts'))
        apply_config(branch_state['base_version'], max(existing_code, version_code(git('show', 'origin/main:app/build.gradle.kts')) + 1), branch_version)
        STATE.write_text(json.dumps(branch_state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        record_release_notes(branch_version, old, sha)
        if subprocess.run([sys.executable, str(ROOT / 'tools/ci/check_oneplus.py')], cwd=ROOT).returncode:
            raise RuntimeError('fixed OnePlus checks failed')
        emit(changed='true', existing='true', branch=branch, upstream_sha=sha, version=branch_version)
        return 0
    git('switch', '-c', branch, 'origin/main')
    if not import_snapshot(old, sha): return 2
    sync_readme_from_upstream(sha)
    base = parse_base_version(git('show', f'{sha}:app/build.gradle.kts'))
    current = version_code(git('show', 'origin/main:app/build.gradle.kts'))
    tag_suffixes = [int(match[1]) for tag in git('tag', '--format=%(refname:short)').splitlines() if (match := re.fullmatch(r'v?' + re.escape(base) + r'-oneplus\.(\d+)', tag))]
    old_suffix = max([0] + tag_suffixes)
    new_version, code, suffix = next_version(base, base if old_suffix else '', old_suffix, current, highest_tag_code())
    apply_config(base, code, new_version)
    record_release_notes(new_version, old, sha)
    if subprocess.run([sys.executable, str(ROOT / 'tools/ci/check_oneplus.py')], cwd=ROOT).returncode:
        git('read-tree', '--reset', '-u', 'origin/main')
        raise RuntimeError('check_oneplus.py failed; snapshot import rolled back')
    write_state(sha, branch, new_version, suffix, base)
    emit(changed='true', branch=branch, upstream_sha=sha, version=new_version)
    return 0


def publish(branch: str, version: str):
    print('Upstream sync now publishes directly through publish_release.py; PR creation is not used.')
    return 0


def report(message: str):
    create_issue('upstream sync report', message)
    print('Issue created')
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd')
    sub.add_parser('prepare')
    p = sub.add_parser('publish'); p.add_argument('--branch', required=True); p.add_argument('--version', required=True)
    r = sub.add_parser('report'); r.add_argument('message')
    args = ap.parse_args()
    if args.cmd in (None, 'prepare'): return prepare()
    if args.cmd == 'publish': return publish(args.branch, args.version)
    return report(args.message)

if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as exc:
        print(f'ERROR: {exc}', file=sys.stderr); sys.exit(1)
