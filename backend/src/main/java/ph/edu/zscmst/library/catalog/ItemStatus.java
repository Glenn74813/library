package ph.edu.zscmst.library.catalog;

public enum ItemStatus {
    AVAILABLE, ON_LOAN, ON_HOLD, LOST, MISSING, DAMAGED, WITHDRAWN;

    /** Statuses an item can be manually set to by staff (others are driven by circulation). */
    public boolean isManual() {
        return this != ON_LOAN && this != ON_HOLD;
    }
}
