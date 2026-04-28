package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a label used to categorize {@link Person}s, {@link Company}s, or {@link Deal}s.
 * Tags are many-to-many with the records they label; no dedicated mapper — managed inline by each entity's mapper.
 */

@Data
@NoArgsConstructor
public class Tag {
    private int id;
    private String name;
    private String color; // hex color code, e.g. "#FF5733"

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
