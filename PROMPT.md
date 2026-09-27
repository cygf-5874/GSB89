
单节点追加已经稳了，多节点的一致性检查这一层是空的。raftlog 是 Java 17 的
日志复制库，只用 JDK 标准库，构建与自检走 `scripts/*.sh`（`scripts/check.sh` 是固定验收程序，别改）。

`Log.java`、`Entry.java`、`LogException.java` 是完整实现，别动它们；
`LogTest.java` 的 14 个用例当前全绿，只覆盖本地追加与读取。
`Replica.java` 里 `appendEntries`、`commitIndex`、`applyMembership` 都还没实现。

任务：按 README「对外契约」的 10 条把这一层补出来，让既有用例与固定件全过。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/check.sh 退出码 0，11 个场景全过（append 3 + commit 3 + term 2 + membership 2 + robust 1）。

约束：
1. 不改 `check/`、不改 `Log.java` / `Entry.java` / `LogException.java` / `LogTest.java`。
2. 不引入第三方依赖，不用 Maven / Gradle，不许下载 JUnit。
3. 不许用时钟、随机数、线程；全局只有请求驱动的确定性状态。
4. 结果必须与 `HashMap` 迭代顺序无关。
