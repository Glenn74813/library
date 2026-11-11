package ph.edu.zscmst.library.catalog;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.patron.PatronCategory;

/**
 * Which copies may leave the library. Material types listed in the "circulation.roomUseTypes"
 * setting are room use only, unless a copy overrides it either way.
 */
@Service
public class LoanPolicy {

    public static final String ROOM_USE_TYPES = "circulation.roomUseTypes";
    public static final String TYPE_LOAN_DAYS = "circulation.typeLoanDays";

    private final SettingsService settings;

    public LoanPolicy(SettingsService settings) {
        this.settings = settings;
    }

    public Set<MaterialType> roomUseTypes() {
        Set<MaterialType> types = EnumSet.noneOf(MaterialType.class);
        Arrays.stream(settings.get(ROOM_USE_TYPES, "").split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try {
                        types.add(MaterialType.valueOf(s));
                    } catch (IllegalArgumentException ignored) {
                        // unknown type name in settings: skip
                    }
                });
        return types;
    }

    public void setRoomUseTypes(Set<MaterialType> types) {
        settings.put(ROOM_USE_TYPES, types.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
    }

    public boolean isRoomUse(Item i) {
        return i.getRoomUseOnly() != null ? i.getRoomUseOnly() : roomUseTypes().contains(i.getRecord().getMaterialType());
    }

    /** Loan period overrides per material type (e.g. AUDIOVISUAL=3); types not listed use the patron category. */
    public Map<MaterialType, Integer> typeLoanDays() {
        Map<MaterialType, Integer> days = new EnumMap<>(MaterialType.class);
        for (String pair : settings.get(TYPE_LOAN_DAYS, "").split(",")) {
            String[] kv = pair.split("=");
            if (kv.length != 2) continue;
            try {
                int n = Integer.parseInt(kv[1].trim());
                if (n > 0) days.put(MaterialType.valueOf(kv[0].trim()), n);
            } catch (IllegalArgumentException ignored) {
                // malformed entry: skip
            }
        }
        return days;
    }

    public void setTypeLoanDays(Map<MaterialType, Integer> days) {
        settings.put(TYPE_LOAN_DAYS, days.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(e -> e.getKey().name() + "=" + e.getValue()).sorted().collect(Collectors.joining(",")));
    }

    /** Loan period for this copy and borrower: the material-type rule if there is one, else the category's. */
    public int loanDays(Item i, PatronCategory category) {
        return typeLoanDays().getOrDefault(i.getRecord().getMaterialType(), category.getLoanDays());
    }

    public int bookingHorizonDays() {
        return intSetting("circulation.bookingHorizonDays", 60, 1, 365);
    }

    public int atRiskDays() {
        return intSetting("circulation.atRiskDays", 2, 0, 30);
    }

    public BigDecimal defaultLostFee() {
        try {
            return new BigDecimal(settings.get("circulation.defaultLostFee", "0").trim()).max(BigDecimal.ZERO);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private int intSetting(String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(settings.get(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Patron renewals (portal, kiosk, SIP2) wait for staff approval; desk renewals never do. */
    public boolean renewalApproval() {
        return !"false".equals(settings.get("circulation.renewalApproval", "true"));
    }

    public void setRenewalApproval(boolean required) {
        settings.put("circulation.renewalApproval", String.valueOf(required));
    }

    public boolean isLoanable(Item i) {
        return !isRoomUse(i);
    }
}
