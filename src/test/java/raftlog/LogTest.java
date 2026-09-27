package raftlog;

import java.util.ArrayList;
import java.util.List;

/**
 * raftlog 既有用例：只覆盖**本地日志的追加与读取**。自带极简 runner，不引入任何第三方测试框架。
 *
 * <p>多节点一致性（{@link Replica}）这一层的语义由固定件 {@code check/Checker.java} 判定，
 * 本文件不涉及。
 *
 * <p>当前全部通过。
 */
public final class LogTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        run("emptyLogDefaults", LogTest::emptyLogDefaults);
        run("appendAdvancesLastIndex", LogTest::appendAdvancesLastIndex);
        run("appendRejectsNonContiguousIndex", LogTest::appendRejectsNonContiguousIndex);
        run("appendRejectsNullEntry", LogTest::appendRejectsNullEntry);
        run("appendAllAppendsInOrder", LogTest::appendAllAppendsInOrder);
        run("lastTermFollowsLastEntry", LogTest::lastTermFollowsLastEntry);
        run("entriesViewIsReadOnly", LogTest::entriesViewIsReadOnly);
        run("atReadsByIndex", LogTest::atReadsByIndex);
        run("atOutOfRangeIsNull", LogTest::atOutOfRangeIsNull);
        run("termAtZeroIsZero", LogTest::termAtZeroIsZero);
        run("termAtOutOfRangeThrows", LogTest::termAtOutOfRangeThrows);
        run("truncateFromDropsTail", LogTest::truncateFromDropsTail);
        run("truncateFromBeyondLastIsNoop", LogTest::truncateFromBeyondLastIsNoop);
        run("truncateFromZeroClearsLog", LogTest::truncateFromZeroClearsLog);

        int total = passed + failed;
        System.out.println(passed + "/" + total + " passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- runner

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + "  " + t);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void expectLogException(Runnable body, String message) {
        try {
            body.run();
        } catch (LogException expected) {
            return;
        } catch (Throwable t) {
            throw new AssertionError(message + " 期望=LogException 实际=抛了 " + t.getClass().getName());
        }
        throw new AssertionError(message + " 期望=LogException 实际=没有抛出");
    }

    private static Entry entry(long index, long term) {
        return new Entry(index, term, "cmd-" + index);
    }

    private static Log logOf(Entry... items) {
        Log log = new Log();
        for (Entry e : items) {
            log.append(e);
        }
        return log;
    }

    private static List<Long> indicesOf(Log log) {
        List<Long> out = new ArrayList<>();
        for (Entry e : log.entries()) {
            out.add(e.index());
        }
        return out;
    }

    // ----------------------------------------------------------- 既有用例

    private static void emptyLogDefaults() {
        Log log = new Log();
        check(log.isEmpty(), "新日志应为空");
        eq(0L, log.lastIndex(), "空日志 lastIndex()");
        eq(0L, log.lastTerm(), "空日志 lastTerm()");
        eq(List.of(), log.entries(), "空日志 entries()");
    }

    private static void appendAdvancesLastIndex() {
        Log log = new Log();
        log.append(entry(1, 1));
        eq(1L, log.lastIndex(), "追加 1 条后 lastIndex()");
        log.append(entry(2, 1));
        log.append(entry(3, 2));
        eq(3L, log.lastIndex(), "追加 3 条后 lastIndex()");
        check(!log.isEmpty(), "非空日志 isEmpty() 应为 false");
    }

    private static void appendRejectsNonContiguousIndex() {
        Log log = logOf(entry(1, 1), entry(2, 1));
        expectLogException(() -> log.append(entry(4, 1)), "跳过下标 3 直接追加 4");
        expectLogException(() -> log.append(entry(1, 1)), "重复追加已在的下标 1");
        eq(2L, log.lastIndex(), "被拒的追加不应改变 lastIndex()");
    }

    private static void appendRejectsNullEntry() {
        Log log = new Log();
        expectLogException(() -> log.append(null), "append(null)");
        eq(0L, log.lastIndex(), "append(null) 不应改变 lastIndex()");
    }

    private static void appendAllAppendsInOrder() {
        Log log = new Log();
        log.appendAll(List.of(entry(1, 1), entry(2, 1), entry(3, 2)));
        eq(List.of(1L, 2L, 3L), indicesOf(log), "appendAll 后的下标序列");
        eq(3L, log.lastIndex(), "appendAll 后的 lastIndex()");
        Log bad = logOf(entry(1, 1));
        expectLogException(() -> bad.appendAll(List.of(entry(3, 1))), "appendAll 里下标不连续");
    }

    private static void lastTermFollowsLastEntry() {
        Log log = new Log();
        eq(0L, log.lastTerm(), "空日志 lastTerm()");
        log.append(entry(1, 6));
        eq(6L, log.lastTerm(), "单条 lastTerm()");
        log.append(entry(2, 7));
        log.append(entry(3, 7));
        eq(7L, log.lastTerm(), "多条 lastTerm() 取自最后一条");
    }

    private static void entriesViewIsReadOnly() {
        Log log = logOf(entry(1, 1), entry(2, 2));
        List<Entry> view = log.entries();
        eq(2, view.size(), "entries() 的条数");
        try {
            view.add(entry(3, 3));
            throw new AssertionError("期望=entries() 返回只读视图 实际=可以 add");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        eq(2L, log.lastIndex(), "外部视图不可修改底层日志");
    }

    private static void atReadsByIndex() {
        Log log = logOf(entry(1, 1), entry(2, 3), entry(3, 3));
        eq(entry(1, 1), log.at(1), "at(1)");
        eq(entry(2, 3), log.at(2), "at(2)");
        eq(entry(3, 3), log.at(3), "at(3)");
    }

    private static void atOutOfRangeIsNull() {
        Log log = logOf(entry(1, 1), entry(2, 1));
        eq(null, log.at(0), "at(0) 是哨兵，应为 null");
        eq(null, log.at(3), "at(lastIndex()+1) 应为 null");
        eq(null, log.at(99), "at(极大下标) 应为 null");
    }

    private static void termAtZeroIsZero() {
        Log log = new Log();
        eq(0L, log.termAt(0), "空日志 termAt(0)");
        log.append(entry(1, 5));
        eq(0L, log.termAt(0), "非空日志 termAt(0) 仍为 0");
    }

    private static void termAtOutOfRangeThrows() {
        Log log = logOf(entry(1, 4));
        eq(4L, log.termAt(1), "termAt(1)");
        expectLogException(() -> log.termAt(2), "termAt(lastIndex()+1)");
        expectLogException(() -> log.termAt(-1), "termAt(-1)");
    }

    private static void truncateFromDropsTail() {
        Log log = logOf(entry(1, 1), entry(2, 1), entry(3, 2), entry(4, 2));
        log.truncateFrom(3);
        eq(List.of(1L, 2L), indicesOf(log), "truncateFrom(3) 后的下标序列");
        eq(1L, log.lastTerm(), "截断后 lastTerm()");
        log.append(entry(3, 9));
        eq(9L, log.lastTerm(), "截断后可继续追加");
    }

    private static void truncateFromBeyondLastIsNoop() {
        Log log = logOf(entry(1, 1), entry(2, 2));
        log.truncateFrom(5);
        eq(List.of(1L, 2L), indicesOf(log), "truncateFrom(超出范围) 是空操作");
        eq(2L, log.lastIndex(), "truncateFrom(超出范围) 后 lastIndex()");
    }

    private static void truncateFromZeroClearsLog() {
        Log log = logOf(entry(1, 1), entry(2, 2), entry(3, 3));
        log.truncateFrom(0);
        check(log.isEmpty(), "truncateFrom(0) 应清空日志");
        eq(0L, log.lastIndex(), "清空后 lastIndex()");
        eq(0L, log.lastTerm(), "清空后 lastTerm()");
    }

    private LogTest() {
    }
}
