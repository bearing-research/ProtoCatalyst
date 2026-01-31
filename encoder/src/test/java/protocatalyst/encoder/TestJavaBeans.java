package protocatalyst.encoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Java Beans for testing JavaBeanEncoder.
 */
public class TestJavaBeans {

    /**
     * Simple Java Bean with primitive properties.
     */
    public static class SimplePerson {
        private String name;
        private int age;

        public SimplePerson() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    /**
     * Java Bean with various primitive types.
     */
    public static class PrimitiveBean {
        private boolean active;
        private byte byteVal;
        private short shortVal;
        private int intVal;
        private long longVal;
        private float floatVal;
        private double doubleVal;
        private char charVal;

        public PrimitiveBean() {}

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public byte getByteVal() { return byteVal; }
        public void setByteVal(byte byteVal) { this.byteVal = byteVal; }

        public short getShortVal() { return shortVal; }
        public void setShortVal(short shortVal) { this.shortVal = shortVal; }

        public int getIntVal() { return intVal; }
        public void setIntVal(int intVal) { this.intVal = intVal; }

        public long getLongVal() { return longVal; }
        public void setLongVal(long longVal) { this.longVal = longVal; }

        public float getFloatVal() { return floatVal; }
        public void setFloatVal(float floatVal) { this.floatVal = floatVal; }

        public double getDoubleVal() { return doubleVal; }
        public void setDoubleVal(double doubleVal) { this.doubleVal = doubleVal; }

        public char getCharVal() { return charVal; }
        public void setCharVal(char charVal) { this.charVal = charVal; }
    }

    /**
     * Java Bean with boxed primitive types (nullable).
     */
    public static class BoxedBean {
        private Boolean active;
        private Integer count;
        private Long bigCount;
        private Double value;

        public BoxedBean() {}

        public Boolean getActive() { return active; }
        public void setActive(Boolean active) { this.active = active; }

        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }

        public Long getBigCount() { return bigCount; }
        public void setBigCount(Long bigCount) { this.bigCount = bigCount; }

        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
    }

    /**
     * Java Bean with temporal types.
     */
    public static class TemporalBean {
        private LocalDate date;
        private Instant timestamp;

        public TemporalBean() {}

        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }

        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }

    /**
     * Java Bean with BigDecimal.
     */
    public static class DecimalBean {
        private BigDecimal amount;
        private String currency;

        public DecimalBean() {}

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }

    /**
     * Java Bean with enum property.
     */
    public static class EnumBean {
        private String name;
        private TestJavaEnums.Priority priority;

        public EnumBean() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public TestJavaEnums.Priority getPriority() { return priority; }
        public void setPriority(TestJavaEnums.Priority priority) { this.priority = priority; }
    }

    /**
     * Java Bean with nested bean.
     */
    public static class NestedBean {
        private String id;
        private SimplePerson person;

        public NestedBean() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public SimplePerson getPerson() { return person; }
        public void setPerson(SimplePerson person) { this.person = person; }
    }

    /**
     * Address bean for nested testing.
     */
    public static class Address {
        private String street;
        private String city;
        private int zipCode;

        public Address() {}

        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public int getZipCode() { return zipCode; }
        public void setZipCode(int zipCode) { this.zipCode = zipCode; }
    }

    /**
     * Employee bean with multiple nested beans.
     */
    public static class Employee {
        private String employeeId;
        private SimplePerson person;
        private Address address;
        private double salary;

        public Employee() {}

        public String getEmployeeId() { return employeeId; }
        public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

        public SimplePerson getPerson() { return person; }
        public void setPerson(SimplePerson person) { this.person = person; }

        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }

        public double getSalary() { return salary; }
        public void setSalary(double salary) { this.salary = salary; }
    }
}
