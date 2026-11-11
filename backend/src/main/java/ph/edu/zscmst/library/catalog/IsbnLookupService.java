package ph.edu.zscmst.library.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ph.edu.zscmst.library.catalog.CatalogDtos.IsbnLookupResult;

/** Copy-cataloging helper: fetches bibliographic data for an ISBN from Open Library. */
@Service
public class IsbnLookupService {

    private static final Logger log = LoggerFactory.getLogger(IsbnLookupService.class);
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    private final RestClient client;

    public IsbnLookupService(@Value("${app.isbn-lookup.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @SuppressWarnings("unchecked")
    public Optional<IsbnLookupResult> lookup(String rawIsbn) {
        String isbn = rawIsbn.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (isbn.length() != 10 && isbn.length() != 13) {
            return Optional.empty();
        }
        String key = "ISBN:" + isbn;
        try {
            Map<String, Object> body = client.get()
                    .uri("/api/books?bibkeys={key}&format=json&jscmd=data", key)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (body == null || !(body.get(key) instanceof Map<?, ?> data)) {
                return Optional.empty();
            }
            Map<String, Object> d = (Map<String, Object>) data;
            Integer year = null;
            if (d.get("publish_date") instanceof String date) {
                Matcher m = YEAR.matcher(date);
                if (m.find()) year = Integer.parseInt(m.group(1));
            }
            String cover = d.get("cover") instanceof Map<?, ?> c ? (String) c.get("medium") : null;
            return Optional.of(new IsbnLookupResult(isbn, (String) d.get("title"), (String) d.get("subtitle"),
                    names(d.get("authors"), "; "), names(d.get("publishers"), "; "), year,
                    names(d.get("subjects"), "; "), cover));
        } catch (RestClientException e) {
            log.warn("ISBN lookup failed for {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    private static String names(Object list, String sep) {
        if (!(list instanceof List<?> l) || l.isEmpty()) return null;
        String joined = l.stream()
                .filter(Map.class::isInstance)
                .map(m -> String.valueOf(((Map<?, ?>) m).get("name")))
                .limit(15)
                .collect(Collectors.joining(sep));
        return joined.length() > 900 ? joined.substring(0, 900) : joined;
    }
}
