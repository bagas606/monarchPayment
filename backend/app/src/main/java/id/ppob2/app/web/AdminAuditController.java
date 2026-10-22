package id.ppob2.app.web;

import id.ppob2.app.admin.AdminAuditReader;
import id.ppob2.app.admin.AdminAuditReader.AuditRow;
import id.ppob2.app.admin.AdminTransactionReader.Page;
import id.ppob2.app.admin.Paging;
import id.ppob2.app.admin.SqlFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Section 41.12: audit log browse/filter. Before/after states are redacted by key name. */
@RestController
public class AdminAuditController {

    private final AdminAuditReader reader;

    public AdminAuditController(AdminAuditReader reader) {
        this.reader = reader;
    }

    @GetMapping("/admin/audit-logs")
    @PreAuthorize("hasAuthority('audit:view')")
    public ResponseEntity<Page<AuditRow>> list(
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "target_id", required = false) Long targetId,
            @RequestParam(name = "actor_id", required = false) Long actorId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(reader.list(SqlFilter.blankToNull(action), SqlFilter.blankToNull(targetType),
                targetId, actorId, Paging.instant("from", from), Paging.instant("to", to), Paging.of(page, size)));
    }
}
