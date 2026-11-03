package db.storage.page;

public enum LinePointerState {
    UNUSED (0),
    NORMAL (1),
    REDIRECT (2),
    DEAD (3);

    private final int code;
    LinePointerState(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static LinePointerState fromCode(int code) {
        for (LinePointerState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("Invalid LinePointerState code: " + code);
    }
}
