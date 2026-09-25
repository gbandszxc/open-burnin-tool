# AGENTS.md — 煲机助手（Burn-in Tool）

安卓耳机煲机工具：多音源（合成噪声/扫频/本地音乐）、方案化四阶段煲机与自由煲机、进度记录、后台前台播放。

## 开发约束
- 进行任何修改操作前，先同步远端仓库最新代码。如果发生冲突，语义合并保留双方改动后提交，并在提交日志中写清楚改动。

## 文档索引（改动须同步维护）
- README.md / README_EN.md —— 项目说明中英双版。**任一版改动须同步另一版，保持内容一致。**
- docs/PRODUCT.md —— 产品定义与功能/交互清单。**功能或交互行为变更时更新。**
- docs/MANUAL.md / docs/MANUAL_EN.md —— 面向使用者的中英双语手册（怎么用、常见问题）。**任一版改动须同步另一版，保持内容一致；功能或交互行为变更时更新。**
- docs/DESIGN.md —— 设计系统（色彩/排版/间距/组件/动效）。**任何 UI 样式改动须更新对应条目。**

## 构建与环境（Windows + Git Bash）
- 构建：`./gradlew <task> --console=plain`；JDK 17 由 `JAVA_HOME` 环境变量指定（也可在本机 gradle.properties 中配置 `org.gradle.java.home`）
- local.properties（SDK/JDK 路径）与 key.properties（签名）为本机文件，已被 .gitignore 覆盖，永不提交；无 key.properties 时 release 签名缺失属正常

## 临时产物规范
测试截图、运行日志、adb 导出、临时脚本输出等一律写入 tmp/ 下的子目录（tmp/screenshots/、tmp/logs/、tmp/adhoc/），tmp/ 不进 git，禁止把临时产物写到仓库其它位置。

## 协作规范
- 提交信息：conventional commits，中文主题行
- 交付代码必须通过 `./gradlew :app:compileDebugKotlin`；涉及 UI 行为的改动须实机冒烟
