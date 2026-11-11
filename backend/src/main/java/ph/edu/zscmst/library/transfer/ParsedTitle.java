package ph.edu.zscmst.library.transfer;

import java.util.ArrayList;
import java.util.List;

import ph.edu.zscmst.library.catalog.MaterialType;

/** A title read from an import file (MARC record or CSV rows), with its copies. */
public class ParsedTitle {

    public record Copy(String barcode, String location, String aisle, String shelf) {
    }

    public String title;
    public String subtitle;
    public String authors;
    public String isbn;
    public String publisher;
    public Integer publicationYear;
    public String edition;
    public String subjects;
    public String callNumber;
    public MaterialType materialType = MaterialType.BOOK;
    public String language;
    public String summary;
    public final List<Copy> copies = new ArrayList<>();
    /** Where it came from in the file, for the report ("record 12", "line 40"). */
    public String source;
}
