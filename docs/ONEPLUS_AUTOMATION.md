# 一加适配自动化

上游：<https://github.com/MiamBao/cardface-miuix>  
目标：<https://github.com/Cokelce/cardface-miuix>  
唯一目标钱包：`com.finshell.wallet`。

## 已保留的定制

- 上游 2.0 系列界面与功能，一加卡片采集、识别、替换和同步兼容层。
- 编辑页相册、粘贴网址、图库、在线卡面四入口，两行两列。
- 关于页指定文案，项目地址、更新列表和更新公告指向本仓库。
- 独立包名 `io.github.miambao.cardface.oneplus`。
- 已撤回的钱包内封面页面、选图和后台桥接不会重新引入；静态检查会验证。

## 工作流

### OnePlus CI

`main` 推送、同仓库 PR 和手动运行触发：静态检查 → 自动化脚本测试 → App/imaging 单元测试 → 签名 APK → 证书验证 → 上传 Artifact。

- `main` 推送检查通过后，创建对应版本的 **预发布 Release**；现有 Release 不覆盖。
- 手动触发默认只构建；勾选 `publish` 才发布预发布版。
- 外部仓库 PR 不读取签名 Secret，也不会执行本工作流中的带密钥构建。
- APK 固定命名 `CardFace-<versionName>.apk`，附 SHA256。

### Upstream OnePlus Sync

每小时 UTC 第 17 分钟检查上游 `main` 最新提交。也可在 Actions 手动执行。

1. 上游无变化：退出，不消耗 Android 编译时间。
2. 有变化：以当前 main 创建 `automation/upstream-<sha>` 分支，三方合并上游。
3. 固定应用标识、作用域、用户更新源、版本及自动化配置；适配业务源码发生冲突时报告 Issue，不猜测 Hook 改法。
4. 静态检查、单元测试、编译及签名校验全部通过后，推送适配分支并保留 PR。
5. **直接从通过检查的适配分支提交创建预发布 Release，上传签名 APK 与 SHA256，无需等待 PR 合并。** 应用更新源会包含这些预发布版。
6. 已有适配分支但尚未发布时，下一次检查会恢复构建与发布；已完整发布的版本不重复构建，不覆盖附件。连续未合并的上游更新也按已发布 tag 递增版本后缀，保持版本码增长。

发布先创建 draft、确认附件完整后才公开为 prerelease。**不自动合并 PR，不自动把云端编译成功当作钱包实机验收。** 钱包内部类名、字段或方法变化时，仍需结合对应钱包版本重新适配。GitHub 定时工作流可能延迟，长期无活动仓库的 schedule 也可能被平台暂停。

## 状态与版本

`.github/upstream-state.json` 记录已集成的上游提交。只有成功的适配分支才更新该文件，合入 main 后代表正式接受这个上游基线。

- 同一上游版本持续更新：递增 `oneplus.N`。
- 上游基版本变化：使用新 `X.Y.Z-oneplus.1`。
- `versionCode` 高于当前 main 和可读历史 tag 中的版本码。
- 钱包作用域、更新源或实验代码检查失败时停止发布。

## 签名与权限

仓库 Secret：`ANDROID_DEBUG_KEYSTORE_BASE64`，存放与现有手机测试版相同的签名 keystore。该文件只在 Runner HOME 恢复，不提交 Git。固定证书 SHA256：

```text
62e7a1035526449f0c04f6e3045295bb92a114aaf9c125253d01e93a81e52062
```

自动流程使用仓库内置 `GITHUB_TOKEN`，不需要保存个人访问令牌。同步任务显式申请 contents、pull-requests、issues 写权限；仓库需允许 Actions 创建 PR。

## 本地验证

```bash
python3 tools/ci/check_oneplus.py
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
bash ./gradlew --no-daemon :app:testDebugUnitTest :imaging:test :app:assembleDebug
```

Linux 云端使用标准 Android SDK/AAPT2；手机本地编译可通过命令行 `-Pandroid.aapt2FromMavenOverride=/workspace/tools/aapt2` 指定本机工具，不在仓库写死手机绝对路径。

## 更新日志展示

应用内公告和 Releases 正文只包含「上游更新」「一加适配」两节，不混入测试数量、构建链接、提交编号、包名和 SHA256。校验值保留在附件和 GitHub asset digest，构建证据保留在 Actions。

已整理版本说明保存在 `.github/release-notes.json`；新同步版本按上游提交变更生成对应条目并保留实际一加适配说明。未知版本没有条目时不复用旧版本日志，避免错误展示。

## README 合并规则

`README.md` 以本仓库 `main` 的最新内容为准，不被上游同步覆盖；包括小米智能卡后追加的一加钱包说明、自有 Releases 下载地址，以及已移除的 LSPosed 模块仓库链接。你以后直接修改 README，下一次同步也会保留你的最新版本。上游 README 的改动不自动覆盖本分支文档。
