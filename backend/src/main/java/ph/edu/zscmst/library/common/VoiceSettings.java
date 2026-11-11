package ph.edu.zscmst.library.common;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Text-to-speech voice for announcements. {@code name} is a browser voice name (it only works on
 * devices that have that voice installed); {@code lang} is the fallback language, e.g. en-PH or fil-PH.
 */
public record VoiceSettings(@Size(max = 200) String name,
                            @NotBlank @Size(max = 20) String lang,
                            @DecimalMin("0.5") @DecimalMax("2.0") double rate,
                            @DecimalMin("0.0") @DecimalMax("2.0") double pitch,
                            @Min(1) @Max(5) int repeat) {

    public static VoiceSettings load(SettingsService s) {
        return new VoiceSettings(s.get("broadcast.voice.name", ""), s.get("broadcast.voice.lang", "en-US"),
                parse(s.get("broadcast.voice.rate", "0.95"), 0.95), parse(s.get("broadcast.voice.pitch", "1"), 1),
                (int) parse(s.get("broadcast.voice.repeat", "2"), 2));
    }

    public void save(SettingsService s) {
        s.put("broadcast.voice.name", name == null ? "" : name.trim());
        s.put("broadcast.voice.lang", lang.trim());
        s.put("broadcast.voice.rate", String.valueOf(rate));
        s.put("broadcast.voice.pitch", String.valueOf(pitch));
        s.put("broadcast.voice.repeat", String.valueOf(repeat));
    }

    private static double parse(String v, double fallback) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
