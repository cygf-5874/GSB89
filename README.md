# raftlog — 日志复制仲裁

`raftlog` 是一个 Java 17 的日志复制库，只用 JDK 标准库，不依赖 Maven / Gradle，也不下载任何
第三方包。它把「本地日志」与「多节点一致性仲裁」分成两层：

- 本地日志层：`Log` / `Entry` / `LogException`，**完整实现**，覆盖本地追加与读取。
- 仲裁层：`Replica`，`appendEntries` / `commitIndex` / `applyMembership` **待补**。

## 构建与自检

```bash
source /c/tmp/genv.sh                      # JDK 17
bash scripts/build.sh                      # 编译 src/main/java -> out/
java -cp out${CPSEP}out-test raftlog.LogTest   # 既有 14 个用例，应打印 14/14 passed
bash scripts/check.sh                      # 固定件 11 个场景
bash scripts/check.sh -list                # 只列场景名
bash scripts/check.sh --only append        # 只跑某个组
```

`CPSEP` 是 classpath 分隔符：Windows（Git Bash / MSYS）上是 `;`，类 Unix 上是 `:`。
`scripts/check.sh` 自己按 `uname` 判定，无需手工填。手动跑既有用例时可写成
`java -cp "out;out-test" raftlog.LogTest`（Windows）。

## 对外契约（10 条）

1. `appendEntries(req)` 的第一道检查是 `prevLogIndex` / `prevLogTerm` 与本地匹配；
   不匹配 → 返回 `success=false` 且给出 `conflictIndex`。
2. `conflictIndex` 必须是**优化回退**：若本地在 `prevLogIndex` 处存在**同 term 的连续段**，
   跳到该 term 的**第一个**下标；否则退到 `min(prevLogIndex, lastIndex) + 1`；
   **不许**每次都只退一格（固定件按「单次请求即可推进的重试步长」夹这条）。
3. 匹配后按 `entries` 逐条比对：term 相同则保留（不再追加，幂等）；term 不同则**从这里截断**
   再追加。
4. `commitIndex = min(leaderCommit, lastIndexOfAppended)`；`commitIndex` **只能单调不减**。
5. `term` 单调：请求 term 小于本地 term → 全部拒绝并回传本地 term；大于本地 term →
   本地降级为 follower（`votedFor` 清空）后处理。
6. 不变量：`commitIndex` 一旦推进到某个下标，该下标的 `(index, term)` **此后永不被覆盖**
   （固定件记录每次提交时的快照，事后逐条比对）。
7. 成员变更：请求可携带 `members`（有序集合）。多数派按**联合配置**计：
   旧成员集合与新成员集合都必须各自过半，`appendEntries` 才被接受；
   变更生效后按新集合计数。
8. 边界：`entries` 为空（心跳）、`prevLogIndex = 0`、本地日志为空、
   `leaderCommit > 本地最后下标`、`members` 为空集合 → 行为已定，见固定件；一律不得抛未预期异常。
9. 结果不依赖 `HashMap` 迭代顺序；成员集合的过半判据是 `size / 2 + 1`。
10. 全确定性：没有时钟、没有随机、没有线程；同一请求序列任意次执行结果逐字段相同。

## 本次要补的一层

要补的是 `Replica` 里的人事：`appendEntries(AppendRequest)`、`commitIndex()`、
`applyMembership(List<Long>)`。`Log` / `Entry` / `LogException` / `LogTest` 与 `check/` 都不许动。

约定：日志下标从 1 开始，下标 0 是「空」的哨兵（`lastIndex() == 0`、`termAt(0) == 0`）；
任期从 1 开始；`majority(n) = n / 2 + 1`。

一次 `appendEntries` 按下述顺序处理，任一门不过即返回 `success=false` 且不改动任何状态
（`term` 门除外，见第 5 条）：

- **term 门**：请求 term 小于本地 term → 拒绝，`term` 回传本地任期；大于本地 term → 先降级
  （`currentTerm` 跟随、`votedFor` 清空）再继续；相等 → 直接继续。
- **成员门**（仅当 `req.members() != null`）：这是一次成员变更，判据为**结构上的联合多数派** ——
  记旧集合为 `old`、新集合为 `new`，交集必须同时覆盖两边的过半：
  `|old ∩ new| >= majority(|old|)` 且 `|old ∩ new| >= majority(|new|)`。
  满足 → 请求照常处理，并在本次请求成功后把成员切换为 `new`（此后一律按 `new` 计数）；
  不满足 → 整个请求被拒，`conflictIndex = -1`（与日志回退无关），成员集合不变。
  `members` 为空集合时 `majority(0) + 1 = 1 > 0`，永远不满足。
- **prev 匹配门**：`prevLogIndex == 0` 时要求 `prevLogTerm == 0`；否则要求本地在
  `prevLogIndex` 处存在一条 term 等于 `prevLogTerm` 的条目。不匹配 → 拒绝，并给出优化回退
  `conflictIndex`：本地比 `prevLogIndex` 短（`prevLogIndex > lastIndex()`）时退到
  `lastIndex() + 1`；否则退到本地在 `prevLogIndex` 那条 entry 的 **term 连续段的起点**
  （从 `prevLogIndex` 往下找第一条 term 不同为止）。**不许**每次只退一格。
- **逐条比对**：从 `entries` 的第一条起，与本地同下标条目按 `(index, term)` 比较；
  term 相同则**保留**（载荷不同也不改，幂等），继续看下一条；一旦某条 term 不同（或本地缺失），
  就**从该下标起截断**本地日志，再把 `entries` 从这一条到末尾整体追加，然后结束比对。
  截断不得触及已提交区：下标 `<= commitIndex` 的条目一律保留（`(index, term)` 永不被覆盖，
  即对外契约第 6 条）。
- **提交推进**：`commitIndex = max(旧 commitIndex, min(req.leaderCommit(), lastIndex()))`，
  只增不减（心跳带着更小的 `leaderCommit` 也不回退）。
- **成员生效**：若成员门通过且 `req.members() != null`，把成员集合切换为 `new`。

`commitIndex()` 返回当前提交下标，空日志上为 0。

`applyMembership(newMembers)` 是「成员生效」的显式入口：先用与成员门**同一套**结构联合多数派判据
校验 `newMembers`，满足则把成员集合切换过去并返回切换后的有序集合；`newMembers` 为 `null`、
空集合、含 `null` 元素、含重复 id，或联合多数派不满足，一律抛 `LogException`。

`Replica` 的状态只由请求驱动：没有时钟、没有随机、没有线程；结果与 `HashMap` 迭代顺序无关。

## 起点状态

- `bash scripts/build.sh` 退出码 0；
- `java -cp "out;out-test" raftlog.LogTest`（Linux/macOS 用 `out:out-test`）打印 `14/14 passed`；
- `bash scripts/check.sh` 退出码 1：`Replica` 的三个待补方法当前一律抛
  `UnsupportedOperationException`，固定件 11 个场景都会经过它们，因此**全红**。
