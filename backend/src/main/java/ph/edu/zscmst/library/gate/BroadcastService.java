package ph.edu.zscmst.library.gate;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.digital.FileStorage;

/**
 * Announcement presets and broadcasts. A preset may carry a recorded audio file (e.g. a staff
 * member reading it in Filipino); kiosks play the recording, or speak the text when there is none.
 */
@Service
public class BroadcastService {

    /** Stored form (JSON in app_setting "broadcast.presets"). */
    public record StoredPreset(String id, String name, String message, String audioKey, String audioFilename,
                               String audioType) {
    }

    /** What the staff UI sends and receives. */
    public record Preset(String id, @NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 500) String message,
                         boolean hasAudio, String audioFilename) {
    }

    public record CurrentBroadcast(String message, Instant at, String presetId, boolean hasAudio) {
    }

    public record Audio(FileStorage storage, String key, String contentType) {
        public ResponseEntity<Resource> response() {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CacheControl.noStore()).body(new FileSystemResource(storage.path(key)));
        }
    }

    private final SettingsService settings;
    private final FileStorage storage;
    private final JsonMapper json;
    private final AuditService audit;
    private final SystemSettings system;

    public BroadcastService(SettingsService settings, FileStorage storage, JsonMapper json, AuditService audit,
                            SystemSettings system) {
        this.system = system;
        this.settings = settings;
        this.storage = storage;
        this.json = json;
        this.audit = audit;
    }

    private List<StoredPreset> load() {
        try {
            List<StoredPreset> list = new ArrayList<>(List.of(json.readValue(settings.get("broadcast.presets", "[]"),
                    StoredPreset[].class)));
            // Presets created before IDs existed get one now, so recordings can be attached to them.
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                StoredPreset p = list.get(i);
                if (p.id() == null) {
                    list.set(i, new StoredPreset(UUID.randomUUID().toString(), p.name(), p.message(), p.audioKey(),
                            p.audioFilename(), p.audioType()));
                    changed = true;
                }
            }
            if (changed) store(list);
            return list;
        } catch (JacksonException e) {
            return new ArrayList<>();
        }
    }

    private void store(List<StoredPreset> list) {
        settings.put("broadcast.presets", json.writeValueAsString(list));
    }

    private static Preset dto(StoredPreset p) {
        return new Preset(p.id(), p.name(), p.message(), p.audioKey() != null, p.audioFilename());
    }

    @Transactional
    public List<Preset> presets() {
        return load().stream().map(BroadcastService::dto).toList();
    }

    /** Replaces the preset list; recordings stay with their preset id, and orphaned files are deleted. */
    @Transactional
    public List<Preset> update(List<Preset> incoming) {
        Map<String, StoredPreset> old = new HashMap<>();
        load().forEach(p -> old.put(p.id(), p));
        List<StoredPreset> next = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Preset p : incoming) {
            StoredPreset prev = p.id() == null ? null : old.get(p.id());
            String id = prev != null ? prev.id() : UUID.randomUUID().toString();
            next.add(new StoredPreset(id, p.name().trim(), p.message().trim(), prev == null ? null : prev.audioKey(),
                    prev == null ? null : prev.audioFilename(), prev == null ? null : prev.audioType()));
            kept.add(id);
        }
        store(next);
        old.values().stream().filter(p -> !kept.contains(p.id()) && p.audioKey() != null)
                .forEach(p -> storage.delete(p.audioKey()));
        audit.record("BROADCAST_PRESETS", next.size() + " preset(s): "
                + String.join(", ", next.stream().map(StoredPreset::name).toList()));
        return next.stream().map(BroadcastService::dto).toList();
    }

    @Transactional
    public Preset attachAudio(String presetId, MultipartFile file) {
        List<StoredPreset> list = load();
        int idx = indexOf(list, presetId);
        if (file.isEmpty()) throw ApiException.badRequest("The file is empty");
        system.checkUpload(SystemSettings.UploadKind.AUDIO, file.getSize());
        AudioFormat format;
        FileStorage.StoredFile stored;
        try (InputStream raw = file.getInputStream(); BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(64);
            byte[] head = in.readNBytes(16);
            in.reset();
            format = AudioFormat.detect(head);
            if (format == null) throw ApiException.badRequest("Upload an MP3 (or WAV, OGG, M4A, WebM) audio file");
            stored = storage.store(in);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        StoredPreset p = list.get(idx);
        String name = file.getOriginalFilename() == null ? "recording" : file.getOriginalFilename()
                .replace('\\', '/').replaceAll(".*/", "").replaceAll("[\\p{Cntrl}\"]", "");
        list.set(idx, new StoredPreset(p.id(), p.name(), p.message(), stored.key(),
                name.length() > 120 ? name.substring(name.length() - 120) : name, format.contentType()));
        store(list);
        if (p.audioKey() != null) storage.delete(p.audioKey());
        audit.record("BROADCAST_AUDIO", p.name() + ": " + name);
        return dto(list.get(idx));
    }

    @Transactional
    public Preset removeAudio(String presetId) {
        List<StoredPreset> list = load();
        int idx = indexOf(list, presetId);
        StoredPreset p = list.get(idx);
        list.set(idx, new StoredPreset(p.id(), p.name(), p.message(), null, null, null));
        store(list);
        if (p.audioKey() != null) storage.delete(p.audioKey());
        return dto(list.get(idx));
    }

    @Transactional
    public Audio presetAudio(String presetId) {
        List<StoredPreset> list = load();
        StoredPreset p = list.get(indexOf(list, presetId));
        if (p.audioKey() == null) throw ApiException.notFound("Recording");
        return new Audio(storage, p.audioKey(), p.audioType());
    }

    /** Sends an announcement; with a preset id, kiosks play that preset's recording if it has one. */
    @Transactional
    public void broadcast(String message, String presetId) {
        String pid = "";
        if (presetId != null && !presetId.isBlank()) {
            List<StoredPreset> list = load();
            pid = list.get(indexOf(list, presetId)).id();
        }
        settings.put("broadcast.message", message.trim());
        settings.put("broadcast.presetId", pid);
        settings.put("broadcast.at", Instant.now().toString());
    }

    @Transactional
    public CurrentBroadcast current() {
        String at = settings.get("broadcast.at", "");
        if (at.isBlank()) return null;
        String pid = settings.get("broadcast.presetId", "");
        boolean hasAudio = !pid.isBlank() && load().stream().anyMatch(p -> p.id().equals(pid) && p.audioKey() != null);
        return new CurrentBroadcast(settings.get("broadcast.message", ""), Instant.parse(at), pid.isBlank() ? null : pid,
                hasAudio);
    }

    /** Audio of the broadcast currently being announced (for kiosks). */
    @Transactional
    public Audio currentAudio() {
        String pid = settings.get("broadcast.presetId", "");
        if (pid.isBlank()) throw ApiException.notFound("Recording");
        return presetAudio(pid);
    }

    private static int indexOf(List<StoredPreset> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        throw ApiException.notFound("Preset");
    }
}
