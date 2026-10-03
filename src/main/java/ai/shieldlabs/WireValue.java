package ai.shieldlabs;

/**
 * Typed raw values separate the declared wire shape from tolerant normalization. Accessors accept
 * only the schema's declared kind, while passing the original value to the existing coercion rules.
 * This deliberately does not validate enums, formats, nulls or unknown response fields.
 */
final class WireValue {
    private WireValue() {}

    static final class StringValue {
        private final Object raw;
        StringValue(Object raw) { this.raw = raw; }
    }
    static final class IntegerValue {
        private final Object raw;
        IntegerValue(Object raw) { this.raw = raw; }
    }
    static final class NumberValue {
        private final Object raw;
        NumberValue(Object raw) { this.raw = raw; }
    }
    static final class BooleanValue {
        private final Object raw;
        BooleanValue(Object raw) { this.raw = raw; }
    }
    static final class ArrayValue {
        private final Object raw;
        ArrayValue(Object raw) { this.raw = raw; }
    }
    static final class ObjectValue {
        private final Object raw;
        ObjectValue(Object raw) { this.raw = raw; }
    }
    static Object string(StringValue value) { return value.raw; }
    static Object integer(IntegerValue value) { return value.raw; }
    static Object number(NumberValue value) { return value.raw; }
    static Object bool(BooleanValue value) { return value.raw; }
    static Object array(ArrayValue value) { return value.raw; }
    static Object object(ObjectValue value) { return value.raw; }
}
