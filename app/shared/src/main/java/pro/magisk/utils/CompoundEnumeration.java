/**
 * Combines multiple {@link Enumeration} instances into a single enumeration.
 *
 * Iterates through each sub-enumeration in order, skipping null entries.
 * Used by {@link DynamicClassLoader#getResources} to merge resource listings
 * from multiple class loader sources.
 *
 * @param <E> the element type
 */
package pro.magisk.utils;

import java.util.Enumeration;
import java.util.NoSuchElementException;

public class CompoundEnumeration<E> implements Enumeration<E> {
    private Enumeration<E>[] enums;
    private int index = 0;

    /**
     * Creates a compound enumeration over the given sub-enumerations.
     *
     * @param enums the enumerations to combine; null entries are skipped
     */
    @SafeVarargs
    public CompoundEnumeration(Enumeration<E> ...enums) {
        this.enums = enums;
    }

    /** Advances to the next non-empty sub-enumeration that has elements. */
    private boolean next() {
        while (index < enums.length) {
            if (enums[index] != null && enums[index].hasMoreElements()) {
                return true;
            }
            index++;
        }
        return false;
    }

    /** Returns true if there are more elements across all sub-enumerations. */
    public boolean hasMoreElements() {
        return next();
    }

    /** Returns the next element from the current sub-enumeration. */
    public E nextElement() {
        if (!next()) {
            throw new NoSuchElementException();
        }
        return enums[index].nextElement();
    }
}
