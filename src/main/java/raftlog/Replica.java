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
    private long commitIndex;

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
        return commitIndex;
    }

    /**
     * 处理一次日志复制请求。
     *
     * @param req 请求
     * @return 本节点的判定结果
     */
    public AppendResult appendEntries(AppendRequest req) {
        // term 门：小于本地 term 直接拒绝；大于本地 term 先降级再继续。
        if (req.term() < currentTerm) {
            return new AppendResult(currentTerm, false, -1L, commitIndex, members);
        }
        if (req.term() > currentTerm) {
            currentTerm = req.term();
            votedFor = null;
        }

        // 成员门：仅当请求携带 members 时，做一次结构上的新旧联合多数派校验。
        if (req.members() != null && !jointMajority(members, req.members())) {
            return new AppendResult(currentTerm, false, -1L, commitIndex, members);
        }

        // prev 匹配门：prevLogIndex == 0 要求 prevLogTerm == 0；
        // 否则本地在该下标处必须存在同 term 的条目。
        long prevIndex = req.prevLogIndex();
        boolean prevMatches = prevIndex == 0
                ? req.prevLogTerm() == 0L
                : log.at(prevIndex) != null && log.termAt(prevIndex) == req.prevLogTerm();
        if (!prevMatches) {
            return new AppendResult(currentTerm, false, optimizedConflictIndex(prevIndex),
                    commitIndex, members);
        }

        // 逐条比对：同 term 保留（幂等）；term 不同或本地缺失则从这里截断再追加。
        // 已提交区（下标 <= commitIndex）一律保留，(index, term) 永不被覆盖。
        List<Entry> incoming = req.entries();
        for (int k = 0; k < incoming.size(); k++) {
            Entry entry = incoming.get(k);
            long index = entry.index();
            if (index <= commitIndex) {
                continue;
            }
            Entry local = log.at(index);
            if (local != null && local.term() == entry.term()) {
                continue;
            }
            log.truncateFrom(index);
            for (int j = k; j < incoming.size(); j++) {
                Entry toAppend = incoming.get(j);
                if (toAppend.index() > commitIndex) {
                    log.append(toAppend);
                }
            }
            break;
        }

        // 提交推进：只增不减。
        commitIndex = Math.max(commitIndex, Math.min(req.leaderCommit(), log.lastIndex()));

        // 成员生效：成员门已通过且请求携带了新集合。
        if (req.members() != null) {
            members = req.members();
        }

        return new AppendResult(currentTerm, true, -1L, commitIndex, members);
    }

    /**
     * 应用一次成员变更。
     *
     * @param newMembers 目标成员集合（有序）
     * @return 生效后的成员集合
     */
    public List<Long> applyMembership(List<Long> newMembers) {
        if (newMembers == null || newMembers.isEmpty()) {
            throw new LogException("成员集合不能为 null 或空");
        }
        for (int i = 0; i < newMembers.size(); i++) {
            Long id = newMembers.get(i);
            if (id == null) {
                throw new LogException("成员集合不能含 null 元素");
            }
            for (int j = i + 1; j < newMembers.size(); j++) {
                if (id.equals(newMembers.get(j))) {
                    throw new LogException("成员集合含重复 id：" + id);
                }
            }
        }
        if (!jointMajority(members, newMembers)) {
            throw new LogException("新旧成员集合不满足联合多数派");
        }
        members = List.copyOf(newMembers);
        return members;
    }

    /** 过半判据：{@code n / 2 + 1}。 */
    private static long majority(int n) {
        return n / 2 + 1;
    }

    /**
     * 结构上的联合多数派：旧集合与新集合的交集必须同时覆盖两边各自的过半。
     * 只计数、不迭代依赖顺序，结果与 {@code HashMap} 迭代顺序无关。
     */
    private static boolean jointMajority(List<Long> oldMembers, List<Long> newMembers) {
        int intersection = 0;
        for (Long id : newMembers) {
            if (id != null && oldMembers.contains(id)) {
                intersection++;
            }
        }
        return intersection >= majority(oldMembers.size())
                && intersection >= majority(newMembers.size());
    }

    /**
     * prev 不匹配时的优化回退下标：本地比 prevLogIndex 短时退到 {@code lastIndex() + 1}；
     * 否则退到本地在 prevLogIndex 那条 entry 的 term 连续段的起点。
     */
    private long optimizedConflictIndex(long prevIndex) {
        if (prevIndex > log.lastIndex()) {
            return log.lastIndex() + 1;
        }
        if (prevIndex <= 0) {
            return 1L;
        }
        long term = log.termAt(prevIndex);
        long index = prevIndex;
        while (index > 1 && log.termAt(index - 1) == term) {
            index--;
        }
        return index;
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
