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

    /** The Library of Congress catalog over native Z39.50. */
    static final String LOC_Z3950 = "lx2.loc.gov:210/LCDB";
    /** A Z39.50 target as host:port/database (nothing that could reach the YAZ command line as an option). */
    private static final Pattern TARGET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]{1,5}/[A-Za-z0-9_.-]+$");
    private static final Pattern HITS = Pattern.compile("Number of hits: (\\d+)");
    private static final int Z3950_MAX = 10;

    /**
     * {@code source}: "nlp" for the National Library of the Philippines, "loc-z3950" for the Library of Congress
     * over native Z39.50, "z3950" for any other Z39.50 server ({@code target} = host:port/database), otherwise
     * the Library of Congress over SRU.
     */
    public SearchResult search(String source, String isbn, String title, String author, String target) {
        if ("nlp".equalsIgnoreCase(source)) return searchNlp(isbn, title, author);
        if ("loc-z3950".equalsIgnoreCase(source)) return searchZ3950(LOC_Z3950, "Library of Congress (Z39.50)", isbn, title, author);
        if ("z3950".equalsIgnoreCase(source)) {
            String t = target == null ? "" : target.trim();
            if (!TARGET.matcher(t).matches()) throw ApiException.badRequest("Enter the Z39.50 server as host:port/database, e.g. z3950.example.edu:210/books");
            return searchZ3950(t, "Z39.50 " + t, isbn, title, author);
        }
        return search(isbn, title, author);
    }

    /**
     * Native Z39.50 search with the YAZ client (installed in the server image): a type-1 (RPN) query by ISBN
     * (Bib-1 use 7) or title (4) and author (1003), up to ten USMARC records, read with marc4j (MARC-8 or UTF-8).
     */
    SearchResult searchZ3950(String target, String name, String isbn, String title, String author) {
        String rpn;
        if (isbn != null && !isbn.isBlank()) {
            rpn = "@attr 1=7 " + rpnTerm(isbn.replaceAll("[^0-9Xx]", ""));
        } else {
            List<String> parts = new ArrayList<>();
            if (title != null && !title.isBlank()) parts.add("@attr 1=4 " + rpnTerm(title));
            if (author != null && !author.isBlank()) parts.add("@attr 1=1003 " + rpnTerm(author));
            if (parts.isEmpty()) throw ApiException.badRequest("Enter an ISBN, or a title and/or author");
            rpn = parts.size() == 1 ? parts.getFirst() : "@and " + parts.get(0) + " " + parts.get(1);
        }
        java.nio.file.Path file = null;
        try {
            file = java.nio.file.Files.createTempFile("z3950-", ".mrc");
            Process p = new ProcessBuilder("yaz-client", "-m", file.toString()).redirectErrorStream(true).start();
            // One command at a time, each after the previous answer (YAZ prints "Elapsed:" when one is done):
            // piping them all at once lets the client quit before the last records arrive.
            var lines = new java.util.concurrent.LinkedBlockingQueue<String>();
            StringBuilder output = new StringBuilder();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                 var in = new java.io.PrintStream(p.getOutputStream(), true, StandardCharsets.UTF_8)) {
                exec.submit(() -> {
                    try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) lines.add(line);
                    }
                    lines.add("\u0000EOF");
                    return null;
                });
                in.println("format usmarc");
                boolean ok = send(in, "open tcp:" + target, lines, output, deadline)
                        && send(in, "find " + rpn, lines, output, deadline) && HITS.matcher(output).find();
                // The first record, then "show" (the next one): a range beyond the hits makes servers refuse the
                // whole request, and some refuse positions after the first.
                for (int i = 1; ok && i <= Z3950_MAX; i++) {
                    int before = output.length();
                    ok = send(in, i == 1 ? "show 1" : "show", lines, output, deadline)
                            && !output.substring(before).contains("Diagnostic");
                }
                in.println("quit");
                if (System.nanoTime() > deadline) {
                    p.destroyForcibly();
                    throw ApiException.badRequest(name + " did not answer within 30 seconds. Check the address and the internet connection.");
                }
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                p.destroyForcibly();
            }
            Matcher hits = HITS.matcher(output.toString());
            if (!hits.find()) {
                log.warn("Z39.50 {} gave no result: {}", target, output.length() > 500 ? output.substring(0, 500) : output.toString());
                throw ApiException.badRequest(name + " could not be searched (connection refused or the server rejected the search).");
            }
            int total = Integer.parseInt(hits.group(1));
            List<Candidate> out = new ArrayList<>();
            byte[] marc = java.nio.file.Files.readAllBytes(file);
            var records = new org.marc4j.MarcPermissiveStreamReader(new ByteArrayInputStream(marc), true, true);
            while (records.hasNext() && out.size() < Z3950_MAX) {
                ParsedTitle t = MarcMapper.toTitle(records.next());
                out.add(new Candidate(name, t.title, t.subtitle, t.authors, t.isbn, t.publisher, t.publicationYear,
                        t.edition, t.subjects, t.callNumber, t.materialType, t.language, t.summary));
            }
            return new SearchResult(name, total, out);
        } catch (java.io.IOException e) {
            log.warn("Z39.50 search failed: {}", e.getMessage());
            throw ApiException.badRequest("Z39.50 searching is not available on this server (YAZ client missing).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badRequest(name + " search was interrupted");
        } finally {
            if (file != null) {
                try {
                    java.nio.file.Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) {
                    // a temp file left behind is harmless
                }
            }
        }
    }

    /**
     * Sends one YAZ command and collects its answer up to the "Elapsed:" line YAZ prints when it is done.
     * False when the client ended or the overall time ran out.
     */
    private static boolean send(java.io.PrintStream in, String command, java.util.concurrent.BlockingQueue<String> lines,
                                StringBuilder output, long deadline) throws InterruptedException {
        in.println(command);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            String line = lines.poll(left, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (line == null || line.equals("\u0000EOF")) return false;
            output.append(line).append('\n');
            if (line.contains("Elapsed:")) return true;
            // Connection problems end the command without an "Elapsed:" line.
            String l = line.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("connection refused") || l.contains("connect failed") || l.contains("unable to resolve")
                    || l.contains("not connected") || l.contains("connection lost") || l.contains("error =")) return false;
        }
    }

    /** A quoted RPN term; quotes, backslashes and line breaks are dropped so the query can't be broken out of. */
    private static String rpnTerm(String s) {
        return "\"" + s.replaceAll("[\"\\\\\\r\\n]", " ").trim() + "\"";
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
