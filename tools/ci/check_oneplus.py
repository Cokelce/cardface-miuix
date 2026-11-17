#!/usr/bin/env python3
"""Static invariants for the OnePlus fork; intentionally does not build."""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[2]
errors = []

def text(rel):
    p = ROOT / rel
    if not p.exists():
        errors.append(f"missing: {rel}")
        return ""
    return p.read_text(encoding="utf-8")

gradle = text("app/build.gradle.kts")
manifest = text("app/src/main/AndroidManifest.xml")
updates = text("app/src/main/kotlin/com/dsh/miuix/cardface/core/Updates.kt")
info = text("app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt")
scope = text("app/src/main/resources/META-INF/xposed/scope.list")
editor = text("app/src/main/kotlin/com/dsh/miuix/cardface/ui/EditorScreen.kt")
about = text("app/src/main/kotlin/com/dsh/miuix/cardface/ui/InfoScreens.kt")
all_kotlin = "\n".join(str(p.relative_to(ROOT)) + "\n" + p.read_text(encoding="utf-8", errors="replace")
                         for p in (ROOT / "app/src/main").rglob("*.kt"))

if 'applicationId = "io.github.miambao.cardface.oneplus"' not in gradle: errors.append("applicationId mismatch")
if re.search(r'android:name="com\.finshell\.wallet"', manifest) is None: errors.append("manifest scope missing")
if scope.strip().splitlines() != ["com.finshell.wallet"]: errors.append("scope.list must contain only com.finshell.wallet")
if 'REPOSITORY = "Cokelce/cardface-miuix"' not in updates: errors.append("Updates repository mismatch")
if info == "" or not re.search(r'INTERNAL_VERSION\s*=\s*"\d+\.\d+\.\d+\.\d+"', info): errors.append("BuildInfo.INTERNAL_VERSION missing")
for phrase in ("OnePlus 15 / ColorOS 17（LSPosed 2.2.1）", "OnePlus 15 / ColorOS 17"):
    if about.count(phrase) < 2: errors.append(f"OnePlus verification wording needs two occurrences: {phrase}")
for label in ("从相册选", "粘贴网址", "从图库选", "在线卡面"):
    if label not in editor: errors.append(f"editor entry missing: {label}")
if not (ROOT / "LICENSE").is_file(): errors.append("GPL LICENSE missing")
if re.search(r'(/storage/emulated/0|/data/user/|/sdcard/|[A-Za-z]:\\)', gradle): errors.append("phone absolute path in Gradle")
# Wallet-targeted experimental UI/service must not be present in manifest or scope metadata.
if re.search(r'(?i)(experimental|实验).*(wallet|finshell)|(?:wallet|finshell).*(experimental|实验)', manifest): errors.append("wallet experimental UI in manifest")
if re.search(r'<service\b[^>]*android:name=', manifest): errors.append("wallet service declaration in manifest")
if "com.miui.tsmclient" in scope or "com.miui.tsmclient" in manifest: errors.append("non-OnePlus wallet scope present")

for token in ("WalletCoverActions", "WalletLocalImagePicker", "WalletCoverBridgeService", "DetailCoverHook", "DoorCoverHook", "cardface_cover_import"):
    if token in all_kotlin or token in manifest: errors.append(f"withdrawn experiment present: {token}")
for config in ("gradle.properties", "settings.gradle.kts", "build.gradle.kts"):
    if any(value in text(config) for value in ("/workspace/", "/root/toolchain/", "/storage/emulated/")):
        errors.append(f"host-specific path in {config}")
if "DeepSeek" in about: errors.append("removed About credit reintroduced")
if "https://github.com/Cokelce/cardface-miuix" not in about: errors.append("About repository mismatch")

if errors:
    print("CHECK FAIL")
    print("\n".join(f"- {e}" for e in errors))
    sys.exit(1)
print("CHECK PASS: package/scope/updates/version/OnePlus-copy/entries/LICENSE/gradle-paths")
