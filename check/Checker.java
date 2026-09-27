import raftlog.Entry;
import raftlog.LogException;
import raftlog.Replica;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * raftlog 的固定验收程序（勿改）。
 *
 * <p>11 个场景覆盖 README「对外契约」的 10 条：append 3 / commit 3 / term 2 / membership 2 / robust 1。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带 30 秒看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有随机源、没有墙钟、没有线程。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗（秒）。 */
    private static final long WATCHDOG_SECONDS = 30L;

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    /** 在独立子进程里跑单个场景，把子进程给出的 PASS/FAIL 行原样返回。 */
    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + WATCHDOG_SECONDS + " 秒内给出 PASS/FAIL 实际="
                    + WATCHDOG_SECONDS + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // ------------------------------------------------ append（3）

        out.add(new Scenario("append/prev-mismatch-optimized-backoff", () -> {
            Replica r = new Replica(1L, ids(1L, 2L, 3L));
            String e;

            Replica.AppendResult res = r.appendEntries(
                    req(1L, 0L, 0L, list(e(1, 1), e(2, 1), e(3, 1)), 0L, null));
            e = expectTrue("首次追加被接受", res.success());
            if (e != null) {
                return e;
            }
            res = r.appendEntries(req(1L, 3L, 1L,
                    list(e(4, 2), e(5, 2), e(6, 2), e(7, 2), e(8, 2), e(9, 2)), 0L, null));
            e = expectTrue("分叉尾巴被接受", res.success());
            if (e != null) {
                return e;
            }

            List<Entry> leader = list(e(1, 1), e(2, 1), e(3, 1),
                    e(4, 3), e(5, 3), e(6, 3), e(7, 3), e(8, 3), e(9, 3));

            long next = 9L;
            int rejections = 0;
            Long firstConflict = null;
            while (true) {
                long prevIndex = next;
                long prevTerm = termAt(leader, prevIndex);
                List<Entry> tail = tail(leader, prevIndex + 1);
                Replica.AppendResult r2 = r.appendEntries(req(2L, prevIndex, prevTerm, tail, 0L, null));
                if (r2.success()) {
                    break;
                }
                rejections++;
                if (firstConflict == null) {
                    firstConflict = r2.conflictIndex();
                }
                long c = r2.conflictIndex();
                if (c <= 0 || c > prevIndex) {
                    return "期望=conflictIndex 落在 (0, prevLogIndex] 实际=" + c + "（prevLogIndex=" + prevIndex + "）";
                }
                next = c - 1;
                if (rejections > 10) {
                    return "期望=在很少的拒绝次数内收敛 实际=已拒绝 " + rejections + " 次仍未收敛";
                }
            }

            e = expect("首次拒绝的 conflictIndex 应跳到同 term 连续段的起点（不是 prevIndex-1）",
                    Long.valueOf(4L), firstConflict);
            if (e != null) {
                return e;
            }
            e = expect("优化回退后收敛所需的拒绝次数", Long.valueOf(1L), Long.valueOf(rejections));
            if (e != null) {
                return e;
            }
            return expect("收敛后的日志 (下标:任期)",
                    list("1:1", "2:1", "3:1", "4:3", "5:3", "6:3", "7:3", "8:3", "9:3"), pairs(r.log()));
        }));

        out.add(new Scenario("append/term-diff-truncate", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            r.appendEntries(req(1L, 0L, 0L, list(e(1, 1), e(2, 1)), 0L, null));
            r.appendEntries(req(1L, 2L, 1L, list(e(3, 2), e(4, 2), e(5, 2)), 0L, null));
            e = expect("截断前日志", list("1:1", "2:1", "3:2", "4:2", "5:2"), pairs(r.log()));
            if (e != null) {
                return e;
            }

            Replica.AppendResult res = r.appendEntries(
                    req(3L, 2L, 1L, list(e(3, 3), e(4, 3)), 0L, null));
            e = expectTrue("term 不同的重发被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("从第一个 term 不同的条目起截断再追加",
                    list("1:1", "2:1", "3:3", "4:3"), pairs(r.log()));
            if (e != null) {
                return e;
            }
            return expect("截断追加后的 lastIndex()", Long.valueOf(4L), Long.valueOf(r.log().lastIndex()));
        }));

        out.add(new Scenario("append/same-term-idempotent", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            r.appendEntries(req(1L, 0L, 0L, list(e(1, 1), e(2, 1), e(3, 1)), 0L, null));
            List<String> before = pairs(r.log());

            // 同 (下标, 任期) 的一条重发：即使载荷不同，也必须保留本地、不重复追加。
            Replica.AppendResult res = r.appendEntries(req(1L, 0L, 0L,
                    list(e(1, 1), new Entry(2L, 1L, "different-payload"), e(3, 1)), 0L, null));
            e = expectTrue("全同 term 的重发被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("同 term 重发后日志不变（幂等）", before, pairs(r.log()));
            if (e != null) {
                return e;
            }

            res = r.appendEntries(req(1L, 3L, 1L, list(e(4, 1)), 0L, null));
            e = expectTrue("心跳后续追加被接受", res.success());
            if (e != null) {
                return e;
            }
            return expect("同 term 保留、其后正常追加",
                    list("1:1", "2:1", "3:1", "4:1"), pairs(r.log()));
        }));

        // ------------------------------------------------ commit（3）

        out.add(new Scenario("commit/min-leader-commit-last", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            Replica.AppendResult res = r.appendEntries(
                    req(1L, 0L, 0L, list(e(1, 1), e(2, 1), e(3, 1)), 5L, null));
            e = expect("leaderCommit 超过本地最后下标时按 lastIndex() 夹取",
                    Long.valueOf(3L), Long.valueOf(res.commitIndex()));
            if (e != null) {
                return e;
            }
            e = expect("commitIndex() 与结果一致", Long.valueOf(3L), Long.valueOf(r.commitIndex()));
            if (e != null) {
                return e;
            }

            res = r.appendEntries(req(1L, 3L, 1L, list(e(4, 1), e(5, 1)), 4L, null));
            e = expect("leaderCommit 小于本地最后下标时按 leaderCommit 取",
                    Long.valueOf(4L), Long.valueOf(res.commitIndex()));
            if (e != null) {
                return e;
            }
            return expect("commitIndex() 与结果一致", Long.valueOf(4L), Long.valueOf(r.commitIndex()));
        }));

        out.add(new Scenario("commit/monotone-nondecreasing", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            long[] seen = new long[4];
            seen[0] = r.appendEntries(req(1L, 0L, 0L, list(e(1, 1), e(2, 1), e(3, 1)), 3L, null)).commitIndex();
            seen[1] = r.appendEntries(req(1L, 3L, 1L, list(), 1L, null)).commitIndex();
            seen[2] = r.appendEntries(req(1L, 3L, 1L, list(), 2L, null)).commitIndex();
            seen[3] = r.appendEntries(req(1L, 3L, 1L, list(e(4, 1)), 4L, null)).commitIndex();

            e = expect("commitIndex 只能单调不减（心跳携带更小的 leaderCommit 也不回退）",
                    list("3", "3", "3", "4"), strs(seen));
            if (e != null) {
                return e;
            }
            long prev = -1L;
            for (long v : seen) {
                if (v < prev) {
                    return "期望=commitIndex 单调不减 实际=" + strs(seen);
                }
                prev = v;
            }
            return expect("最终 commitIndex()", Long.valueOf(4L), Long.valueOf(r.commitIndex()));
        }));

        out.add(new Scenario("commit/committed-pair-immutable", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            Replica.AppendResult res = r.appendEntries(
                    req(1L, 0L, 0L, list(e(1, 1), e(2, 1), e(3, 1)), 3L, null));
            e = expectTrue("首次提交成功", res.success());
            if (e != null) {
                return e;
            }
            List<String> committed = pairs(r.log());
            e = expect("提交时记录的快照", list("1:1", "2:1", "3:1"), committed);
            if (e != null) {
                return e;
            }

            // 新 leader（更高 term）在已提交区发来冲突条目：已提交的 (下标, 任期) 不得被覆盖。
            res = r.appendEntries(req(2L, 2L, 1L, list(e(3, 2), e(4, 2)), 2L, null));
            e = expectTrue("冲突请求本身被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("提交下标不得因此回退", Long.valueOf(3L), Long.valueOf(r.commitIndex()));
            if (e != null) {
                return e;
            }

            List<String> after = pairs(r.log());
            for (String pair : committed) {
                if (!after.contains(pair)) {
                    return "期望=已提交的 (下标, 任期) " + pair + " 永不被覆盖 实际=最终日志 " + after;
                }
            }
            e = expect("已提交区的任期不被改写", Long.valueOf(1L), Long.valueOf(r.log().at(3L).term()));
            if (e != null) {
                return e;
            }
            return expect("冲突请求只在提交下界之上生效",
                    list("1:1", "2:1", "3:1", "4:2"), after);
        }));

        // ------------------------------------------------ term（2）

        out.add(new Scenario("term/smaller-term-rejected", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            Replica.AppendResult res = r.appendEntries(req(5L, 0L, 0L, list(e(1, 5)), 0L, null));
            e = expectTrue("较高 term 的请求被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("本地任期升到 5", Long.valueOf(5L), Long.valueOf(r.currentTerm()));
            if (e != null) {
                return e;
            }

            res = r.appendEntries(req(3L, 0L, 0L, list(e(2, 3)), 0L, null));
            e = expectTrue("较小 term 的请求被拒", !res.success());
            if (e != null) {
                return e;
            }
            e = expect("拒绝时回传本地 term", Long.valueOf(5L), Long.valueOf(res.term()));
            if (e != null) {
                return e;
            }
            e = expect("本地任期不变", Long.valueOf(5L), Long.valueOf(r.currentTerm()));
            if (e != null) {
                return e;
            }
            return expect("较小 term 的条目不落盘", list("1:5"), pairs(r.log()));
        }));

        out.add(new Scenario("term/larger-term-step-down", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            e = expect("新节点初始任期", Long.valueOf(0L), Long.valueOf(r.currentTerm()));
            if (e != null) {
                return e;
            }
            e = expect("新节点初始无投票", null, r.votedFor());
            if (e != null) {
                return e;
            }

            Replica.AppendResult res = r.appendEntries(req(4L, 0L, 0L, list(e(1, 4)), 0L, null));
            e = expectTrue("较大 term 的请求被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("本地任期跟随升到 4", Long.valueOf(4L), Long.valueOf(r.currentTerm()));
            if (e != null) {
                return e;
            }
            e = expect("结果里回传的 term 为 4", Long.valueOf(4L), Long.valueOf(res.term()));
            if (e != null) {
                return e;
            }
            e = expect("降级后投票被清空", null, r.votedFor());
            if (e != null) {
                return e;
            }

            res = r.appendEntries(req(3L, 1L, 4L, list(e(2, 3)), 0L, null));
            e = expectTrue("降级后更小 term 被拒", !res.success());
            if (e != null) {
                return e;
            }
            return expect("降级后更小 term 的条目不落盘", list("1:4"), pairs(r.log()));
        }));

        // ------------------------------------------------ membership（2）

        out.add(new Scenario("membership/joint-majority", () -> {
            Replica r = new Replica(1L, ids(1L, 2L, 3L));
            String e;

            // 旧 {1,2,3} 与新 {2,3,4} 交集 {2,3} = 2，满足两边各自的过半（majority(3)=2）。
            Replica.AppendResult res = r.appendEntries(req(1L, 0L, 0L, list(), 0L, ids(2L, 3L, 4L)));
            e = expectTrue("交集同时过旧、新两头半：接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("生效后的成员集合", ids(2L, 3L, 4L), r.members());
            if (e != null) {
                return e;
            }

            // 新 {4,5,6} 与旧 {2,3,4} 交集 {4} = 1，不过旧集合的过半（majority(3)=2）。
            res = r.appendEntries(req(1L, 0L, 0L, list(), 0L, ids(4L, 5L, 6L)));
            e = expectTrue("交集不过旧集合过半：拒绝", !res.success());
            if (e != null) {
                return e;
            }
            e = expect("成员集合的过半判据是 size/2+1；拒绝时成员集合不变", ids(2L, 3L, 4L), r.members());
            if (e != null) {
                return e;
            }
            return expect("成员变更被拒与日志回退无关，conflictIndex=-1",
                    Long.valueOf(-1L), Long.valueOf(res.conflictIndex()));
        }));

        out.add(new Scenario("membership/new-config-counts", () -> {
            Replica r = new Replica(1L, ids(1L, 2L, 3L));
            String e;

            List<Long> after1 = r.applyMembership(ids(1L, 2L, 4L));
            e = expect("applyMembership 生效后的成员集合（交集 {1,2}=2 过两头半）",
                    ids(1L, 2L, 4L), after1);
            if (e != null) {
                return e;
            }

            List<Long> after2 = r.applyMembership(ids(1L, 2L, 4L, 5L, 6L));
            e = expect("配置扩大到 5 人后的成员集合（交集 3 过两头半）",
                    ids(1L, 2L, 4L, 5L, 6L), after2);
            if (e != null) {
                return e;
            }

            // 现在按 5 人集合计数：majority(5)=3，交集 {1,2}=2 不达标 → 拒绝。
            Replica.AppendResult res = r.appendEntries(req(1L, 0L, 0L, list(), 0L, ids(1L, 2L, 9L)));
            e = expectTrue("变更生效后按新集合的过半计数：拒绝", !res.success());
            if (e != null) {
                return e;
            }
            e = expect("拒绝后成员集合不变", ids(1L, 2L, 4L, 5L, 6L), r.members());
            if (e != null) {
                return e;
            }

            try {
                r.applyMembership(ids(1L, 2L, 9L));
            } catch (LogException expected) {
                return null;
            }
            return "期望=applyMembership 对不满足联合过半的入参抛 LogException 实际=没有抛出";
        }));

        // ------------------------------------------------ robust（1）

        out.add(new Scenario("robust/boundaries", () -> {
            Replica r = new Replica(1L, ids(1L));
            String e;

            // (a) entries 为空（心跳）
            Replica.AppendResult res = r.appendEntries(req(1L, 0L, 0L, list(), 0L, null));
            e = expectTrue("空 entries（心跳）被接受", res.success());
            if (e != null) {
                return e;
            }
            e = expect("心跳后 lastIndex()", Long.valueOf(0L), Long.valueOf(r.log().lastIndex()));
            if (e != null) {
                return e;
            }
            e = expect("心跳后 commitIndex()", Long.valueOf(0L), Long.valueOf(r.commitIndex()));
            if (e != null) {
                return e;
            }

            // (b) prevLogIndex = 0
            res = r.appendEntries(req(1L, 0L, 0L, list(e(1, 1)), 0L, null));
            e = expectTrue("prevLogIndex=0 被接受", res.success());
            if (e != null) {
                return e;
            }

            // (c) 本地日志为空时 prevLogIndex 超出 → 拒绝，回退 min(prevLogIndex, lastIndex)+1
            Replica fresh = new Replica(2L, ids(2L));
            res = fresh.appendEntries(req(1L, 1L, 1L, list(e(2, 1)), 0L, null));
            e = expectTrue("本地为空时 prevLogIndex=1 被拒", !res.success());
            if (e != null) {
                return e;
            }
            e = expect("本地为空时的回退下标", Long.valueOf(1L), Long.valueOf(res.conflictIndex()));
            if (e != null) {
                return e;
            }

            // (d) leaderCommit 大于本地最后下标
            res = fresh.appendEntries(req(1L, 0L, 0L, list(e(1, 1), e(2, 1)), 10L, null));
            e = expect("leaderCommit 超过本地最后下标时按 lastIndex() 夹取",
                    Long.valueOf(2L), Long.valueOf(res.commitIndex()));
            if (e != null) {
                return e;
            }

            // (e) members 为空集合
            res = r.appendEntries(req(1L, 1L, 1L, list(), 0L, ids()));
            e = expectTrue("空成员集合被拒（majority(0)+1 无法达成）", !res.success());
            if (e != null) {
                return e;
            }
            return expect("空成员集合被拒后成员集合不变", ids(1L), r.members());
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    private static Replica.AppendRequest req(long term, long prevLogIndex, long prevLogTerm,
                                             List<Entry> entries, long leaderCommit, List<Long> members) {
        return new Replica.AppendRequest(term, prevLogIndex, prevLogTerm, entries, leaderCommit, members);
    }

    private static Entry e(long index, long term) {
        return new Entry(index, term, "cmd-" + index);
    }

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static List<Long> ids(Long... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static long termAt(List<Entry> log, long index) {
        if (index <= 0) {
            return 0L;
        }
        return log.get((int) index - 1).term();
    }

    private static List<Entry> tail(List<Entry> log, long fromIndex) {
        List<Entry> out = new ArrayList<>();
        for (Entry x : log) {
            if (x.index() >= fromIndex) {
                out.add(x);
            }
        }
        return out;
    }

    /** 把日志压成 `下标:任期` 的字符串序列，便于逐条比对。 */
    private static List<String> pairs(raftlog.Log log) {
        List<String> out = new ArrayList<>();
        for (Entry x : log.entries()) {
            out.add(x.index() + ":" + x.term());
        }
        return out;
    }

    private static List<String> strs(long[] values) {
        List<String> out = new ArrayList<>();
        for (long v : values) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static String expect(String what, Object expected, Object actual) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (same) {
            return null;
        }
        return "期望=" + what + " → " + expected + " 实际=" + actual;
    }

    private static String expectTrue(String what, boolean cond) {
        return cond ? null : "期望=" + what + " 实际=不满足";
    }

    private Checker() {
    }
}
