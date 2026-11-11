package ph.edu.zscmst.library.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Key/value runtime settings (kiosk messages, gate modes) editable from the admin UI. */
@Service
public class SettingsService {

    private final AppSettingRepository repository;

    public SettingsService(AppSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return repository.findById(key).map(AppSetting::getValue).orElse(defaultValue);
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }

    @Transactional(readOnly = true)
    public Map<String, String> getByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        repository.findAll().stream()
                .filter(s -> s.getKey().startsWith(prefix))
                .forEach(s -> result.put(s.getKey().substring(prefix.length()), s.getValue() == null ? "" : s.getValue()));
        return result;
    }

    @Transactional
    public void put(String key, String value) {
        repository.save(new AppSetting(key, value));
    }
}
