package io.nexora.content;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A custom metadata column on a document library. Values live in {@link Node#getMetadata()}. */
@Entity
@Table(name = "column_def")
public class ColumnDef {

    public enum Type { TEXT, NOTE, NUMBER, DATE, BOOLEAN, CHOICE }

    @Id
    private UUID id;
    private UUID libraryId;
    private String internalName;
    private String displayName;
    @Enumerated(EnumType.STRING)
    private Type type;
    private boolean required;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> choices = new ArrayList<>();
    private int position;

    protected ColumnDef() {}

    public ColumnDef(UUID libraryId, String internalName, String displayName, Type type, boolean required,
                     List<String> choices, int position) {
        this.id = UUID.randomUUID();
        this.libraryId = libraryId;
        this.internalName = internalName;
        this.displayName = displayName;
        this.type = type;
        this.required = required;
        this.choices = choices == null ? new ArrayList<>() : new ArrayList<>(choices);
        this.position = position;
    }

    public UUID getId() { return id; }
    public UUID getLibraryId() { return libraryId; }
    public String getInternalName() { return internalName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Type getType() { return type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public List<String> getChoices() { return choices == null ? List.of() : choices; }
    public void setChoices(List<String> choices) { this.choices = new ArrayList<>(choices); }
    public int getPosition() { return position; }
}
