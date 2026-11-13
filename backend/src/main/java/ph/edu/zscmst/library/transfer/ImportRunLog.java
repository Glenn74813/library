package ph.edu.zscmst.library.transfer;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import ph.edu.zscmst.library.common.AuditService;

/** Keeps a row per committed import, read back by the data migration validation report. */
@Service
public class ImportRunLog {

    public record Run(long id, String kind, String filename, int rowsInFile, int created, int matched, int copiesCreated,
                      int copiesSkipped, List<String> problems, String runBy, Instant runAt) {
    }

    private final JdbcTemplate jdbc;

    public ImportRunLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String kind, String filename, int rowsInFile, int created, int matched, int copiesCreated,
                       int copiesSkipped, List<String> problems) {
        jdbc.update("""
                insert into import_run (kind, filename, rows_in_file, created, matched, copies_created, copies_skipped, problems, run_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", kind, filename, rowsInFile, created, matched, copiesCreated, copiesSkipped,
                problems.isEmpty() ? null : String.join("\n", problems), AuditService.currentActor());
    }

    public List<Run> all() {
        return jdbc.query("select * from import_run order by run_at, id", (rs, n) -> new Run(rs.getLong("id"), rs.getString("kind"),
                rs.getString("filename"), rs.getInt("rows_in_file"), rs.getInt("created"), rs.getInt("matched"),
                rs.getInt("copies_created"), rs.getInt("copies_skipped"),
                rs.getString("problems") == null ? List.of() : List.of(rs.getString("problems").split("\n")),
                rs.getString("run_by"), rs.getTimestamp("run_at").toInstant()));
    }
}
