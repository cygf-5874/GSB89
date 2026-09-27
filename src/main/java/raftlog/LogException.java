package raftlog;

/**
 * 本地日志层的错误：非法入参、下标不连续或下标越界。
 *
 * <p>完整实现，勿改。
 */
public class LogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 用一句话说明出错原因。 */
    public LogException(String message) {
        super(message);
    }
}
