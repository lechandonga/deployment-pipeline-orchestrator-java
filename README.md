# 部署流水线编排服务

一次发布从创建、灰度到全量的推进编排服务。在并发请求与失败重试下保持一致、可解释。

## 状态模型

```
CREATED ──advance──> CANARY_IN_PROGRESS ──advance──> FULL_ROLLOUT_IN_PROGRESS ──advance──> SUCCEEDED
   │                      │                                 │
   │                      │                                 │
   └──失败/放弃/超时──────┴──────────────┬──────────────────┘
                                          ▼
        FAILED(HEALTH_GATE_UNMET / TARGET_FAILED) ──rollback──> ROLLED_BACK
        ABORTED(ABORTED_BY_USER)   TIMED_OUT(TIMEOUT)
```

- 推进**串行且单调**：每次 `advance` 只前移一个阶段，不允许倒退或跳变。
- 终态：`SUCCEEDED / FAILED / ROLLED_BACK / ABORTED / TIMED_OUT`。进入终态后一切推进请求被拒绝（`TERMINAL_STATE`）。唯一例外：`FAILED` 允许 `rollback` 进入 `ROLLED_BACK`。
- 每次状态迁移写入 `StageEvent` 审计历史（时间、源/目标状态、阶段、详情），可通过 `GET /api/releases/{id}` 查询并复现最终判定。

## 幂等策略

- 所有变更操作要求 `Idempotency-Key` 请求头。
- 首次执行后记录 `(key -> 参数指纹 + 结果快照)`：
  - **同 key 同参数重放** → 返回原结果，响应体 `replayed=true`，不重复执行（HTTP 200）。
  - **同 key 不同参数**（版本/目标/操作类型对不上）→ 拒绝，`code=IDEMPOTENCY_CONFLICT`（HTTP 409）。
- 创建时同 key 并发竞争：后到请求丢弃新建结果，按已登记记录判定，不会产生重复发布单。

## 并发策略

- 每个发布单一把锁（`ConcurrentHashMap<releaseId, lock>`），所有状态变更在锁内完成，阶段推进天然串行。
- 锁内二次检查幂等记录，防止并发下重复执行。
- 目标级幂等：已完成目标（`completedTargets`）不会重复部署；已回滚目标（`rolledBackTargets`）不会重复回滚扣减。

## 健康门禁与回滚规则

- **健康门禁**（`HealthGate`）：灰度部署后、进入全量前、最终成功前各检查一次；任一目标不健康 → `FAILED / HEALTH_GATE_UNMET`。
- **目标失败**：任一目标部署失败 → `FAILED / TARGET_FAILED`，绝不会静默当成成功。
- **回滚**：从进行中状态或 `FAILED` 触发，仅回滚"已完成且未回滚过"的目标，然后进入 `ROLLED_BACK`；回滚是终态，之后推进/再回滚均被拒绝，杜绝重复扣减与状态跳变。
- **主动放弃**：非终态可 `abort` → `ABORTED / ABORTED_BY_USER`。
- **超时**：阶段停留超过 `stageTimeoutMillis`（创建时可配，默认 5 分钟）→ `TIMED_OUT / TIMEOUT`。超时判定在查询与 `sweepTimeouts()` 扫描时惰性生效，发布单不会一直卡住。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/releases` | 创建发布单（body: version, canaryTargets, fullTargets, stageTimeoutMillis?） |
| POST | `/api/releases/{id}/advance` | 推进一个阶段 |
| POST | `/api/releases/{id}/rollback` | 失败回滚 |
| POST | `/api/releases/{id}/abort` | 主动放弃 |
| GET  | `/api/releases/{id}` | 查询发布单（含审计历史） |

错误响应：`{"code": "...", "message": "..."}`，code 取值 `NOT_FOUND / IDEMPOTENCY_CONFLICT / TERMINAL_STATE / TARGET_FAILED / HEALTH_GATE_UNMET`。

## 验证方法

```bash
mvn test
```

`ReleaseServiceTest` 覆盖（日志打印输入、关键状态与判定依据）：

- `createIsIdempotentAndRejectsMismatchedReplay` — 重复提交返回原结果；参数对不上的重放被拒绝。
- `concurrentAdvanceWithSameKeyExecutesOnlyOnce` — 16 线程同 key 重试风暴：只执行一次，其余拿到重放。
- `concurrentAdvanceWithDistinctKeysStaysMonotonic` — 16 线程不同 key：推进串行单调、目标不重复部署、状态不丢失。
- `advanceReplayReturnsOriginalResult` — 推进重放返回原结果且不重复部署。
- `fullRolloutFailureRollsBackOnceAndStaysTerminal` — 全量中途失败 → 回滚一次 → 终态后拒绝再回滚/再推进。
- `unhealthyCanaryFailsWithHealthGateReason` — 健康门禁未满足落到可区分失败终态。
- `waitingTooLongEndsInTimeout` — 等待超时落到 `TIMED_OUT`。
- `abortEndsInDistinctTerminalAndHistoryIsReproducible` — 主动放弃与历史可复现。
- `happyPathReachesSucceededAndRejectsFurtherAdvance` — 全链路成功且终态拒绝推进。
