package db.access.tuple;

import static java.util.Objects.requireNonNull;

public record Column(String name, Type type, boolean nullable, int maxLength) {
    // the 2-byte unsigned length prefix holds 65535 bytes; one UTF-8 character takes at most 4
    public static final int MAX_VARCHAR_LENGTH = 65535 / 4;

    public Column {
        requireNonNull(name, "column name must not be null");
        requireNonNull(type, "type of column " + name + " must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }

        if (type == Type.VARCHAR) {
            if (maxLength < 1) {
                throw new IllegalArgumentException("VARCHAR column " + name + " needs maxLength of at least 1, got " + maxLength);
            }
            if (maxLength > MAX_VARCHAR_LENGTH) {
                throw new IllegalArgumentException("VARCHAR column " + name + " maxLength must be at most " + MAX_VARCHAR_LENGTH + ", got " + maxLength);
            }
        } else {
            if (maxLength != 0) {
                throw new IllegalArgumentException("maxLength applies only to VARCHAR, got " + maxLength + " for " + type + " column " + name);
            }
        }
    }

    public static Column of(String name, Type type, boolean nullable) {
        return new Column(name, type, nullable, 0);
    }

    public static Column varchar(String name, int maxLength, boolean nullable) {
        return new Column(name, Type.VARCHAR, nullable, maxLength);
    }

}
