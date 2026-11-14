# 覆盖率门禁（P1-2）

> 变更：`com.scm.parent/pom.xml` 的 JaCoCo `check` 阈值改为可传入参数；
> `.github/workflows/maven-build.yml` 新增 `coverage-gate` job；基线在
> `scripts/ci/coverage-baseline.tsv`。

## 1. 问题与修法

原状：父 POM 里 `jacoco.skip=true`，CI 的推分支跑 `mvn clean verify` 不带
`-Djacoco.skip=false`，所以 70%/60% 的门禁**从未执行**。

直接把它打开并不可行——实测水位差异很大：

| 模块 | 行覆盖 | 分支覆盖 |
|---|---|---|
| `platform-common-web` | 27.3% | 23.6% |
| `ecom-order-center-service` | 44.7% | 44.1% |
| `ecom-payment-service` | 62.4% | 57.6% |
| `platform-gateway` | 71.9% | 55.0% |

如果一刀切要求 70/60，构建会长期变红。变红的门禁很快又会被 `continue-on-error`
或再次 `skip=true` 绕过——那就是问题复发的方式，不是解决方式。

因此采用**棘轮（ratchet）**：

- 阈值参数化为 `jacoco.line.minimum` / `jacoco.branch.minimum`（默认仍是 0.70 / 0.60）。
- CI 的 `coverage-gate` job 只对**已建立基线**的模块跑门禁，并按基线传入阈值。
- 某个模块的覆盖率**只允许上升**：补测后把 `scripts/ci/coverage-baseline.tsv` 里对应
  的两列改成新的实测值，门槛随之提高。
- 门禁覆盖的模块新增了测试但没上调基线不会失败；**覆盖率下降一定失败**。

## 2. 基线文件

`scripts/ci/coverage-baseline.tsv`：`模块(-pl 路径)<TAB>行最小值<TAB>分支最小值`。
未列出的模块不参与门禁（它们仍在 `jacoco.skip=true` 下运行，不阻塞构建）。

## 3. 本地复现某一模块的门禁

```bash
mvn -pl ../platform/common/web -f com.scm.parent/pom.xml verify \
  -Djacoco.skip=false \
  -Djacoco.line.minimum=0.273 \
  -Djacoco.branch.minimum=0.236
```

看真实数字（不带阈值，只看报告）：

```bash
mvn -pl ../platform/common/web -f com.scm.parent/pom.xml verify -Djacoco.skip=false \
  -Djacoco.line.minimum=0 -Djacoco.branch.minimum=0
# 报告在 platform/common/web/target/site/jacoco/index.html
```

> PowerShell 必须给每个 `-D` 加引号（`"-Djacoco.skip=false"`），否则 `-D` 后面的
> `.` 会被当成生命周期阶段解析，报 `Unknown lifecycle phase ".skip=false"`。

## 4. 未做的事（明确边界）

- **没有**把全仓 30+ 模块一次性纳入门禁。只纳入有真实基线的关键模块；其余模块的补测
  与提门槛是后续增量工作。
- **没有**提高任何模块的覆盖率数字。本批次只建立"不许倒退"的机制；把 27% 提到 70%
  需要真的写测试，不是配置能解决的。
- 目标值 70/60 保留在文档与默认值中，作为长期目标；当前门槛以基线为准。
