package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * One weighted risk signal behind a Risk Score.
 *
 * <p>Names form an open set (see {@link SignalName} for the known ones) and can repeat within one
 * identification. Weights can be negative (a late correction) or informational. Use signals for
 * display and logging; never add up the weights yourself, and branch on {@link DetectionFlags} and
 * the Risk Score instead.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"name", "weight", "description"})
public final class Signal {
    private final String name;
    private final int weight;
    private final String description;

    Signal(String name, int weight, String description) {
        this.name = name;
        this.weight = weight;
        this.description = description;
    }

    /**
     * Returns the signal name (slug).
     *
     * @return the name, for example {@code "antidetect_browser"}
     */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /**
     * Returns the weight this signal contributed. Can be negative.
     *
     * @return the weight
     */
    @JsonProperty("weight")
    public int getWeight() {
        return weight;
    }

    /**
     * Returns the human-readable description. Only identifications read from the History API carry
     * one; for webhook identifications it is {@code null}. Descriptions are free text: never branch on
     * them.
     *
     * @return the description, or {@code null}
     */
    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Signal)) {
            return false;
        }
        Signal other = (Signal) o;
        return weight == other.weight && name.equals(other.name) && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, weight, description);
    }

    @Override
    public String toString() {
        return name + "(" + weight + ")";
    }
}
