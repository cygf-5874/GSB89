package raftlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地日志：完整实现，勿改。
 *
 * <p>下标从 1 开始；下标 0 是「空日志」的哨兵：{@code lastIndex() == 0}、{@code termAt(0) == 0}。
 * 日志在内存里始终保持连续（没有空洞）：{@code append} 只接受下标恰为 {@code lastIndex() + 1}
 * 的条目。
 */
public final class Log {

    /** entries.get(i) 的下标恒为 i + 1。 */
    private final List<Entry> entries = new ArrayList<>();

    /** 空日志。 */
    public Log() {
    }

    /** 追加一条条目；其下标必须等于 {@code lastIndex() + 1}，否则抛 {@link LogException}。 */
    public void append(Entry e) {
        if (e == null) {
            throw new LogException("entry 不能为 null");
        }
        long expected = lastIndex() + 1;
        if (e.index() != expected) {
            throw new LogException("append 的下标不连续：期望 " + expected + " 实际 " + e.index());
        }
        entries.add(e);
    }

    /** 依次追加一段条目，等价于逐条 {@link #append(Entry)}。 */
    public void appendAll(List<Entry> more) {
        if (more == null) {
            throw new LogException("entries 不能为 null");
        }
        for (Entry e : more) {
            append(e);
        }
    }

    /**
     * 从下标 {@code index}（含）起截断，丢掉其后的全部条目。
     * {@code index} 不大于 0 时清空整个日志；超出范围时是空操作。
     */
    public void truncateFrom(long index) {
        if (index <= 0) {
            entries.clear();
            return;
        }
        if (index > lastIndex()) {
            return;
        }
        entries.subList((int) index - 1, entries.size()).clear();
    }

    /** 全部条目（只读视图，按下标递增）。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** 最后一条的下标；空日志为 0。 */
    public long lastIndex() {
        return entries.size();
    }

    /** 最后一条的任期；空日志为 0。 */
    public long lastTerm() {
        return entries.isEmpty() ? 0L : entries.get(entries.size() - 1).term();
    }

    /** 下标 {@code index} 处的条目；越界（含 0）返回 null。 */
    public Entry at(long index) {
        if (index <= 0 || index > lastIndex()) {
            return null;
        }
        return entries.get((int) index - 1);
    }

    /**
     * 下标 {@code index} 处的任期；{@code index == 0} 返回 0；
     * 其余越界抛 {@link LogException}。
     */
    public long termAt(long index) {
        if (index == 0) {
            return 0L;
        }
        Entry e = at(index);
        if (e == null) {
            throw new LogException("下标越界：" + index);
        }
        return e.term();
    }

    /** 是否为空日志。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
