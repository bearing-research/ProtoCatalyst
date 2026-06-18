package protocatalyst.truffle.exec;

/** The global aggregate functions the backend supports so far (Layer 3, no GROUP BY yet). */
public enum AggKind {
    SUM,
    COUNT,
    MIN,
    MAX,
    AVG
}
