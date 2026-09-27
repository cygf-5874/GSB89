package raftlog;

import java.util.List;

/**
 * 日志复制仲裁层：待补骨架。
 *
 * <p>{@link #appendEntries(AppendRequest)}、{@link #commitIndex()}、{@link #applyMembership(List)}
 * 三个方法的方法体当前一律抛 {@link UnsupportedOperationException}；其余构造与方法签名已经是给定门面。
 * 具体语义见 README「对外契约」与「本次要补的一层」。
 *
 * <p>本层是请求驱动的确定性状态机：没有时钟、没有随机、没有线程。
 */
public final class Replica {

    private final long selfId;
    private final Log log = new Log();
    private long currentTerm;
    private Long votedFor;
    private List<Long> members;

    /**
     * 以本节点 id 与初始成员集合（有序）构造。
     *
     * @param selfId  本节点 id
     * @param members 初始成员集合，保持给定顺序
     */
    public Replica(long selfId, List<Long> members) {
        this.selfId = selfId;
        this.members = List.copyOf(members);
    }

    /** 本节点 id。 */
    public long selfId() {
        return selfId;
    }

    /** 当前任期。 */
    public long currentTerm() {
        return currentTerm;
    }

    /** 本任期投票给的节点 id；未投票为 null。 */
    public Long votedFor() {
        return votedFor;
    }

    /** 当前成员集合（有序）。 */
    public List<Long> members() {
        return members;
    }

    /** 本地日志。 */
    public Log log() {
        return log;
    }

    /** 当前提交下标；空日志上为 0。 */
    public long commitIndex() {
        throw new UnsupportedOperationException("commitIndex 尚未实现");
    }

    /**
     * 处理一次日志复制请求。
     *
     * @param req 请求
     * @return 本节点的判定结果
     */
    public AppendResult appendEntries(AppendRequest req) {
        throw new UnsupportedOperationException("appendEntries 尚未实现");
    }

    /**
     * 应用一次成员变更。
     *
     * @param newMembers 目标成员集合（有序）
     * @return 生效后的成员集合
     */
    public List<Long> applyMembership(List<Long> newMembers) {
        throw new UnsupportedOperationException("applyMembership 尚未实现");
    }

    /** 一次日志复制请求。 */
    public static final class AppendRequest {

        private final long term;
        private final long prevLogIndex;
        private final long prevLogTerm;
        private final List<Entry> entries;
        private final long leaderCommit;
        private final List<Long> members;

        /**
         * @param term         请求携带的任期
         * @param prevLogIndex 新条目之前那条的下标（0 表示从空日志开头接）
         * @param prevLogTerm  上述下标的任期
         * @param entries      待复制的条目（从 prevLogIndex + 1 起连续）
         * @param leaderCommit leader 的提交下标
         * @param members      随请求携带的新成员集合；null 表示不携带成员变更
         */
        public AppendRequest(long term, long prevLogIndex, long prevLogTerm,
                             List<Entry> entries, long leaderCommit, List<Long> members) {
            this.term = term;
            this.prevLogIndex = prevLogIndex;
            this.prevLogTerm = prevLogTerm;
            this.entries = entries == null ? List.of() : List.copyOf(entries);
            this.leaderCommit = leaderCommit;
            this.members = members == null ? null : List.copyOf(members);
        }

        /** 请求携带的任期。 */
        public long term() {
            return term;
        }

        /** 新条目之前那条的下标。 */
        public long prevLogIndex() {
            return prevLogIndex;
        }

        /** 上述下标的任期。 */
        public long prevLogTerm() {
            return prevLogTerm;
        }

        /** 待复制的条目。 */
        public List<Entry> entries() {
            return entries;
        }

        /** leader 的提交下标。 */
        public long leaderCommit() {
            return leaderCommit;
        }

        /** 随请求携带的新成员集合；null 表示不携带。 */
        public List<Long> members() {
            return members;
        }
    }

    /** 一次日志复制请求的判定结果。 */
    public static final class AppendResult {

        private final long term;
        private final boolean success;
        private final long conflictIndex;
        private final long commitIndex;
        private final List<Long> members;

        /**
         * @param term          本节点当前任期
         * @param success       请求是否被接受
         * @param conflictIndex 拒绝时给出的回退下标；-1 表示与日志回退无关
         * @param commitIndex   本节点当前提交下标
         * @param members       本节点当前成员集合（有序）
         */
        public AppendResult(long term, boolean success, long conflictIndex,
                            long commitIndex, List<Long> members) {
            this.term = term;
            this.success = success;
            this.conflictIndex = conflictIndex;
            this.commitIndex = commitIndex;
            this.members = members == null ? List.of() : List.copyOf(members);
        }

        /** 本节点当前任期。 */
        public long term() {
            return term;
        }

        /** 请求是否被接受。 */
        public boolean success() {
            return success;
        }

        /** 拒绝时给出的回退下标；-1 表示与日志回退无关。 */
        public long conflictIndex() {
            return conflictIndex;
        }

        /** 本节点当前提交下标。 */
        public long commitIndex() {
            return commitIndex;
        }

        /** 本节点当前成员集合（有序）。 */
        public List<Long> members() {
            return members;
        }
    }
}
