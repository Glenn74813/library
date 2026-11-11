package ph.edu.zscmst.library.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.marc4j.MarcStreamReader;
import org.marc4j.MarcStreamWriter;
import org.marc4j.marc.Record;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.MaterialType;

class MarcMapperTest {

    @Test
    void exportThenImportKeepsTheTitleAndCopies() {
        BibRecord b = new BibRecord();
        b.setTitle("The Notebook");
        b.setSubtitle("a novel");
        b.setAuthors("Sparks, Nicholas; Doe, Jane");
        b.setIsbn("9780446605236");
        b.setPublisher("Warner Books");
        b.setPublicationYear(1996);
        b.setSubjects("Romance fiction; North Carolina -- Fiction");
        b.setCallNumber("FIC SPA 1996");
        b.setMaterialType(MaterialType.BOOK);
        b.setLanguage("English");
        Item copy = new Item(b, "LRC-000123");
        copy.setLocation("Fiction Section");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarcStreamWriter w = new MarcStreamWriter(out, "UTF-8");
        w.write(MarcMapper.toMarc(b, List.of(copy)));
        w.close();
        Record r = new MarcStreamReader(new ByteArrayInputStream(out.toByteArray()), "UTF-8").next();

        ParsedTitle t = MarcMapper.toTitle(r);
        assertThat(t.title).isEqualTo("The Notebook");
        assertThat(t.subtitle).isEqualTo("a novel");
        assertThat(t.authors).isEqualTo("Sparks, Nicholas; Doe, Jane");
        assertThat(t.isbn).isEqualTo("9780446605236");
        assertThat(t.publisher).isEqualTo("Warner Books");
        assertThat(t.publicationYear).isEqualTo(1996);
        assertThat(t.subjects).contains("Romance fiction");
        assertThat(t.callNumber).isEqualTo("FIC SPA 1996");
        assertThat(t.language).isEqualTo("English");
        assertThat(t.materialType).isEqualTo(MaterialType.BOOK);
        assertThat(t.copies).extracting(ParsedTitle.Copy::barcode).containsExactly("LRC-000123");
    }

    @Test
    void stripsIsbdPunctuation() {
        assertThat(MarcMapper.clean("A walk to remember /")).isEqualTo("A walk to remember");
        assertThat(MarcMapper.clean("New York :")).isEqualTo("New York");
        assertThat(MarcMapper.clean("[2002]")).isEqualTo("2002");
    }
}
