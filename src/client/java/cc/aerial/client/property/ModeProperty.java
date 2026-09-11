package cc.aerial.client.property;

public final class ModeProperty<T extends Enum<T>> extends Property<T> {
    private final T[] values;

    public ModeProperty(String name, T value) {
        super(name);
        setValue(value);
        this.values = getEnumConstants();
    }

    /**
     * The enum's constants.
     *
     * <p>Asked of {@code getDeclaringClass()} and not of {@code getClass()}: a constant that carries its own
     * body is an instance of an anonymous subclass, and {@code Class.getEnumConstants()} answers null for
     * that. Reading it off the declaring class is also the only form that needs no cast.
     */
    private T[] getEnumConstants() {
        return getValue().getDeclaringClass().getEnumConstants();
    }

    public T[] getValues() {
        return values;
    }

    public void setValueOrdinal(int value) {
        setValue(values[value]);
    }

    public void cycle(boolean forwards) {
        int currentIndex = getValue().ordinal();
        int nextIndex = (currentIndex + (forwards ? 1 : values.length - 1)) % values.length;
        setValueOrdinal(nextIndex);
    }

    public boolean is(T value) {
        return getValue() == value;
    }
}
