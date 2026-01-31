package protocatalyst.encoder;

/** Java enums for testing ProtoEncoder Java enum support. */
public class TestJavaEnums {

    /** Simple Java enum with a few values. */
    public enum Priority {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /** Java enum representing days of the week. */
    public enum DayOfWeek {
        MONDAY,
        TUESDAY,
        WEDNESDAY,
        THURSDAY,
        FRIDAY,
        SATURDAY,
        SUNDAY
    }

    /** Java enum with custom fields and methods (common pattern). */
    public enum HttpStatus {
        OK(200),
        NOT_FOUND(404),
        INTERNAL_ERROR(500);

        private final int code;

        HttpStatus(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
