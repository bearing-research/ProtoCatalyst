package protocatalyst.executor.exec

/** Exception thrown when query execution fails. */
class ExecutionException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
