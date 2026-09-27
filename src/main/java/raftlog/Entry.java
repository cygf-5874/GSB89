package raftlog;

import java.util.Objects;

/**
 * 一条日志条目：不可变。完整实现，勿改。
 *
 * <p>下标从 1 开始（下标 0 是「空日志」的哨兵），任期从 1 开始。
 */
public final class Entry {

    private final long index;
    private final long term;
    private final String command;

    /** 构造一条条目。 */
    public Entry(long index, long term, String command) {
        this.index = index;
        this.term = term;
        this.command = command;
    }

    /** 本条目的下标（≥ 1）。 */
    public long index() {
        return index;
    }

    /** 本条目的任期（≥ 1）。 */
    public long term() {
        return term;
    }

    /** 条目载荷。 */
    public String command() {
        return command;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Entry)) {
            return false;
        }
        Entry other = (Entry) o;
        return index == other.index && term == other.term && Objects.equals(command, other.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, term, command);
    }

    @Override
    public String toString() {
        return "Entry(" + index + "," + term + "," + command + ")";
    }
}
