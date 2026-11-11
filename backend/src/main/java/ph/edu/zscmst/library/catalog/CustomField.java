package ph.edu.zscmst.library.catalog;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A library-defined extra catalog field (e.g. "Accession number", "Donor", "Thesis adviser"). */
@Entity
@Table(name = "custom_field")
public class CustomField {

    public enum Type { TEXT, NUMBER, DATE, SELECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "field_key")
    private String key;
    private String label;
    @Enumerated(EnumType.STRING)
    @Column(name = "field_type")
    private Type type;
    private String options;
    private boolean showInOpac = true;
    private int sortOrder;

    protected CustomField() {
    }

    public CustomField(String key) {
        this.key = key;
    }

    public List<String> optionList() {
        return options == null || options.isBlank() ? List.of()
                : Arrays.stream(options.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public boolean isShowInOpac() { return showInOpac; }
    public void setShowInOpac(boolean showInOpac) { this.showInOpac = showInOpac; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
