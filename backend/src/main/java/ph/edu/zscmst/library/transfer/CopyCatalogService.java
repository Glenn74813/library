package ph.edu.zscmst.library.transfer;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.marc4j.MarcXmlReader;
import org.marc4j.marc.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.SettingsService;

/**
 * Copy cataloging over SRU (the HTTP form of Z39.50 offered by the Library of Congress and many
 * other catalogs). Results are MARC21 records mapped to catalog fields for the record form.
 */
@Service
public class CopyCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CopyCatalogService.class);
    private static final String MARC_NS = "http://www.loc.gov/MARC21/slim";

    public record Candidate(String source, String title, String subtitle, String authors, String isbn, String publisher,
                            Integer publicationYear, String edition, String subjects, String callNumber,
                            MaterialType materialType, String language, String summary) {
    }

    public record SearchResult(String target, int total, List<Candidate> records) {
    }

    private final SettingsService settings;
    private final RestClient http;

    public CopyCatalogService(SettingsService settings) {
        this.settings = settings;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(8000);
        f.setReadTimeout(20000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public SearchResult search(String isbn, String title, String author) {
        String target = settings.get("copycat.name", "Library of Congress");
        String base = settings.get("copycat.url", "http://lx2.loc.gov:210/lcdb");
        String cql;
        if (isbn != null && !isbn.isBlank()) {
            cql = "bath.isbn=" + isbn.replaceAll("[^0-9Xx]", "");
        } else {
            List<String> parts = new ArrayList<>();
            if (title != null && !title.isBlank()) parts.add("dc.title=\"" + quote(title) + "\"");
            if (author != null && !author.isBlank()) parts.add("dc.creator=\"" + quote(author) + "\"");
            if (parts.isEmpty()) throw ApiException.badRequest("Enter an ISBN, or a title and/or author");
            cql = String.join(" and ", parts);
        }
        String url = base + "?version=1.1&operation=searchRetrieve&maximumRecords=10&recordSchema=marcxml&query="
                + URLEncoder.encode(cql, StandardCharsets.UTF_8);
        byte[] body;
        try {
            body = http.get().uri(URI.create(url)).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            log.warn("Copy-catalog search failed: {}", e.getMessage());
            throw ApiException.badRequest(target + " did not answer. Check the internet connection and try again.");
        }
        return parse(target, body == null ? new byte[0] : body);
    }

    SearchResult parse(String target, byte[] xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no XXE
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            int total = 0;
            NodeList counts = doc.getElementsByTagNameNS("*", "numberOfRecords");
            if (counts.getLength() > 0) total = Integer.parseInt(counts.item(0).getTextContent().trim());
            NodeList recs = doc.getElementsByTagNameNS(MARC_NS, "record");
            List<Candidate> out = new ArrayList<>();
            var tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            for (int i = 0; i < recs.getLength(); i++) {
                StringWriter w = new StringWriter();
                var t = tf.newTransformer();
                t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                t.transform(new DOMSource((Element) recs.item(i)), new StreamResult(w));
                MarcXmlReader reader = new MarcXmlReader(new ByteArrayInputStream(
                        ("<collection xmlns=\"" + MARC_NS + "\">" + w + "</collection>").getBytes(StandardCharsets.UTF_8)));
                if (!reader.hasNext()) continue;
                Record r = reader.next();
                ParsedTitle p = MarcMapper.toTitle(r);
                out.add(new Candidate(target, p.title, p.subtitle, p.authors, p.isbn, p.publisher, p.publicationYear,
                        p.edition, p.subjects, p.callNumber, p.materialType, p.language, p.summary));
            }
            return new SearchResult(target, total, out);
        } catch (Exception e) {
            log.warn("Could not read copy-catalog response: {}", e.getMessage());
            throw ApiException.badRequest("The response from " + target + " could not be read");
        }
    }

    private static String quote(String s) {
        return s.trim().replace("\\", "").replace("\"", "");
    }
}
