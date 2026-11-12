package ph.edu.zscmst.library.transfer;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Copy cataloging from two sources, both returning MARC21 records mapped to catalog fields for the record
 * form: the Library of Congress over SRU (the HTTP form of Z39.50), and the National Library of the
 * Philippines through its public Koha catalog (an RSS search, then each record's MARCXML export).
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

    /** Most NLP records fetched per search (each is one request to the NLP catalog). */
    private static final int NLP_MAX = 8;
    private static final Pattern BIBLIO = Pattern.compile("biblionumber=(\\d+)");
    private static final Pattern TOTAL = Pattern.compile("<opensearch:totalResults>\\s*(\\d+)");

    /** {@code source}: "nlp" for the National Library of the Philippines, otherwise the Library of Congress. */
    public SearchResult search(String source, String isbn, String title, String author) {
        return "nlp".equalsIgnoreCase(source) ? searchNlp(isbn, title, author) : search(isbn, title, author);
    }

    /**
     * National Library of the Philippines: its Koha OPAC's RSS search finds the records, then each one's
     * MARCXML export is read. The NLP's site allows search use; requests are made only when staff search.
     */
    SearchResult searchNlp(String isbn, String title, String author) {
        String target = "National Library of the Philippines";
        String base = settings.get("copycat.nlpUrl", "https://koha.nlp.gov.ph").replaceAll("/+$", "");
        String ccl;
        if (isbn != null && !isbn.isBlank()) {
            ccl = "nb:" + isbn.replaceAll("[^0-9Xx]", "");
        } else {
            List<String> parts = new ArrayList<>();
            if (title != null && !title.isBlank()) parts.add("ti:" + kohaTerms(title));
            if (author != null && !author.isBlank()) parts.add("au:" + kohaTerms(author));
            if (parts.isEmpty()) throw ApiException.badRequest("Enter an ISBN, or a title and/or author");
            ccl = String.join(" and ", parts);
        }
        String rss;
        try {
            byte[] body = http.get().uri(URI.create(base + "/cgi-bin/koha/opac-search.pl?format=rss&count=" + NLP_MAX + "&q="
                    + URLEncoder.encode(ccl, StandardCharsets.UTF_8))).header("User-Agent", AGENT).retrieve().body(byte[].class);
            rss = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        } catch (RestClientException e) {
            log.warn("NLP search failed: {}", e.getMessage());
            throw ApiException.badRequest(target + " did not answer. Check the internet connection and try again.");
        }
        Matcher t = TOTAL.matcher(rss);
        int total = t.find() ? Integer.parseInt(t.group(1)) : 0;
        List<String> ids = new ArrayList<>();
        Matcher m = BIBLIO.matcher(rss);
        while (m.find() && ids.size() < NLP_MAX) if (!ids.contains(m.group(1))) ids.add(m.group(1));
        List<Candidate> out = new ArrayList<>();
        for (String id : ids) {
            try {
                byte[] marc = http.get().uri(URI.create(base + "/cgi-bin/koha/opac-export.pl?op=export&format=marcxml&bib=" + id))
                        .header("User-Agent", AGENT).retrieve().body(byte[].class);
                if (marc != null) out.addAll(parse(target, marc).records());
            } catch (RuntimeException e) {
                log.warn("NLP record {} could not be read: {}", id, e.getMessage()); // skip that one record
            }
        }
        return new SearchResult(target, Math.max(total, out.size()), out);
    }

    /** Words only: Koha's query language treats punctuation (colons, parentheses...) as operators. */
    private static String kohaTerms(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}\\s'-]", " ").trim().replaceAll("\\s+", " ");
    }

    private static final String AGENT = "ZSCMST-SmartLibrary/1.0 (library copy cataloging)";

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
