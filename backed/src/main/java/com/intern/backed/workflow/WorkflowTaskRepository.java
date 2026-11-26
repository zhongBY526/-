package com.intern.backed.workflow;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class WorkflowTaskRepository {
    private final JdbcTemplate jdbc;

    public WorkflowTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 已有 docker volume 不会重跑 init.sql，这里兜底建表保证老环境可用
    @PostConstruct
    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS workflow_task (
                    id             BIGSERIAL PRIMARY KEY,
                    question       VARCHAR(1024) NOT NULL,
                    kb_ids         VARCHAR(255)  NOT NULL DEFAULT '',
                    status         VARCHAR(24)   NOT NULL DEFAULT 'RUNNING',
                    intent         VARCHAR(16)   NOT NULL DEFAULT '',
                    draft          TEXT          NOT NULL DEFAULT '',
                    format_report  TEXT          NOT NULL DEFAULT '',
                    sources        TEXT          NOT NULL DEFAULT '',
                    steps          TEXT          NOT NULL DEFAULT '',
                    confirm_comment VARCHAR(512) NOT NULL DEFAULT '',
                    error_msg      VARCHAR(1024),
                    created_by     BIGINT        NOT NULL REFERENCES users (id),
                    created_at     TIMESTAMP     NOT NULL DEFAULT now(),
                    updated_at     TIMESTAMP     NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_task_user ON workflow_task (created_by)");
    }

    private static final RowMapper<WorkflowTask> MAPPER = (rs, i) -> new WorkflowTask(
            rs.getLong("id"),
            rs.getString("question"),
            rs.getString("kb_ids").isBlank() ? List.of()
                    : Arrays.stream(rs.getString("kb_ids").split(",")).map(Long::valueOf).toList(),
            rs.getString("status"),
            rs.getString("intent"),
            rs.getString("draft"),
            rs.getString("format_report"),
            rs.getString("sources"),
            rs.getString("steps"),
            rs.getString("confirm_comment"),
            rs.getString("error_msg"),
            rs.getLong("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    public long insert(String question, List<Long> kbIds, long userId) {
        String kbIdStr = String.join(",", kbIds.stream().map(String::valueOf).toList());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // PG 驱动 RETURN_GENERATED_KEYS 会返回整行，必须显式指定返回列
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO workflow_task (question, kb_ids, created_by) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, question);
            ps.setString(2, kbIdStr);
            ps.setLong(3, userId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<WorkflowTask> findById(long id) {
        return jdbc.query("SELECT * FROM workflow_task WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<WorkflowTask> listByUser(long userId, boolean admin) {
        if (admin) {
            return jdbc.query("SELECT * FROM workflow_task ORDER BY id DESC LIMIT 100", MAPPER);
        }
        return jdbc.query("SELECT * FROM workflow_task WHERE created_by = ? ORDER BY id DESC LIMIT 100", MAPPER, userId);
    }

    /** 图执行完成后一次性落结果（成功→PENDING_CONFIRM，失败→FAILED） */
    public void saveResult(long id, String status, String intent, String draft,
                           String formatReport, String sources, String steps, String errorMsg) {
        jdbc.update("""
                UPDATE workflow_task SET status = ?, intent = ?, draft = ?, format_report = ?,
                    sources = ?, steps = ?, error_msg = ?, updated_at = now() WHERE id = ?
                """, status, intent, draft, formatReport, sources, steps, errorMsg, id);
    }

    /** 人工确认/驳回/导出/重跑等状态迁移，带乐观前置状态校验，返回是否迁移成功 */
    public boolean transition(long id, String fromStatus, String toStatus, String comment) {
        int rows = jdbc.update("""
                UPDATE workflow_task SET status = ?, confirm_comment = ?, updated_at = now()
                WHERE id = ? AND status = ?
                """, toStatus, comment == null ? "" : comment, id, fromStatus);
        return rows > 0;
    }

    /** 重跑：REJECTED/FAILED 回到 RUNNING，清空上一轮结果 */
    public boolean resetForRetry(long id) {
        int rows = jdbc.update("""
                UPDATE workflow_task SET status = 'RUNNING', intent = '', draft = '', format_report = '',
                    sources = '', steps = '', error_msg = NULL, updated_at = now()
                WHERE id = ? AND status IN ('REJECTED', 'FAILED')
                """, id);
        return rows > 0;
    }
}