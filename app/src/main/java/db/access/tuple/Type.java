package db.access.tuple;

public enum Type {
    INT(1, 4, Integer.class),
    BIGINT(2, 8, Long.class),
    BOOLEAN(3, 1, Boolean.class),
    VARCHAR(4, -1, String.class);

    private final int code;
    private final int size;
    private final Class<?> javaClass;

    Type(int code, int size, Class<?> javaClass) {
        this.code = code;
        this.size = size;
        this.javaClass = javaClass;
    }

    public int code() {
        return this.code;
    }

    public Class<?> getJavaClass() {
        return this.javaClass;
    }

    public static Type fromCode(int code) {
        for (Type type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown type code " + code);
    }

    public boolean isFixedLength() {
        return this.size != -1;
    }

    public int fixedSize() {
        if (!isFixedLength()) {
            throw new IllegalStateException(fromCode(this.code) + " has no fixed size");
        }
        return this.size;
    }
}
