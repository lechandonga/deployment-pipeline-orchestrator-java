# 部署流水线编排服务（Deployment Pipeline Orchestrator）

让一次发布从 **创建 → 灰度 → 全量** 的推进过程，在并发请求、重复提交和失败重试下
保持 **一致、单调、可解释、可复现**。

- Java 21 / Spring Boot 3.4，内存存储（仓储与幂等存储均为 `ConcurrentHashMap`），无外部中间件依赖。
- 状态迁移串行化：每张发布单聚合内自带 `ReentrantLock`，所有读改写都在锁内完成。
- 幂等：创建/推进/放弃均支持 `Idempotency-Key`；相同指纹重放返回原结果，版本或参数不一致返回 `409 IDEMPOTENCY_CONFLICT`。
- 健康门禁三态（健康 / 继续等待 / 不健康）+ 超时扫描，失败/超时/放弃落入可区分终态。
- 配额占用与回补按“操作令牌”幂等，回滚后再推进不会重复扣减。

## 1. 状态模型

状态枚举：`domain/ReleaseStatus.java`

| 状态 | 是否终态 | 含义 |
| --- | --- | --- |
| `CREATED` | 否 | 发布单已创建，尚未推进 |
| `CANARY_ROLLING_OUT` | 否 | 灰度目标已部署，正在等待/接受健康门禁 |
| `CANARY_STABLE` | 否 | 灰度门禁通过，等待全量推进指令 |
| `FULL_ROLLING_OUT` | 否 | 全量目标已部署，正在等待/接受健康门禁 |
| `ROLLED_BACK` | 否（稳定态） | 显式回滚完成；可查询，不可再推进，可被放弃落终态 |
| `SUCCEEDED` | 是 | 全量门禁通过，发布成功 |
| `TIMED_OUT` | 是 | 等待健康门禁超时，已回滚 |
| `ABORTED` | 是 | 主动放弃，已回滚 |
| `ROLLBACK_FAILED` | 是 | 触发了回滚但部分目标回滚失败（最高优先级终态） |

> 失败可区分性说明：健康门禁“明确不健康”时先回滚，发布单停在 `ROLLED_BACK`（非终态稳定态），
> 失败原因记为 `FailureReason.HEALTH_GATE_UNHEALTHY`，从而与超时、放弃、目标部署失败区分开。
> `HEALTH_CHECK_FAILED` 枚举值在模型中保留为“不健康”的显式失败类别标记（terminal=true），
> 当前流程统一表达为“回滚 + 原因码”，以便后续需要时可直接落该终态而不改状态机外壳。
> `TIMED_OUT` / `ABORTED` / `SUCCEEDED` 为终态；若回滚本身有目标失败，则一律升级为
> `ROLLBACK_FAILED`，不会被任何后续状态覆盖。

### 合法迁移（单调、不可回跳）

```text
                 ┌─────────────── advance(HEALTHY) ───────────────┐
CREATED ──advance──> CANARY_ROLLING_OUT ──advance(HEALTHY)──> CANARY_STABLE
                         │  │  │                                   │
        UNHEALTHY/超时/放弃│  │PENDING(继续等待)     advance ──────>│
                         ▼  ▼                                    ▼
                    ROLLED_BACK <── 失败/放弃/超时回滚 ── FULL_ROLLING_OUT
                         │                                   │   │
              abort ────►│◄──────────── UNHEALTHY/超时 ──────┘   │HEALTHY
                         ▼                                       ▼
        ABORTED / TIMED_OUT / ROLLBACK_FAILED              SUCCEEDED(终态)
```

- 终态收到 advance/abort/rollback → `409 TERMINAL_STATE`。
- `ROLLED_BACK` 收到 advance → `409 INVALID_TRANSITION`（重试必须新建发布单，杜绝重复扣减）。
- 进入任何终态后状态机封闭，不会再被推进。

## 2. 幂等与并发策略

### 幂等

- 幂等键：HTTP 头 `Idempotency-Key`（也支持请求体携带），服务端记录
  `IdempotencyRecord{key, stage, requestFingerprint, releaseId, result, firstSeenAt}`。
- **请求指纹**：对 `application + version + 排序后的 params + 排序后的 canary/full 目标`
  做规范化后 SHA-256（见 `PipelineOrchestratorService#createFingerprint`）。
- 重放判定：
  - 同键 + 同指纹 → 返回首次结果，视图 `replay=true`，HTTP 200（创建首次为 201）。
  - 同键 + 不同指纹（版本/参数对不上）→ `409 IDEMPOTENCY_CONFLICT`，
    响应 `decisionBasis` 同时给出 `stored.fingerprint` 与 `request.fingerprint`，原因可区分。
  - 无幂等键的重复创建（同 releaseId）→ `409 IDEMPOTENCY_CONFLICT`。
- 推进/放弃也支持幂等键：重放不追加事件、不重复执行（用事件数量与配额计数断言）。

### 并发

两层锁，锁序固定为 **幂等键锁 → 发布单聚合锁**（避免死锁）：

1. **幂等键分段锁**（`ConcurrentHashMap<String,Object>`）：把同键请求的
   “查重放 → 执行 → 落账”串行化，消除 check-then-act 窗口。
2. **发布单聚合锁**（每张发布单一把可重入 `ReentrantLock`）：推进、回滚、
   超时扫描互斥，状态迁移只可能发生一次，事件序列单调。
3. `IdempotencyStore.putIfAbsent` 与仓储 `putIfAbsent` 均为原子操作，作为最后兜底。
4. 超时扫描器（`TimeoutScanner`，默认 500ms，可用 `pipeline.timeout-scan-ms` 调整）
   调用的是同一个持锁迁移方法；与推进竞争时结局互斥：**要么推进赢，要么超时赢**，
   输家拿到 `TERMINAL_STATE` 拒绝，不允许状态跳变。

## 3. 健康门禁

`gate/HealthGate.java` 返回三态 `GateResult{verdict, targets, detail}`：

| 判定 | 行为 |
| --- | --- |
| `HEALTHY` | 灰度→`CANARY_STABLE`；全量→`SUCCEEDED` |
| `PENDING` | 无失败但条件未满足，保持当前状态，可继续轮询推进；超过 `healthTimeout` → 回滚 + `TIMED_OUT` |
| `UNHEALTHY` | 立即停止并回滚，原因 `HEALTH_GATE_UNHEALTHY`，不会被静默当成成功 |

默认实现 `ScriptableHealthGate` 可注入 PENDING/UNHEALTHY 目标集；生产中可替换为探针/指标实现。
时间源为注入的 `java.time.Clock` Bean（`config/PipelineConfig`），测试用 `MutableClock` 确定性验证超时。

## 4. 回滚规则与配额

- 触发回滚的四种可区分原因（`domain/FailureReason.java`）：
  `TARGET_FAILED`（目标部署失败）、`HEALTH_GATE_UNHEALTHY`、`WAIT_TIMEOUT`、`ABANDONED`。
- 回滚只针对**已成功部署并记账**的目标（逆序执行），每个目标：
  1. 调执行器回滚；成功后从发布单“已部署集合”移除；
  2. 按令牌 `releaseId|stage|target` 回补配额，同令牌只回补一次。
- 部署失败立即中止该阶段（不继续后续目标），且**绝不静默成功**。
- 回滚完成后状态为 `ROLLED_BACK`；主动放弃再落 `ABORTED`；超时落 `TIMED_OUT`；
  若存在回滚失败目标 → `ROLLBACK_FAILED`（终态优先，`failureDetail` 同时保留原始触发原因）。
- `rollback` 接口在 `ROLLED_BACK` 上重复调用按幂等重放处理（`replay=true`），不重复回补。

## 5. REST 接口

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/releases` | 创建发布单（支持 `Idempotency-Key`） |
| `POST /api/releases/{id}/advance` | 推进一个阶段/重评门禁（支持幂等键） |
| `POST /api/releases/{id}/rollback` | 显式回滚（幂等） |
| `POST /api/releases/{id}/abort` | 主动放弃（支持幂等键） |
| `GET  /api/releases/{id}` | 查询当前视图、最终判定与完整事件流 |
| `GET  /api/releases` | 历史发布单列表（按创建时间排序） |

错误响应统一为 `{code, message, decisionBasis}`，可复现判定依据（含指纹、终态、失败原因）。

### 快速手验

```bash
mvn -o spring-boot:run
# 1) 创建
curl -s -XPOST localhost:8080/api/releases -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k1' -d '{
    "releaseId":"r1","application":"app","version":"v1",
    "params":{"region":"cn-east-1"},
    "canaryTargets":["n1"],"fullTargets":["n1","n2"],"healthTimeout":"PT2M"}'
# 2) 灰度 -> 3) 全量
curl -s -XPOST localhost:8080/api/releases/r1/advance
curl -s -XPOST localhost:8080/api/releases/r1/advance
# 4) 同键重放（replay=true）；把 v1 改成 v2 再重放 => 409 IDEMPOTENCY_CONFLICT
# 5) 查询含 events 事件流
curl -s localhost:8080/api/releases/r1
```

## 6. 验证方法

```bash
mvn -o test          # 离线全量测试（20 个用例）
```

| 测试类 | 覆盖点 |
| --- | --- |
| `ReleaseLifecycleIdempotencyTest` | 灰度→全量成功路径；同键重放返回原结果；版本/参数不一致冲突；终态不可推进；推进重放不重复执行/扣减 |
| `ReleaseFailureAndRollbackTest` | 门禁不健康回滚；PENDING→超时 `TIMED_OUT`；超时前恢复可成功；目标部署失败不静默成功；放弃 `ABORTED`；回滚幂等不重复回补；回滚失败 `ROLLBACK_FAILED` |
| `ReleaseConcurrencyTest` | 16 线程同键并发创建只产生一张单；20 线程并发推进迁移恰好各一次；推进 vs 超时竞争结局互斥；并发放弃/回滚只回补一次 |
| `ReleaseApiIntegrationTest` | HTTP 201/200/409/404 语义、`decisionBasis` 指纹差异、终态与事件流查询 |

并发用例通过线程池 + `CyclicBarrier` 同时放行，并断言：最终状态收敛、
关键迁移事件各只出现一次、配额占用/回补计数精确。所有用例日志均打印
**输入参数、关键状态与判定依据**（指纹、门禁 verdict、配额 firstTime 标志等），
配合发布单视图中的 `events`（有序、只追加）可完整复现判定过程。

## 7. 关键类索引

```text
domain/        ReleaseStatus / FailureReason / ReleaseOrder(聚合) / ReleaseEvent
repository/    ReleaseRepository + 内存实现；IdempotencyStore + 内存实现
quota/         QuotaService（令牌幂等的占用/回补）
gate/          HealthGate / GateResult(三态) / ScriptableHealthGate
deploy/        TargetDeployer / SimulatedTargetDeployer（可注入失败目标）
service/       PipelineOrchestratorService（状态机+幂等+回滚+超时）
               ReleaseQueryService / TimeoutScanner
web/           ReleaseController / GlobalExceptionHandler
config/        Clock Bean + @EnableScheduling
```
